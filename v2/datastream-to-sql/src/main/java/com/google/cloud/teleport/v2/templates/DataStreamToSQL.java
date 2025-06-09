/*
 * Copyright (C) 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.templates;

import com.google.cloud.teleport.metadata.Template;
import com.google.cloud.teleport.metadata.TemplateCategory;
import com.google.cloud.teleport.metadata.TemplateParameter;
import com.google.cloud.teleport.metadata.TemplateParameter.TemplateEnumOption;
import com.google.cloud.teleport.v2.common.UncaughtExceptionLogger;
import com.google.cloud.teleport.v2.datastream.io.CdcJdbcIO;
import com.google.cloud.teleport.v2.datastream.sources.DataStreamIO;
import com.google.cloud.teleport.v2.datastream.values.DmlInfo;
import com.google.cloud.teleport.v2.templates.DataStreamToSQL.Options;
import com.google.cloud.teleport.v2.transforms.CreateDml;
import com.google.cloud.teleport.v2.transforms.ProcessDml;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.common.base.Splitter;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.StreamingOptions;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.UnknownKeyFor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This pipeline ingests DataStream data from GCS. The data is then cleaned and converted from JSON
 * objects into DML statements. The DML is applied to the desired target database, which can be one
 * of MySQL or PostgreSQL. Replication maintains a 1:1 match between source and target by default.
 * No DDL is supported in the current version of this pipeline.
 *
 * <p>NOTE: Future versions will support: Pub/Sub, GCS, or Kafka as per DataStream
 *
 * <p>Check out <a
 * href="https://github.com/GoogleCloudPlatform/DataflowTemplates/blob/main/v2/datastream-to-sql/README_Cloud_Datastream_to_SQL.md">README</a>
 * for instructions on how to use or modify this template.
 */
@Template(
        name = "Cloud_Datastream_to_SQL",
        category = TemplateCategory.STREAMING,
        displayName = "Datastream to SQL",
        description = {
                "The Datastream to SQL template is a streaming pipeline that reads <a href=\"https://cloud.google.com/datastream/docs\">Datastream</a> data and replicates it into any MySQL or PostgreSQL database. "
                        + "The template reads data from Cloud Storage using Pub/Sub notifications and replicates this data into SQL replica tables.\n",
                "The template does not support data definition language (DDL) and expects that all tables already exist in the database. "
                        + "Replication uses Dataflow stateful transforms to filter stale data and ensure consistency in out of order data. "
                        + "For example, if a more recent version of a row has already passed through, a late arriving version of that row is ignored. "
                        + "The data manipulation language (DML) that executes is a best attempt to perfectly replicate source to target data. The DML statements executed follow the following rules:\n",
                "If a primary key exists, insert and update operations use upsert syntax (ie. <code>INSERT INTO table VALUES (...) ON CONFLICT (...) DO UPDATE</code>).\n"
                        + "If primary keys exist, deletes are replicated as a delete DML.\n"
                        + "If no primary key exists, both insert and update operations are inserted into the table.\n"
                        + "If no primary keys exist, deletes are ignored.\n"
                        + "If you are using the Oracle to Postgres utilities, add <code>ROWID</code> in SQL as the primary key when none exists."
        },
        optionsClass = Options.class,
        flexContainerName = "datastream-to-sql",
        documentation =
                "https://cloud.google.com/dataflow/docs/guides/templates/provided/datastream-to-sql",
        contactInformation = "https://cloud.google.com/support",
        preview = true,
        requirements = {
                "A Datastream stream that is ready to or already replicating data.",
                "<a href=\"https://cloud.google.com/storage/docs/reporting-changes\">Cloud Storage Pub/Sub notifications</a> are enabled for the Datastream data.",
                "A PostgreSQL database was seeded with the required schema.",
                "Network access between Dataflow workers and PostgreSQL is set up."
        },
        streaming = true,
        supportsAtLeastOnce = true)
public class DataStreamToSQL {

    private static final Logger LOG = LoggerFactory.getLogger(DataStreamToSQL.class);
    private static final String AVRO_SUFFIX = "avro";
    private static final String JSON_SUFFIX = "json";

