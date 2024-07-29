/*
 * Copyright (C) 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.v2.utils;

import com.google.cloud.teleport.v2.datastream.io.CdcJdbcIO.DataSourceConfiguration;
import com.google.cloud.teleport.v2.datastream.values.DatastreamRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A set of Database Migration utilities to convert JSON data to DML.
 */
public class DatastreamToOracleDML extends DatastreamToDML {

    private static final Logger LOG = LoggerFactory.getLogger(DatastreamToOracleDML.class);

    private DatastreamToOracleDML(DataSourceConfiguration sourceConfiguration) {
        super(sourceConfiguration);
    }

    public static DatastreamToOracleDML of(DataSourceConfiguration sourceConfiguration) {
        return new DatastreamToOracleDML(sourceConfiguration);
    }

    @Override
    public String getDefaultQuoteCharacter() {
        //return "`";
        return "";
    }

    @Override
    public String getDeleteDmlStatement() {
        return "DELETE FROM {%$quoted_table_name$%} WHERE {%$primary_key_kv_sql$%}";
    }

    @Override
    public String getUpsertDmlStatement() {
        return "MERGE INTO {%$quoted_table_name$%} "
                + "USING dual ON ({%$primary_key_kv_sql$%}) "
                + "WHEN MATCHED THEN UPDATE SET "
                + "{%$column_kv_sql$%} "
                + "WHEN NOT MATCHED THEN INSERT "
                + "({%$quoted_column_names$%}) VALUES ({%$column_value_sql$%}) ";
    }

    @Override
    public String getInsertDmlStatement() {
        return "INSERT INTO {%$quoted_table_name$%} "
                + "({%$quoted_column_names$%}) VALUES ({%$column_value_sql$%})";
    }

    @Override
    public String getTargetCatalogName(DatastreamRow row) {
        return "";
    }

    @Override
    public String getTargetSchemaName(DatastreamRow row) {
        return "";
    }

    @Override
    public String getTargetTableName(DatastreamRow row) {
        String tableName = row.getTableName();
        return cleanTableName(tableName);
    }

    @Override
    public String cleanDataTypeValueSql(String columnValue, String columnName, Map<String, String> tableSchema) {
        String dataType = tableSchema.get(columnName);
        if (dataType == null) {
            return columnValue;
        }
        if (columnValue == null) {
            return null;
        }
        switch (dataType.toLowerCase()) {
            case "bool":
                return Boolean.parseBoolean(columnValue) ? "1" : "0";
            case "text":
            case "jsonb":
            case "varchar":
                if (columnValue.length() > 4000) {
                    String value = "";
                    //Il valore arriva tra apici, sostituiamo i singoli apici del valore della colonna per evitare errori di escape in sql
                    String withoutApici = columnValue.substring(1, columnValue.length() - 1).replace("'", "");
                    //lo divido in chunks da 3500 caratteri
                    List<String> chunks = new ArrayList<>();
                    for (int i = 0; i < withoutApici.length(); i += 3500) {
                        chunks.add(withoutApici.substring(i, Math.min(withoutApici.length(), i + 3500)));
                    }
                    //LOG.info("VALUE TO clean: {}", withoutApici);
                    for (String element : chunks) {
                        if (StringUtils.isEmpty(value)) {
                            value = StringUtils.join("TO_CLOB('", element, "')");
                        } else {
                            value = StringUtils.join(value, " || TO_CLOB('", element, "')");
                        }
                    }
                    //LOG.info("Cleaned VALUE: {}", value);
                    return value;
                } else {
                    return columnValue;
                }
            case "timestamp":
                final String replace = StringUtils.replace(columnValue, "T", " ");
                final String replace1 = StringUtils.replace(replace, "Z", " ");
                return StringUtils.join("TO_TIMESTAMP(", replace1, ", 'YYYY-MM-DD HH24:MI:SS.FF6')");
            case "date":
                return StringUtils.join("TO_DATE(", columnValue, ", 'YYYY-MM-DD HH24:MI:SS')");
            default:
                return columnValue;
        }
    }
}
