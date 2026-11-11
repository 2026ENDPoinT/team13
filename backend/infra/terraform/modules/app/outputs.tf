output "instance_id" {
  value = aws_instance.app.id
}

output "instance_arn" {
  value = aws_instance.app.arn
}

output "private_dns" {
  value = aws_instance.app.private_dns
}

output "security_group_id" {
  value = aws_security_group.app.id
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.app.name
}

output "deploy_target_tag" {
  description = "Tag (key and value) that marks the instance as the deploy target."
  value       = { key = "DeployTarget", value = var.name }
}

output "secrets" {
  description = "Secret ARN, allowed JSON keys and whether a value is required, by secret id."
  value = { for k, s in aws_secretsmanager_secret.this : k => {
    arn      = s.arn
    keys     = local.secrets[k].keys
    required = local.secrets[k].required
  } }
}