    /**
     * Options supported by the pipeline.
     *
     * <p>Inherits standard configuration options.
     */
    public interface Options extends PipelineOptions, StreamingOptions {
        @TemplateParameter.Text(
                order = 1,
                groupName = "Source",
                description = "File location for Datastream file input in Cloud Storage.",
                helpText =
                        "The file location for the Datastream files in Cloud Storage to replicate. This file location is typically the root path for the stream.")
        String getInputFilePattern();

        void setInputFilePattern(String value);

        @TemplateParameter.PubsubSubscription(
                order = 2,
                optional = true,
                description = "The Pub/Sub subscription being used in a Cloud Storage notification policy.",
                helpText =
                        "The Pub/Sub subscription with Datastream file notifications."
                                + " For example, `projects/<PROJECT_ID>/subscriptions/<SUBSCRIPTION_ID>`.")
        String getGcsPubSubSubscription();

        void setGcsPubSubSubscription(String value);

        @TemplateParameter.Enum(
                order = 3,
                enumOptions = {@TemplateEnumOption("avro"), @TemplateEnumOption("json")},
                optional = true,
                description = "Datastream output file format (avro/json).",
                helpText =
                        "The format of the output file produced by Datastream. For example, `avro` or `json`. Defaults to `avro`.")
        @Default.String("avro")
        String getInputFileFormat();

        void setInputFileFormat(String value);

        @TemplateParameter.Text(
                order = 4,
                groupName = "Source",
                optional = true,
                description = "Name or template for the stream to poll for schema information.",
                helpText =
                        "The name or template for the stream to poll for schema information. The default value is `{_metadata_stream}`.")
        String getStreamName();

        void setStreamName(String value);

        @TemplateParameter.DateTime(
                order = 5,
                optional = true,
                description =
                        "The starting DateTime used to fetch from Cloud Storage "
                                + "(https://tools.ietf.org/html/rfc3339).",
                helpText =
                        "The starting DateTime used to fetch from Cloud Storage "
                                + "(https://tools.ietf.org/html/rfc3339).")
        @Default.String("1970-01-01T00:00:00.00Z")
        String getRfcStartDateTime();

        void setRfcStartDateTime(String value);

        // DataStream API Root Url (only used for testing)
        @TemplateParameter.Text(
                order = 6,
                optional = true,
                description = "Datastream API Root URL (only required for testing)",
                helpText = "Datastream API Root URL")
        @Default.String("https://datastream.googleapis.com/")
        String getDataStreamRootUrl();

        void setDataStreamRootUrl(String value);

        // SQL Connection Parameters
        @TemplateParameter.Enum(
                order = 7,
                optional = true,
                enumOptions = {@TemplateEnumOption("postgres"), @TemplateEnumOption("mysql"), @TemplateEnumOption("oracle")},
                description = "SQL Database Type (postgres or mysql).",
                helpText = "The database type to write to (for example, Postgres).")
        @Default.String("postgres")
        String getDatabaseType();

        void setDatabaseType(String value);

        @TemplateParameter.Text(
                order = 8,
                groupName = "Target",
                description = "Database connection string",
                helpText = "(description=(retry_count=)(retry_delay=)(address=(protocol=(()()())(connect_data=....")
        String getDatabaseHost();

        void setDatabaseHost(String value);

        @TemplateParameter.Text(
                order = 9,
                groupName = "Target",
                optional = true,
                description = "Database Port to connect on.",
                helpText = "The SQL database port to connect to. The default value is `5432`.")
        @Default.String("")
        String getDatabasePort();

        void setDatabasePort(String value);

        @TemplateParameter.Text(
                order = 10,
                description = "Database User to connect with.",
                helpText =
                        "The SQL user with all required permissions to write to all tables in replication.")
        String getDatabaseUser();

        void setDatabaseUser(String value);

        @TemplateParameter.Password(
                order = 11,
                description = "Secret Manager KEY Target DB",
                helpText = "The password for the SQL user.")
        String getDatabasePassword();

        void setDatabasePassword(String value);

//        @TemplateParameter.Text(
//                order = 12,
//                groupName = "Target",
//                description = "Database target max connection",
//                helpText = "10")
//        @Default.Integer(10)
//        Integer getMaxConnection();
//
//        void setMaxConnection(String value);

