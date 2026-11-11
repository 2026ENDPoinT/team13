variable "name" {
  description = "Name prefix."
  type        = string
}

variable "domain_name" {
  description = "Service domain served by the distribution."
  type        = string
}

variable "route53_zone_id" {
  description = "Hosted zone that holds domain_name."
  type        = string
}

variable "price_class" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "app_instance_arn" {
  type = string
}

variable "app_private_dns" {
  type = string
}

variable "app_security_group_id" {
  type = string
}

variable "app_port" {
  type = number
}

variable "app_origin_read_timeout" {
  description = "Seconds CloudFront waits for the app to respond."
  type        = number
}

variable "frontend_bucket_regional_domain_name" {
  type = string
}

variable "delivery_bucket_regional_domain_name" {
  type = string
}

variable "signing_public_keys" {
  description = "PEM public keys of the signed cookie key group, by key name."
  type        = map(string)
}

variable "frontend_routing" {
  description = "How extensionless static-site paths map to objects: spa or directory."
  type        = string
}

variable "frontend_content_security_policy" {
  description = "Content-Security-Policy for the static shell. null sends none."
  type        = string
}

variable "hsts_max_age_seconds" {
  type = number
}

variable "waf_common_rules_to_count" {
  description = "Rules of AWSManagedRulesCommonRuleSet switched from Block to Count."
  type        = list(string)
}

variable "waf_body_size_limit_bytes" {
  description = "Largest request body accepted outside waf_large_body_path_prefixes."
  type        = number
}

variable "waf_large_body_path_prefixes" {
  description = "URI path prefixes exempt from the body size rule; the app enforces their limit."
  type        = list(string)
}
