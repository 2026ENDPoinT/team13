variable "name" {
  description = "RDS identifier and name prefix."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "Private subnets in at least two availability zones."
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Only this security group may connect on 3306."
  type        = string
}

variable "engine_version" {
  description = "MySQL engine version. A major.minor value lets RDS apply minor upgrades."
  type        = string
}

variable "instance_class" {
  type = string
}

variable "database_name" {
  type = string
}

variable "master_username" {
  type = string
}

variable "allocated_storage" {
  description = "Initial storage in GiB."
  type        = number
}

variable "max_allocated_storage" {
  description = "Storage autoscaling ceiling in GiB."
  type        = number
}

variable "backup_retention_days" {
  type = number
}

variable "backup_window" {
  description = "Daily backup window in UTC (hh24:mi-hh24:mi)."
  type        = string
}

variable "maintenance_window" {
  description = "Weekly maintenance window in UTC (ddd:hh24:mi-ddd:hh24:mi)."
  type        = string
}

variable "slow_query_seconds" {
  type = number
}

variable "log_retention_days" {
  description = "Retention of the exported RDS error and slow query logs."
  type        = number
}