        @TemplateParameter.Text(
                order = 13,
                groupName = "Target",
                optional = true,
                description = "SQL Database Name.",
                helpText = "The name of the SQL database to connect to. The default value is `postgres`.")
        @Default.String("postgres")
        String getDatabaseName();

        void setDatabaseName(String value);

        @TemplateParameter.Text(
                order = 14,
                optional = true,
                description = "A map of key/values used to dictate schema name changes",
                helpText =
                        "A map of key/values used to dictate schema name changes (ie."
                                + " old_name:new_name,CaseError:case_error)")
        @Default.String("")
        String getSchemaMap();

        void setSchemaMap(String value);

        @TemplateParameter.Text(
                order = 15,
                groupName = "Target",
                optional = true,
                description = "Custom connection string.",
                helpText =
                        "Optional connection string which will be used instead of the default database string.")
        @Default.String("")
        String getCustomConnectionString();

        void setCustomConnectionString(String value);

        @TemplateParameter.Text(
                order = 16,
                optional = true,
                description = "Custom connection string.",
                helpText =
                        "Database Type (postgres or mysql)")
        @Default.String("postgres")
        String getSourceDatabaseType();

        void setSourceDatabaseType(String value);

        @TemplateParameter.Text(
                order = 17,
                optional = true,
                description = "Custom connection string.",
                helpText =
                        "Database Host")
        String getSourceDatabaseHost();

        void setSourceDatabaseHost(String value);

        @TemplateParameter.Text(
                order = 18,
                optional = true,
                description = "Custom connection string.",
                helpText =
                        "Database Port")
        @Default.String("")
        String getSourceDatabasePort();

        void setSourceDatabasePort(String value);

        @TemplateParameter.Text(
                order = 19,
                optional = true,
                description = "Custom connection string.",
                helpText =
                        "Database User")
        String getSourceDatabaseUser();

        void setSourceDatabaseUser(String value);

        @TemplateParameter.Text(
                order = 20,
                optional = true,
                description = "Sercret Name Source DB",
                helpText =
                        "Sercret Name Source DB")
        String getSourceDatabasePassword();

        void setSourceDatabasePassword(String value);

        @TemplateParameter.Text(
                order = 21,
                optional = true,
                description = "Custom connection string.",
                helpText =
                        "Database Name")
        String getSourceDatabaseName();

        void setSourceDatabaseName(String value);

    }

    /**
     * Main entry point for executing the pipeline.
     *
     * @param args The command-line arguments to the pipeline.
     */
    public static void main(String[] args) {
        UncaughtExceptionLogger.register();

        LOG.info("Starting Datastream to SQL");

        Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

        options.setStreaming(true);
        run(options);
    }

    /**
     * Build the DataSourceConfiguration for the target SQL database. Using the pipeline options,
     * determine the database type and create the correct jdbc connection for the requested DB.
     *
     * @param options The execution parameters to the pipeline.
     */
    public static CdcJdbcIO.DataSourceConfiguration getDataSourceConfiguration(Options options) {
        String jdbcDriverName;
        String jdbcDriverConnectionString;

        switch (options.getDatabaseType()) {
            case "postgres":
                jdbcDriverName = "org.postgresql.Driver";
                jdbcDriverConnectionString =
                        String.format(
                                "jdbc:postgresql://%s:%s/%s",
                                options.getDatabaseHost(), options.getDatabasePort(), options.getDatabaseName());
                break;
            case "mysql":
                jdbcDriverName = "com.mysql.cj.jdbc.Driver";
                jdbcDriverConnectionString =
                        String.format(
                                "jdbc:mysql://%s:%s/%s",
                                options.getDatabaseHost(), options.getDatabasePort(), options.getDatabaseName());
                break;
            case "oracle":
                jdbcDriverName = "oracle.jdbc.driver.OracleDriver";
                jdbcDriverConnectionString =
                        String.format(
                                "jdbc:oracle:thin:@%s",
                                options.getDatabaseHost());
                break;
            default:
                throw new IllegalArgumentException(
                        String.format("Database Type %s is not supported.", options.getDatabaseType()));
        }
        if (!options.getCustomConnectionString().isEmpty()) {
            jdbcDriverConnectionString = options.getCustomConnectionString();
        }
        try {
            LOG.info("Connection TARGET string: {}", jdbcDriverConnectionString);
            LOG.info("Driver TARGET string: {}", jdbcDriverName);
            LOG.info("USER TARGET string: {}", options.getDatabaseUser());
            LOG.info("PWD TARGET string: {}", options.getDatabasePassword());

            String secretValue = getSecretValue(options.getDatabasePassword());

            CdcJdbcIO.DataSourceConfiguration dataSourceConfiguration =
                    CdcJdbcIO.DataSourceConfiguration.create(jdbcDriverName, jdbcDriverConnectionString)
                            .withUsername(options.getDatabaseUser())
                            .withPassword(secretValue)
                            .withMaxIdleConnections(0);

            return dataSourceConfiguration;
        } catch (Exception e) {
            LOG.error("Error in creating source data source configuration");
            LOG.error("Exception message {}", e.getMessage());
            LOG.error("Exception Localized message {}", e.getLocalizedMessage());
            throw e;
        }

    }

