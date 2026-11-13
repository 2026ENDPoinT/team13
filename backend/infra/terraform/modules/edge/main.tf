locals {
  base_url = "https://${var.domain_name}"

  # Requests to these paths may carry bodies larger than the WAF limit; the app answers 413 itself.
  large_body_path_regex = join("|", flatten([for p in var.waf_large_body_path_prefixes : ["^${p}$", "^${p}/"]]))

  # Ordered behaviors. Anything else (including /actuator) falls through to the static site.
  delivery_paths = ["/vod/*", "/doc/*"]
  api_paths      = ["/api/*", "/ws", "/ws/*"]

  certificate_validation = { for o in aws_acm_certificate.this.domain_validation_options : o.domain_name => o }
}

# ---------------------------------------------------------------------------
# TLS certificate (CloudFront requires us-east-1) and DNS
# ---------------------------------------------------------------------------

resource "aws_acm_certificate" "this" {
  region = "us-east-1"

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# Keyed by the configured domain so the plan does not depend on apply-time values.
resource "aws_route53_record" "certificate_validation" {
  for_each = toset([var.domain_name])

  zone_id         = var.route53_zone_id
  name            = local.certificate_validation[each.key].resource_record_name
  type            = local.certificate_validation[each.key].resource_record_type
  ttl             = 300
  records         = [local.certificate_validation[each.key].resource_record_value]
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  region = "us-east-1"

  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.certificate_validation : r.fqdn]
}

resource "aws_route53_record" "alias" {
  for_each = toset(["A", "AAAA"])

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = each.value

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}

# ---------------------------------------------------------------------------
# Signed cookies for /vod/* and /doc/*. Only public keys are managed here;
# the private key goes to Secrets Manager outside Terraform.
# ---------------------------------------------------------------------------

resource "aws_cloudfront_public_key" "signing" {
  for_each = var.signing_public_keys

  name        = "${var.name}-${each.key}"
  comment     = "Signed cookie key ${each.key}"
  encoded_key = each.value
}

resource "aws_cloudfront_key_group" "signing" {
  name    = "${var.name}-delivery"
  comment = "Signers of /vod/* and /doc/* cookies"
  items   = [for k in aws_cloudfront_public_key.signing : k.id]
}

# ---------------------------------------------------------------------------
# Origins
# ---------------------------------------------------------------------------

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${var.name}-s3"
  description                       = "CloudFront reads frontend and delivery"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# CloudFront reaches the private instance through ENIs it places in the VPC.
# The CloudFront to instance hop is HTTP inside the VPC (ADR-0007, last item).
resource "aws_cloudfront_vpc_origin" "app" {
  vpc_origin_endpoint_config {
    name                   = "${var.name}-app"
    arn                    = var.app_instance_arn
    http_port              = var.app_port
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# Service-managed security group that CloudFront creates with the first VPC origin in the VPC.
data "aws_security_group" "cloudfront_vpc_origin" {
  vpc_id = var.vpc_id

  filter {
    name   = "group-name"
    values = ["CloudFront-VPCOrigins-Service-SG*"]
  }

  depends_on = [aws_cloudfront_vpc_origin.app]
}

resource "aws_vpc_security_group_ingress_rule" "app_from_cloudfront" {
  security_group_id            = var.app_security_group_id
  description                  = "App port from CloudFront VPC origin"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port
  to_port                      = var.app_port
  referenced_security_group_id = data.aws_security_group.cloudfront_vpc_origin.id
}

# ---------------------------------------------------------------------------
# Policies
# ---------------------------------------------------------------------------

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Everything the app reads from the viewer: Host (own-origin checks), Origin, Cookie,
# X-XSRF-TOKEN, Sec-Fetch-*, Sec-WebSocket-*, Idempotency-Key, all query strings.
# CloudFront always appends the viewer address to X-Forwarded-For, so only its rightmost entry is
# CloudFront's; the app reads it from the right only when the peer is the VPC origin (app subnet).
resource "aws_cloudfront_origin_request_policy" "api" {
  name    = "${var.name}-api"
  comment = "All viewer headers, cookies and query strings plus the viewer protocol"

  cookies_config {
    cookie_behavior = "all"
  }

  headers_config {
    header_behavior = "allViewerAndWhitelistCloudFront"
    headers {
      items = ["CloudFront-Forwarded-Proto"]
    }
  }

  query_strings_config {
    query_string_behavior = "all"
  }
}

resource "aws_cloudfront_response_headers_policy" "static_site" {
  name    = "${var.name}-static-site"
  comment = "Security headers for the static shell"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = var.hsts_max_age_seconds
      include_subdomains         = false
      preload                    = false
      override                   = true
    }

    content_type_options {
      override = true
    }

    frame_options {
      frame_option = "DENY"
      override     = true
    }

    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }

    dynamic "content_security_policy" {
      for_each = var.frontend_content_security_policy == null ? [] : [var.frontend_content_security_policy]

      content {
        content_security_policy = content_security_policy.value
        override                = true
      }
    }
  }
}

