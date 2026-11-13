variable "name" {
  description = "Name prefix."
  type        = string
}

variable "alarm_emails" {
  description = "Email addresses subscribed to the alarm topics. Each must confirm the subscription."
  type        = list(string)
}

variable "app_instance_id" {
  type = string
}

variable "nat_autoscaling_group_name" {
  type = string
}

variable "db_instance_identifier" {
  type = string
}

variable "distribution_id" {
  type = string
}

variable "rds_cpu_percent" {
  type = number
}

variable "rds_free_storage_bytes" {
  type = number
}

variable "rds_max_connections" {
  type = number
}

variable "cloudfront_5xx_percent" {
  type = number
}