    public static CdcJdbcIO.DataSourceConfiguration getDataSourceSourceConfiguration(Options options) {
        String jdbcDriverName;
        String jdbcDriverConnectionString;

        jdbcDriverName = "org.postgresql.Driver";
        jdbcDriverConnectionString =
                String.format(
                        "jdbc:postgresql://%s:%s/%s",
                        options.getSourceDatabaseHost(), options.getSourceDatabasePort(), options.getSourceDatabaseName());

        try {

            LOG.info("Connection SOURCE string: {}", jdbcDriverConnectionString);
            LOG.info("Driver SOURCE string: {}", jdbcDriverName);
            LOG.info("USER SOURCE string: {}", options.getSourceDatabaseUser());
            LOG.info("PWD SOURCE string: {}", options.getSourceDatabasePassword());
            String secretValue = getSecretValue(options.getSourceDatabasePassword());
            CdcJdbcIO.DataSourceConfiguration dataSourceConfiguration =
                    CdcJdbcIO.DataSourceConfiguration.create(jdbcDriverName, jdbcDriverConnectionString)
                            .withUsername(options.getSourceDatabaseUser())
                            .withPassword(secretValue)
                            .withMaxIdleConnections(0);
            return dataSourceConfiguration;
        } catch (Exception e) {
            LOG.error("Error in creating source data source configuration");
            LOG.error("Exception message {}", e.getMessage());
            LOG.error("Exception Localized message {}", e.getLocalizedMessage());
            throw e;
        }

    }

