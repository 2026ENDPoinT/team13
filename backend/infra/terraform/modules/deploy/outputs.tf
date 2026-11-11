output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "ecr_repository_arn" {
  value = aws_ecr_repository.this.arn
}

output "deploy_role_arn" {
  description = "Role for aws-actions/configure-aws-credentials (role-to-assume)."
  value       = aws_iam_role.deploy.arn
}

output "deploy_document_name" {
  description = "SSM Command document the deploy role runs with parameter ImageTag."
  value       = aws_ssm_document.deploy.name
}
