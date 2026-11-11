# ---------------------------------------------------------------------------
# Account and naming
# ---------------------------------------------------------------------------

variable "aws_account_id" {
  description = "Target AWS account. The provider refuses to run against any other account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be 12 digits."
  }
}

variable "aws_region" {
  description = "Primary region (architecture.md 1)."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "The pre-adoption topology runs in ap-northeast-2 (architecture.md 1)."
  }
}

variable "name" {
  description = "Name prefix of every resource."
  type        = string
  default     = "astra-pre"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}$", var.name))
    error_message = "name must be 3-20 characters of lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "environment" {
  description = "Value of the Environment tag."
  type        = string
  default     = "pre-adoption"
}

# ---------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "IPv4 CIDR of the VPC."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be an IPv4 CIDR."
  }
}

variable "availability_zones" {
  description = "Availability zones. The first hosts fck-nat and the app instance; RDS needs two."
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]

  validation {
    condition     = length(var.availability_zones) >= 2 && length(distinct(var.availability_zones)) == length(var.availability_zones)
    error_message = "At least two distinct availability zones are required (RDS subnet group)."
  }
}

variable "subnet_newbits" {
  description = "Bits added to the VPC prefix per subnet. 8 on a /16 gives /24 subnets."
  type        = number
  default     = 8

  validation {
    condition     = var.subnet_newbits >= 2 && var.subnet_newbits <= 12
    error_message = "subnet_newbits must be between 2 and 12."
  }
}

variable "egress_tcp_ports" {
  description = "TCP ports the app instance may reach through fck-nat (AWS APIs, school IdP, LLM provider)."
  type        = list(number)
  default     = [443]

  validation {
    condition     = length(var.egress_tcp_ports) > 0 && alltrue([for p in var.egress_tcp_ports : p >= 1 && p <= 65535 && floor(p) == p])
    error_message = "egress_tcp_ports must list TCP ports between 1 and 65535."
  }
}

variable "nat_instance_type" {
  description = "fck-nat instance type (architecture.md 3.1: t4g.nano)."
  type        = string
  default     = "t4g.nano"
}

variable "fck_nat_ami_owner" {
  description = "AWS account that publishes the fck-nat AMIs (https://fck-nat.dev)."
  type        = string
  default     = "568608671756"

  validation {
    condition     = can(regex("^[0-9]{12}$", var.fck_nat_ami_owner))
    error_message = "fck_nat_ami_owner must be 12 digits."
  }
}

# ---------------------------------------------------------------------------
# App instance
# ---------------------------------------------------------------------------

variable "app_instance_type" {
  description = "App instance type. The ECR image must match its architecture. With scan_uploads it needs at least 8 GiB of memory (clamd)."
  type        = string
  default     = "t3.large"
}

variable "scan_uploads" {
  description = "Start the ClamAV scan profile and require it (COMPOSE_PROFILES=scan, ASTRA_SCAN_REQUIRED=true; ADR-0018). Keep true for every deployment that takes school material; false makes uploads clean after the format check only."
  type        = bool
  default     = true
}

variable "app_root_volume_size" {
  description = "App root volume in GiB (images, temporary work space)."
  type        = number
  default     = 30

  validation {
    condition     = var.app_root_volume_size >= 8 && var.app_root_volume_size <= 500
    error_message = "app_root_volume_size must be between 8 and 500 GiB."
  }
}

variable "app_port" {
  description = "Host port the api container publishes; CloudFront VPC origin connects to it."
  type        = number
  default     = 8080

  validation {
    condition     = var.app_port >= 1024 && var.app_port <= 65535 && var.app_port != 8081
    error_message = "app_port must be an unprivileged port other than 8081 (management port)."
  }
}

variable "app_termination_protection" {
  description = "Protect the app instance from API termination."
  type        = bool
  default     = true
}

variable "app_origin_read_timeout" {
  description = "Seconds CloudFront waits for an app response. Above 60 needs a CloudFront quota increase."
  type        = number
  default     = 60

  validation {
    condition     = var.app_origin_read_timeout >= 1 && var.app_origin_read_timeout <= 180
    error_message = "app_origin_read_timeout must be between 1 and 180."
  }
}