    /**
     * Validate the options supplied match expected values. We will also validate that connectivity is
     * working correctly for the target SQL database.
     *
     * @param options                 The execution parameters to the pipeline.
     * @param dataSourceConfiguration The JDBC datasource configuration.
     */
    public static void validateOptions(
            Options options, CdcJdbcIO.DataSourceConfiguration dataSourceConfiguration) {
        try {
            if (options.getDatabaseHost() != null) {
                dataSourceConfiguration.buildDatasource().getConnection().close();
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Parse the SchemaMap config which allows key:value pairs of column naming configs.
     */
    public static Map<String, String> parseSchemaMap(String schemaMapString) {
        if (schemaMapString == null || schemaMapString.equals("")) {
            return new HashMap<>();
        }

        return Splitter.on(",").withKeyValueSeparator(":").split(schemaMapString);
    }

    /**
     * Runs the pipeline with the supplied options.
     *
     * @param options The execution parameters to the pipeline.
     * @return The result of the pipeline execution.
     */
    public static PipelineResult run(Options options) {
        /*
         * Stages:
         *   1) Ingest and Normalize Data to FailsafeElement with JSON Strings
         *   2) Write JSON Strings to SQL DML Objects
         *   3) Filter stale rows using stateful PK transform
         *   4) Write DML statements to SQL Database via jdbc
         */

        Pipeline pipeline = Pipeline.create(options);
        LOG.info("Pipeline creata");

        CdcJdbcIO.DataSourceConfiguration dataSourceConfiguration = getDataSourceConfiguration(options);
        LOG.info("Datasource ottenuto");
        CdcJdbcIO.DataSourceConfiguration sourceDataSourceConfiguration = getDataSourceSourceConfiguration(options);
        LOG.info("DataSource postgres ottenuto");
        validateOptions(options, dataSourceConfiguration);
        LOG.info("Options validate");
        Map<String, String> schemaMap = parseSchemaMap(options.getSchemaMap());
        LOG.info("schemaMap creato");
        /*
         * Stage 1: Ingest and Normalize Data to FailsafeElement with JSON Strings
         *   a) Read DataStream data from GCS into JSON String FailsafeElements (datastreamJsonRecords)
         */

        DataStreamIO dataStreamIO = new DataStreamIO(
                options.getStreamName(),
                options.getInputFilePattern(),
                options.getInputFileFormat(),
                options.getGcsPubSubSubscription(),
                options.getRfcStartDateTime())
                .withLowercaseSourceColumns()
                .withRenameColumnValue("_metadata_row_id", "rowid")
                .withHashRowId();

        LOG.info("Datastream IO creato");

        PCollection<FailsafeElement<String, String>> datastreamJsonRecords = pipeline.apply(dataStreamIO);
        LOG.info("Datastream IO applicato alla pipeline");
        /*
         * Stage 2: Write JSON Strings to SQL Insert Strings
         *   a) Convert JSON String FailsafeElements to TableRow's (tableRowRecords)
         * Stage 3) Filter stale rows using stateful PK transform
         */
        PCollection<KV<String, DmlInfo>> dmlStatements =
                datastreamJsonRecords
                        .apply("Format to DML", CreateDml.of(sourceDataSourceConfiguration, dataSourceConfiguration).withSchemaMap(schemaMap))
                        .apply("DML Stateful Processing", ProcessDml.statefulOrderByPK());

        LOG.info("dmlStatements applicato alla pipeline");

        // Raggruppa per nome tabella
        PCollection<KV<String, Iterable<DmlInfo>>> dmlStatementsByTable =
                dmlStatements
                        .apply("Group DML by Table",
                                MapElements.into(TypeDescriptors.kvs(TypeDescriptors.strings(), TypeDescriptor.of(DmlInfo.class)))
                                        .via(kv -> KV.of(kv.getValue().getTableName(), kv.getValue())))
                        .apply(GroupByKey.create());

        /*
         * Stage 4: Write Inserts to CloudSQL in batch per tabella
         */
        CdcJdbcIO.Write<KV<String, Iterable<DmlInfo>>> kvWrite = CdcJdbcIO.<KV<String, Iterable<DmlInfo>>>write()
                .withDataSourceConfiguration(dataSourceConfiguration)
                .withBatchSize(100)
                .withStatementBatchFormatter((KV<String, Iterable<DmlInfo>> element) -> {
                    List<String> statements = new ArrayList<>();
                    for (DmlInfo dmlInfo : element.getValue()) {
                        statements.add(dmlInfo.getDmlSql());
                    }
                    LOG.info("Batch SQL writing for table {}: {} statements", element.getKey(), statements.size());
                    return statements;
                });

        LOG.info("Write batch Inserts to CloudSQL creato");
        dmlStatementsByTable.apply("Write batch to SQL", kvWrite);
        LOG.info("Write batch Inserts to CloudSQL applicato alla pipeline");
        PipelineResult run = pipeline.run();
        LOG.info("pipeline.run");
        // Execute the pipeline and return the result.
        return run;
    }

    private static String getSecretValue(String secretName) {
        LOG.info("Getting secret value for secret name {}", secretName);
        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            AccessSecretVersionResponse response = client.accessSecretVersion(secretName);
            String stringUtf8 = response.getPayload().getData().toStringUtf8();
            LOG.info("Secret value obtained");
            return stringUtf8;
        } catch (Exception e) {
            LOG.error("Unable to read secret value message {}", e.getMessage());
            LOG.error("Unable to read secret value localized message {}", e.getLocalizedMessage());
            throw new RuntimeException("Unable to read secret value", e);
        }
    }

}
