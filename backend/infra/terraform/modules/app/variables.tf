variable "name" {
  description = "Name prefix."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "Private subnet of the app instance."
  type        = string
}

variable "s3_prefix_list_id" {
  description = "Prefix list of the S3 gateway endpoint."
  type        = string
}

variable "database_security_group_id" {
  type = string
}

variable "egress_tcp_ports" {
  description = "TCP ports the instance may reach through fck-nat."
  type        = list(number)
}

variable "instance_type" {
  type = string
}

variable "root_volume_size" {
  description = "Root volume size in GiB."
  type        = number
}

variable "termination_protection" {
  type = bool
}

variable "bucket_arns" {
  description = "Buckets the app reads and writes (content, delivery)."
  type        = map(string)
}

variable "kms_key_arns" {
  description = "Customer managed keys of those buckets."
  type        = map(string)
}

variable "ecr_repository_arn" {
  type = string
}

variable "log_retention_days" {
  type = number
}

variable "secret_recovery_window_days" {
  type = number
}

variable "docker_compose_version" {
  description = "docker compose plugin release tag."
  type        = string
}

variable "docker_compose_sha256" {
  description = "SHA-256 of the docker compose plugin binary, by architecture (x86_64, aarch64)."
  type        = map(string)
}
