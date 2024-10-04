job_name                 = "test-job"
project                  = "project-name"
host_project             = "host-project"
region                   = "us-central1"               # Or your desired region
working_directory_bucket = "bucket-name"               # example "test-bucket"
working_directory_prefix = "path/to/working/directory" # should not start or end with a '/'
jdbc_driver_jars         = "gs://path/to/driver/jars"
jdbc_driver_class_name   = "com.mysql.jdbc.driver"
source_config_url        = "jdbc:mysql://127.4.5.30:3306/my-db"
username                 = "root"
password                 = "abc"
num_partitions           = 4000
max_connections          = 320
instance_id              = "my-spanner-instance"
database_id              = "my-spanner-database"
spanner_project_id       = "my-spanner-project"
spanner_host             = "https://batch-spanner.googleapis.com"
local_session_file_path  = "/local/path/to/smt/session/file"

network               = "network-name"
subnetwork            = "subnetwork-name"
service_account_email = "your-service-account-email@your-project-id.iam.gserviceaccount.com"
launcher_machine_type = "n1-highmem-32" # Recommend using larger launcher VMs
machine_type          = "n1-highmem-4"
max_workers           = 50
ip_configuration      = "WORKER_IP_PRIVATE"
num_workers           = 1
default_log_level     = "INFO"