variable "app_environment" {
  description = "Extra non-secret app settings written to /opt/astra/.env on each deploy, e.g. ASTRA_LLM_MODEL, ASTRA_OIDC_ISSUER, ASTRA_OIDC_CLIENT_ID. Terraform-derived keys, secret keys and the scanner switch (use scan_uploads) are rejected."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for k in keys(var.app_environment) : can(regex("^[A-Z][A-Z0-9_]*$", k))])
    error_message = "app_environment keys must be upper-case environment variable names."
  }

  validation {
    condition     = alltrue([for v in values(var.app_environment) : !strcontains(v, "'") && !strcontains(v, "\n")])
    error_message = "app_environment values must be single-line and must not contain a single quote."
  }

  validation {
    condition = length(setintersection(keys(var.app_environment), [
      "ASTRA_IMAGE", "ASTRA_CONVERTER_IMAGE", "ASTRA_API_BIND", "ASTRA_API_PORT", "DB_URL", "DB_USERNAME", "DB_PASSWORD",
      "ASTRA_STORAGE_CONTENT_BUCKET", "ASTRA_STORAGE_DELIVERY_BUCKET", "ASTRA_STORAGE_REGION",
      "ASTRA_STORAGE_ENDPOINT", "ASTRA_STORAGE_ACCESS_KEY", "ASTRA_STORAGE_SECRET_KEY",
      "ASTRA_CLOUDFRONT_BASE_URL", "ASTRA_CLOUDFRONT_KEY_PAIR_ID", "ASTRA_CLOUDFRONT_PRIVATE_KEY",
      "ASTRA_SESSION_ALLOWED_ORIGINS", "ASTRA_OIDC_REDIRECT_URI", "ASTRA_OIDC_CLIENT_SECRET",
      "ASTRA_LLM_API_KEY", "ASTRA_BREAK_GLASS_EXTERNAL_REF", "ASTRA_BREAK_GLASS_PASSWORD_HASH",
      "ASTRA_BREAK_GLASS_TOTP_SECRET", "ASTRA_DELIVERY_ORIGIN_ENABLED", "ASTRA_DELIVERY_ORIGIN_SIGNING_KEY",
      "ASTRA_SCAN_CLAMD_HOST", "ASTRA_SCAN_CLAMD_PORT", "ASTRA_SCAN_REQUIRED", "COMPOSE_PROFILES",
    ])) == 0
    error_message = "app_environment must not set Terraform-derived, secret, local-only, scanner switch or compose control keys."
  }
}

variable "docker_compose_version" {
  description = "docker compose plugin release installed on the app instance."
  type        = string
  default     = "v5.7.1"
}

variable "docker_compose_sha256" {
  description = "SHA-256 of docker-compose-linux-<arch> for docker_compose_version (from the release checksums.txt)."
  type        = map(string)
  default = {
    x86_64  = "af426722a8af5de812fcee30cc1c39f0639f5f723cbadefb25e5d4db054064cf"
    aarch64 = "f2f49f903240895fa764c0dca3a14548b754ad9426f6b609ccaf55ec5a3aa2a5"
  }

  validation {
    condition     = alltrue([for k in ["x86_64", "aarch64"] : can(regex("^[0-9a-f]{64}$", lookup(var.docker_compose_sha256, k, "")))])
    error_message = "docker_compose_sha256 needs x86_64 and aarch64 SHA-256 hex digests."
  }
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention of the app log group."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a value CloudWatch Logs accepts."
  }
}

variable "secret_recovery_window_days" {
  description = "Days a deleted secret can still be restored."
  type        = number
  default     = 30

  validation {
    condition     = var.secret_recovery_window_days >= 7 && var.secret_recovery_window_days <= 30
    error_message = "secret_recovery_window_days must be between 7 and 30."
  }
}

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

variable "db_instance_class" {
  type    = string
  default = "db.t4g.small"
}

