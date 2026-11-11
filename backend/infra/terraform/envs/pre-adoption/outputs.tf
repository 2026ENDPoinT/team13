# Values the deployment and the operator need. None of them is secret.

output "service_url" {
  value = module.edge.base_url
}

output "cloudfront_distribution_id" {
  description = "For cache invalidation after a frontend upload."
  value       = module.edge.distribution_id
}

output "cloudfront_signing_public_key_ids" {
  description = "Public key IDs by name. The active one is ASTRA_CLOUDFRONT_KEY_PAIR_ID."
  value       = module.edge.signing_public_key_ids
}

output "bucket_names" {
  description = "frontend (static build upload target), content, delivery."
  value       = module.storage.bucket_names
}

output "ecr_repository_url" {
  value = module.deploy.ecr_repository_url
}

output "github_deploy_role_arn" {
  description = "role-to-assume for aws-actions/configure-aws-credentials."
  value       = module.deploy.deploy_role_arn
}

output "deploy_document_name" {
  description = "SSM document to send with parameter ImageTag to the instance tagged as deploy target."
  value       = module.deploy.deploy_document_name
}

output "deploy_target_tag" {
  value = module.app.deploy_target_tag
}

output "app_instance_id" {
  description = "For SSM Session Manager (aws ssm start-session --target)."
  value       = module.app.instance_id
}

output "app_log_group" {
  value = module.app.log_group_name
}

output "app_secret_arns" {
  description = "Secret containers to fill with JSON objects whose keys are the listed environment variables."
  value       = { for k, s in module.app.secrets : k => { arn = s.arn, keys = s.keys, required = s.required } }
}

output "app_environment" {
  description = "Non-secret settings the deploy document writes to /opt/astra/.env."
  value       = merge(var.app_environment, local.derived_app_environment)
}

output "db_endpoint" {
  value = "${module.database.address}:${module.database.port}"
}

output "db_master_user_secret_arn" {
  description = "RDS-managed master credentials, for creating the app user (operators only)."
  value       = module.database.master_user_secret_arn
}

output "nat_egress_ip" {
  description = "Fixed public IPv4 of all egress (school IdP and LLM provider allowlists)."
  value       = module.network.nat_public_ip
}

output "alarm_topic_arns" {
  value = module.monitoring.alarm_topic_arns
}
