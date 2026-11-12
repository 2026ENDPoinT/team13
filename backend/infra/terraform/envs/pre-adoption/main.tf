# Pre-adoption topology (docs/architecture.md 3.1, 3.3; ADR-0003, ADR-0007).
# CloudFront + WAF -> S3 frontend / S3 delivery / VPC origin -> one EC2 in a private subnet,
# RDS MySQL single-AZ, fck-nat for egress. The adoption phase (3.2: ALB, ECS, Valkey, Multi-AZ)
# is not modelled here.

module "network" {
  source = "../../modules/network"

  name                 = var.name
  vpc_cidr             = var.vpc_cidr
  availability_zones   = var.availability_zones
  subnet_newbits       = var.subnet_newbits
  egress_tcp_ports     = var.egress_tcp_ports
  nat_instance_type    = var.nat_instance_type
  nat_root_volume_size = 8
  fck_nat_ami_owner    = var.fck_nat_ami_owner
}

module "storage" {
  source = "../../modules/storage"

  name                            = var.name
  cloudfront_distribution_arn     = module.edge.distribution_arn
  kms_deletion_window_days        = var.kms_deletion_window_days
  abort_incomplete_multipart_days = var.abort_incomplete_multipart_days
  noncurrent_version_days         = var.noncurrent_version_days
}

module "database" {
  source = "../../modules/database"

  name                  = var.name
  vpc_id                = module.network.vpc_id
  subnet_ids            = module.network.private_subnet_ids
  app_security_group_id = module.app.security_group_id
  engine_version        = var.db_engine_version
  instance_class        = var.db_instance_class
  database_name         = var.db_name
  master_username       = var.db_master_username
  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  backup_retention_days = var.db_backup_retention_days
  backup_window         = var.db_backup_window
  maintenance_window    = var.db_maintenance_window
  slow_query_seconds    = var.db_slow_query_seconds
  log_retention_days    = var.log_retention_days
  time_zone             = var.institution_time_zone
}

module "app" {
  source = "../../modules/app"

  name                        = var.name
  vpc_id                      = module.network.vpc_id
  subnet_id                   = module.network.private_subnet_ids[0]
  s3_prefix_list_id           = module.network.s3_prefix_list_id
  database_security_group_id  = module.database.security_group_id
  egress_tcp_ports            = var.egress_tcp_ports
  instance_type               = var.app_instance_type
  scan_uploads                = var.scan_uploads
  root_volume_size            = var.app_root_volume_size
  termination_protection      = var.app_termination_protection
  ecr_repository_arn          = module.deploy.ecr_repository_arn
  log_retention_days          = var.log_retention_days
  secret_recovery_window_days = var.secret_recovery_window_days
  docker_compose_version      = var.docker_compose_version
  docker_compose_sha256       = var.docker_compose_sha256

  bucket_arns = {
    content  = module.storage.bucket_arns["content"]
    delivery = module.storage.bucket_arns["delivery"]
  }
  kms_key_arns = module.storage.kms_key_arns
}

module "edge" {
  source = "../../modules/edge"

  name                                 = var.name
  domain_name                          = var.domain_name
  route53_zone_id                      = var.route53_zone_id
  price_class                          = var.cloudfront_price_class
  vpc_id                               = module.network.vpc_id
  app_instance_arn                     = module.app.instance_arn
  app_private_dns                      = module.app.private_dns
  app_security_group_id                = module.app.security_group_id
  app_port                             = var.app_port
  app_origin_read_timeout              = var.app_origin_read_timeout
  frontend_bucket_regional_domain_name = module.storage.bucket_regional_domain_names["frontend"]
  delivery_bucket_regional_domain_name = module.storage.bucket_regional_domain_names["delivery"]
  signing_public_keys                  = var.cloudfront_signing_public_keys
  frontend_routing                     = var.frontend_routing
  frontend_content_security_policy     = var.frontend_content_security_policy
  hsts_max_age_seconds                 = var.hsts_max_age_seconds
  waf_common_rules_to_count            = var.waf_common_rules_to_count
  waf_body_size_limit_bytes            = var.waf_body_size_limit_bytes
  waf_large_body_path_prefixes         = var.waf_large_body_path_prefixes
}

locals {
  # Malware scan (ADR-0018): start clamav and freshclam and refuse to start without a scanner.
  # With these set, uploads stay quarantined until the worker gets a clean verdict from clamd.
  scan_app_environment = {
    COMPOSE_PROFILES      = "scan"
    ASTRA_SCAN_CLAMD_HOST = "clamav"
    ASTRA_SCAN_CLAMD_PORT = "3310"
    ASTRA_SCAN_REQUIRED   = "true"
  }

  # Settings Terraform knows. The deploy document writes them to /opt/astra/.env with
  # var.app_environment and the Secrets Manager values (README env tables).
  derived_app_environment = merge({
    ASTRA_API_BIND                = "0.0.0.0"
    ASTRA_API_PORT                = tostring(var.app_port)
    DB_URL                        = "jdbc:mysql://${module.database.address}:${module.database.port}/${module.database.database_name}?sslMode=REQUIRED"
    ASTRA_STORAGE_CONTENT_BUCKET  = module.storage.bucket_names["content"]
    ASTRA_STORAGE_DELIVERY_BUCKET = module.storage.bucket_names["delivery"]
    ASTRA_STORAGE_REGION          = var.aws_region
    ASTRA_CLOUDFRONT_BASE_URL     = module.edge.base_url
    ASTRA_CLOUDFRONT_KEY_PAIR_ID  = module.edge.signing_public_key_ids[var.cloudfront_signing_active_key]
    # The VPC origin hop is plain HTTP, so the app cannot derive its public origin from the request.
    ASTRA_SESSION_ALLOWED_ORIGINS = module.edge.base_url
    ASTRA_OIDC_REDIRECT_URI       = "${module.edge.base_url}/api/auth/callback"
    # CloudFront VPC origin ENIs sit in the app subnet and the app security group admits only them on the
    # app port, so a peer from this CIDR came through CloudFront and its CloudFront-Viewer-Address is trusted.
    ASTRA_CLIENT_ADDRESS_TRUSTED_PROXIES = module.network.private_subnet_cidrs[0]
    # The same zone as the RDS default time_zone. The app pins each session itself and refuses to start on a mismatch.
    ASTRA_TIME_ZONE = var.institution_time_zone
  }, { for k, v in local.scan_app_environment : k => v if var.scan_uploads })
}

module "deploy" {
  source = "../../modules/deploy"

  name                        = var.name
  ecr_repository_name         = var.ecr_repository_name
  ecr_keep_images             = var.ecr_keep_images
  github_repository           = var.github_repository
  github_refs                 = var.github_refs
  github_environment          = var.github_environment
  create_github_oidc_provider = var.create_github_oidc_provider
  deploy_target_tag           = module.app.deploy_target_tag
  egress_bridge               = module.app.egress_bridge
  app_environment             = merge(var.app_environment, local.derived_app_environment)
  secrets                     = module.app.secrets
}

module "monitoring" {
  source = "../../modules/monitoring"

  name                       = var.name
  alarm_emails               = var.alarm_emails
  app_instance_id            = module.app.instance_id
  nat_autoscaling_group_name = module.network.nat_autoscaling_group_name
  db_instance_identifier     = module.database.identifier
  distribution_id            = module.edge.distribution_id
  rds_cpu_percent            = var.rds_cpu_alarm_percent
  rds_free_storage_bytes     = var.rds_free_storage_alarm_bytes
  rds_max_connections        = var.rds_connections_alarm
  cloudfront_5xx_percent     = var.cloudfront_5xx_alarm_percent
}