variable "db_engine_version" {
  description = "MySQL version. 8.4 lets RDS apply minor upgrades within 8.4."
  type        = string
  default     = "8.4"

  validation {
    condition     = can(regex("^8\\.4(\\.[0-9]+)?$", var.db_engine_version))
    error_message = "db_engine_version must be MySQL 8.4 (architecture.md 1)."
  }
}

variable "db_name" {
  type    = string
  default = "astra"
}

variable "db_master_username" {
  description = "RDS master user. Its password is managed by RDS in Secrets Manager."
  type        = string
  default     = "astra_admin"
}

variable "db_allocated_storage" {
  type    = number
  default = 20

  validation {
    condition     = var.db_allocated_storage >= 20
    error_message = "gp3 storage starts at 20 GiB."
  }
}

variable "db_max_allocated_storage" {
  description = "Storage autoscaling ceiling in GiB."
  type        = number
  default     = 100
}

variable "db_backup_retention_days" {
  description = "Automated backup (PITR) retention."
  type        = number
  default     = 7

  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days must be between 1 and 35; 0 would disable PITR."
  }
}

variable "db_backup_window" {
  description = "UTC. Default 02:00-02:30 KST."
  type        = string
  default     = "17:00-17:30"
}

variable "db_maintenance_window" {
  description = "UTC. Default Monday 03:00-04:00 KST."
  type        = string
  default     = "sun:18:00-sun:19:00"
}

variable "db_slow_query_seconds" {
  type    = number
  default = 1
}

# ---------------------------------------------------------------------------
# Storage
# ---------------------------------------------------------------------------

variable "kms_deletion_window_days" {
  type    = number
  default = 30

  validation {
    condition     = var.kms_deletion_window_days >= 7 && var.kms_deletion_window_days <= 30
    error_message = "kms_deletion_window_days must be between 7 and 30."
  }
}

variable "abort_incomplete_multipart_days" {
  type    = number
  default = 7

  validation {
    condition     = var.abort_incomplete_multipart_days >= 1
    error_message = "abort_incomplete_multipart_days must be at least 1."
  }
}

variable "noncurrent_version_days" {
  description = "Days a replaced or deleted object version stays in frontend and content."
  type        = number
  default     = 30

  validation {
    condition     = var.noncurrent_version_days >= 1
    error_message = "noncurrent_version_days must be at least 1."
  }
}

# ---------------------------------------------------------------------------
# Edge
# ---------------------------------------------------------------------------

variable "domain_name" {
  description = "Single service domain (architecture.md 3.1), e.g. eclass.example.ac.kr."
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name must be a lower-case DNS name."
  }
}

variable "route53_zone_id" {
  description = "Public hosted zone that contains domain_name. Must be delegated before apply (ACM DNS validation)."
  type        = string

  validation {
    condition     = can(regex("^Z[A-Z0-9]+$", var.route53_zone_id))
    error_message = "route53_zone_id must be a Route 53 hosted zone ID."
  }
}

