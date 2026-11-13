variable "name" {
  description = "Name prefix. Bucket names are <name>-<purpose>-<account id>."
  type        = string
}

variable "cloudfront_distribution_arn" {
  description = "Distribution allowed to read frontend and delivery and to decrypt with the delivery key."
  type        = string
}

variable "kms_deletion_window_days" {
  description = "Waiting period before a scheduled KMS key deletion."
  type        = number
}

variable "abort_incomplete_multipart_days" {
  description = "Days after which incomplete multipart uploads are aborted."
  type        = number
}

variable "noncurrent_version_days" {
  description = "Days a noncurrent object version is kept in versioned buckets."
  type        = number
}