# API and delivery responses keep the app's own headers; only HSTS and nosniff are enforced.
resource "aws_cloudfront_response_headers_policy" "transport" {
  name    = "${var.name}-transport"
  comment = "HSTS and nosniff for API and delivery"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = var.hsts_max_age_seconds
      include_subdomains         = false
      preload                    = false
      override                   = true
    }

    content_type_options {
      override = true
    }
  }
}

resource "aws_cloudfront_function" "frontend_routing" {
  name    = "${var.name}-frontend-routing"
  runtime = "cloudfront-js-2.0"
  comment = "Map extensionless paths of the static shell to index.html"
  publish = true
  code    = templatefile("${path.module}/templates/frontend-routing.js", { mode = var.frontend_routing })
}

# ---------------------------------------------------------------------------
# AWS WAF (architecture.md 3.3). No IP-based rate rule: campus users share NAT addresses.
# ---------------------------------------------------------------------------

resource "aws_wafv2_web_acl" "this" {
  region = "us-east-1"

  name        = var.name
  description = "Managed Core, SQL database and Known bad inputs rules"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  association_config {
    request_body {
      cloudfront {
        default_size_inspection_limit = "KB_16"
      }
    }
  }

  rule {
    name     = "aws-common"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"

        # SizeRestrictions_BODY blocks bodies over 8 KB, which includes uploads, posts and submissions.
        dynamic "rule_action_override" {
          for_each = toset(var.waf_common_rules_to_count)

          content {
            name = rule_action_override.value
            action_to_use {
              count {}
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-aws-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-sqli"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-aws-sqli"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-aws-known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  # Path-scoped replacement for SizeRestrictions_BODY: outside the large-body paths, bodies over
  # the limit (or over the inspection limit) are rejected with 413.
  rule {
    name     = "body-size-outside-large-body-paths"
    priority = 40

    action {
      block {
        custom_response {
          response_code = 413
        }
      }
    }

    statement {
      and_statement {
        statement {
          size_constraint_statement {
            comparison_operator = "GT"
            size                = var.waf_body_size_limit_bytes

            field_to_match {
              body {
                oversize_handling = "MATCH"
              }
            }

            text_transformation {
              priority = 0
              type     = "NONE"
            }
          }
        }

        statement {
          not_statement {
            statement {
              regex_match_statement {
                regex_string = local.large_body_path_regex

                field_to_match {
                  uri_path {}
                }

                text_transformation {
                  priority = 0
                  type     = "NONE"
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-body-size"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = var.name
    sampled_requests_enabled   = true
  }
}

# ---------------------------------------------------------------------------
# Distribution
# ---------------------------------------------------------------------------

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  comment             = var.name
  aliases             = [var.domain_name]
  default_root_object = "index.html"
  http_version        = "http2and3"
  is_ipv6_enabled     = true
  price_class         = var.price_class
  web_acl_id          = aws_wafv2_web_acl.this.arn

  origin {
    origin_id                = "frontend"
    domain_name              = var.frontend_bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = "delivery"
    domain_name              = var.delivery_bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id   = "app"
    domain_name = var.app_private_dns

    vpc_origin_config {
      vpc_origin_id            = aws_cloudfront_vpc_origin.app.id
      origin_keepalive_timeout = 5
      origin_read_timeout      = var.app_origin_read_timeout
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.api_paths

    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = "app"
      viewer_protocol_policy     = "redirect-to-https"
      allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods             = ["GET", "HEAD"]
      compress                   = false
      cache_policy_id            = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id   = aws_cloudfront_origin_request_policy.api.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.transport.id
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.delivery_paths

    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = "delivery"
      viewer_protocol_policy     = "redirect-to-https"
      allowed_methods            = ["GET", "HEAD"]
      cached_methods             = ["GET", "HEAD"]
      compress                   = true
      cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.transport.id
      trusted_key_groups         = [aws_cloudfront_key_group.signing.id]
    }
  }

  default_cache_behavior {
    target_origin_id           = "frontend"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.static_site.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.frontend_routing.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.this.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
