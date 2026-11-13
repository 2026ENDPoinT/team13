output "bucket_names" {
  description = "Bucket names by purpose (frontend, content, delivery)."
  value       = { for k, b in aws_s3_bucket.this : k => b.bucket }
}

output "bucket_arns" {
  value = { for k, b in aws_s3_bucket.this : k => b.arn }
}

output "bucket_regional_domain_names" {
  value = { for k, b in aws_s3_bucket.this : k => b.bucket_regional_domain_name }
}

output "kms_key_arns" {
  description = "Customer managed key ARNs for content and delivery."
  value       = { for k, key in aws_kms_key.this : k => key.arn }
}
