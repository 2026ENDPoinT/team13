data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
  partition  = data.aws_partition.current.partition

  github_oidc_host = "token.actions.githubusercontent.com"
  github_oidc_provider_arn = (var.create_github_oidc_provider
    ? aws_iam_openid_connect_provider.github[0].arn
  : "arn:${local.partition}:iam::${local.account_id}:oidc-provider/${local.github_oidc_host}")

  github_subjects = (var.github_environment != null
    ? ["repo:${var.github_repository}:environment:${var.github_environment}"]
  : [for ref in var.github_refs : "repo:${var.github_repository}:ref:${ref}"])
}

# ---------------------------------------------------------------------------
# ECR
# ---------------------------------------------------------------------------

resource "aws_ecr_repository" "this" {
  name                 = var.ecr_repository_name
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the most recent images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_keep_images
        }
        action = { type = "expire" }
      },
    ]
  })
}

# ---------------------------------------------------------------------------
# Deploy command: the only command the CI role may run on the instance.
# ---------------------------------------------------------------------------

resource "aws_ssm_document" "deploy" {
  name            = "${var.name}-deploy"
  document_type   = "Command"
  document_format = "JSON"
  target_type     = "/AWS::EC2::Instance"

  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Render /opt/astra/.env and roll the compose project to an ECR image tag."
    parameters = {
      ImageTag = {
        type           = "String"
        description    = "Image tag in ${aws_ecr_repository.this.repository_url}"
        allowedPattern = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"
      }
    }
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "deploy"
      inputs = {
        timeoutSeconds = 900
        runCommand = split("\n", templatefile("${path.module}/templates/deploy.sh", {
          region         = local.region
          registry       = split("/", aws_ecr_repository.this.repository_url)[0]
          repository_url = aws_ecr_repository.this.repository_url
          config_lines   = join("\n", [for k in sort(keys(var.app_environment)) : "${k}='${var.app_environment[k]}'"])
          fetch_lines = join("\n", [for id in sort(keys(var.secrets)) :
            "fetch '${var.secrets[id].arn}' '${jsonencode(var.secrets[id].keys)}' ${var.secrets[id].required ? "required" : "optional"}"
          ])
        }))
      }
    }]
  })
}

# ---------------------------------------------------------------------------
# GitHub Actions OIDC and the deploy role
# ---------------------------------------------------------------------------

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://${local.github_oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "deploy_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.github_oidc_host}:sub"
      values   = local.github_subjects
    }
  }
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.deploy_assume.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "deploy" {
  # GetAuthorizationToken has no resource-level permissions.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.this.arn]
  }

  statement {
    sid       = "RunDeployDocument"
    actions   = ["ssm:SendCommand"]
    resources = [aws_ssm_document.deploy.arn]
  }

  statement {
    sid       = "OnDeployTargetOnly"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:${local.partition}:ec2:${local.region}:${local.account_id}:instance/*"]
    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/${var.deploy_target_tag.key}"
      values   = [var.deploy_target_tag.value]
    }
  }

  # Read the result of the command it sent. These actions have no resource-level permissions.
  statement {
    sid = "ReadCommandResult"
    actions = [
      "ssm:GetCommandInvocation",
      "ssm:ListCommandInvocations",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "deploy" {
  name   = "deploy"
  role   = aws_iam_role.deploy.id
  policy = data.aws_iam_policy_document.deploy.json
}