variable "cloudfront_price_class" {
  description = "PriceClass_100 excludes Korea; PriceClass_200 includes it."
  type        = string
  default     = "PriceClass_200"

  validation {
    condition     = contains(["PriceClass_200", "PriceClass_All"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class must include Korean edge locations (PriceClass_200 or PriceClass_All)."
  }
}

variable "cloudfront_signing_public_keys" {
  description = "PEM public keys for /vod/* and /doc/* signed cookies, by key name. Two entries during rotation."
  type        = map(string)

  validation {
    condition     = length(var.cloudfront_signing_public_keys) >= 1 && length(var.cloudfront_signing_public_keys) <= 5
    error_message = "A CloudFront key group holds 1 to 5 public keys."
  }

  validation {
    condition = alltrue([for k, v in var.cloudfront_signing_public_keys :
      can(regex("^[a-z0-9-]{1,40}$", k)) && startswith(trimspace(v), "-----BEGIN PUBLIC KEY-----") && !strcontains(v, "PRIVATE KEY")
    ])
    error_message = "Keys need short lower-case names and PEM public key values. Never pass a private key."
  }
}

variable "cloudfront_signing_active_key" {
  description = "Name in cloudfront_signing_public_keys whose ID the app signs with (ASTRA_CLOUDFRONT_KEY_PAIR_ID)."
  type        = string

  validation {
    condition     = contains(keys(var.cloudfront_signing_public_keys), var.cloudfront_signing_active_key)
    error_message = "cloudfront_signing_active_key must name an entry of cloudfront_signing_public_keys."
  }
}

variable "frontend_routing" {
  description = "spa: extensionless paths serve /index.html. directory: they serve <path>/index.html."
  type        = string
  default     = "spa"

  validation {
    condition     = contains(["spa", "directory"], var.frontend_routing)
    error_message = "frontend_routing must be spa or directory."
  }
}

variable "frontend_content_security_policy" {
  description = "Content-Security-Policy for the static shell. null sends none until the frontend fixes its sources."
  type        = string
  default     = null
}

variable "hsts_max_age_seconds" {
  type    = number
  default = 31536000
}

variable "waf_common_rules_to_count" {
  description = "AWSManagedRulesCommonRuleSet rules switched to Count (architecture.md 3.3)."
  type        = list(string)
  default     = ["SizeRestrictions_BODY"]

  validation {
    condition     = contains(var.waf_common_rules_to_count, "SizeRestrictions_BODY")
    error_message = "SizeRestrictions_BODY must stay in Count; the path-scoped body size rule replaces it."
  }
}

variable "waf_body_size_limit_bytes" {
  description = "Largest body accepted outside waf_large_body_path_prefixes. CloudFront WAF inspects the first 16 KB."
  type        = number
  default     = 16384

  validation {
    condition     = var.waf_body_size_limit_bytes >= 1 && var.waf_body_size_limit_bytes <= 16384
    error_message = "waf_body_size_limit_bytes must be between 1 and 16384 (inspection limit)."
  }
}

variable "waf_large_body_path_prefixes" {
  description = "Path prefixes whose body size only the app limits (413), e.g. uploads."
  type        = list(string)
  default     = ["/api/uploads"]

  validation {
    condition     = length(var.waf_large_body_path_prefixes) > 0 && alltrue([for p in var.waf_large_body_path_prefixes : can(regex("^/[A-Za-z0-9/_-]*[A-Za-z0-9_-]$", p))])
    error_message = "Each prefix starts with / and contains only letters, digits, /, _ and -, without a trailing slash."
  }
}

# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------

variable "github_repository" {
  description = "Repository whose GitHub Actions may push images and deploy."
  type        = string
  default     = "STack-Corp/STack-eclass-Backend"

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must be owner/name."
  }
}

variable "github_refs" {
  description = "Refs allowed to assume the deploy role."
  type        = list(string)
  default     = ["refs/heads/main"]

  validation {
    condition     = length(var.github_refs) > 0 && alltrue([for r in var.github_refs : startswith(r, "refs/") && !strcontains(r, "*")])
    error_message = "github_refs must be exact refs (refs/heads/... or refs/tags/...), without wildcards."
  }
}

variable "github_environment" {
  description = "GitHub environment allowed to assume the deploy role instead of github_refs."
  type        = string
  default     = null
}

variable "create_github_oidc_provider" {
  description = "false when the account already has the token.actions.githubusercontent.com provider."
  type        = bool
  default     = true
}

variable "ecr_repository_name" {
  type    = string
  default = "astra-backend"
}

variable "ecr_keep_images" {
  type    = number
  default = 30

  validation {
    condition     = var.ecr_keep_images >= 2
    error_message = "Keep at least two images so the previous release can be redeployed."
  }
}

# ---------------------------------------------------------------------------
# Alarms
# ---------------------------------------------------------------------------

variable "alarm_emails" {
  description = "Email subscribers of the alarm topics (architecture.md 9 routes alarms to Slack later)."
  type        = list(string)
  default     = []
}

variable "rds_cpu_alarm_percent" {
  type    = number
  default = 80
}

variable "rds_free_storage_alarm_bytes" {
  type    = number
  default = 2147483648
}

variable "rds_connections_alarm" {
  type    = number
  default = 100
}

variable "cloudfront_5xx_alarm_percent" {
  type    = number
  default = 5
}
