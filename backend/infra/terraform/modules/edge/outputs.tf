output "base_url" {
  value = local.base_url
}

output "distribution_id" {
  value = aws_cloudfront_distribution.this.id
}

output "distribution_arn" {
  value = aws_cloudfront_distribution.this.arn
}

output "distribution_domain_name" {
  value = aws_cloudfront_distribution.this.domain_name
}

output "signing_public_key_ids" {
  description = "CloudFront public key IDs by key name (ASTRA_CLOUDFRONT_KEY_PAIR_ID)."
  value       = { for k, key in aws_cloudfront_public_key.signing : k => key.id }
}

output "web_acl_name" {
  value = aws_wafv2_web_acl.this.name
}
