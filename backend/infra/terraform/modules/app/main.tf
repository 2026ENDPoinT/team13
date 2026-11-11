data "aws_region" "current" {}
data "aws_partition" "current" {}

locals {
  region    = data.aws_region.current.region
  partition = data.aws_partition.current.partition

  # Secret containers only; values are put by an operator outside Terraform (infra/README.md).
  # Each secret value is a JSON object whose keys are the environment variable names the app reads.
  # The deploy document accepts only the keys listed here.
  secrets = {
    db-app = {
      description = "App database user for MySQL (not the RDS master user)."
      keys        = ["DB_USERNAME", "DB_PASSWORD"]
      required    = true
    }
    break-glass = {
      description = "Break-glass local login (architecture.md 5): external_ref, bcrypt hash, TOTP secret."
      keys        = ["ASTRA_BREAK_GLASS_EXTERNAL_REF", "ASTRA_BREAK_GLASS_PASSWORD_HASH", "ASTRA_BREAK_GLASS_TOTP_SECRET"]
      required    = false
    }
    cloudfront-private-key = {
      description = "PKCS#8 PEM RSA private key matching a public key in the CloudFront key group."
      keys        = ["ASTRA_CLOUDFRONT_PRIVATE_KEY"]
      required    = false
    }
    llm-api-key = {
      description = "LLM provider API key (ADR-0008)."
      keys        = ["ASTRA_LLM_API_KEY"]
      required    = false
    }
    oidc-client-secret = {
      description = "School IdP OIDC client secret (ADR-0004)."
      keys        = ["ASTRA_OIDC_CLIENT_SECRET"]
      required    = false
    }
  }

  docker_compose_arch = contains(data.aws_ec2_instance_type.app.supported_architectures, "arm64") ? "aarch64" : "x86_64"

  # Bridge name of the compose egress network (compose.yaml networks.egress driver_opts). Only api and worker are on it.
  egress_bridge = "astra-egress"

  scan_min_memory_mib = 8192
}

# ---------------------------------------------------------------------------
# Secrets Manager containers (no secret values in Terraform or its state)
# ---------------------------------------------------------------------------

resource "aws_secretsmanager_secret" "this" {
  for_each = local.secrets

  name                    = "${var.name}/${each.key}"
  description             = "${each.value.description} JSON keys: ${join(", ", each.value.keys)}"
  recovery_window_in_days = var.secret_recovery_window_days

  tags = { Name = "${var.name}/${each.key}" }
}

# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "app" {
  name              = "/${var.name}/app"
  retention_in_days = var.log_retention_days
}

# ---------------------------------------------------------------------------
# Security group: app port from the CloudFront VPC origin service security group only
# (the ingress rule lives in the edge module, which owns the VPC origin).
# ---------------------------------------------------------------------------

resource "aws_security_group" "app" {
  name        = "${var.name}-app"
  description = "App instance: app port from CloudFront VPC origin only, no SSH"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-app" }
}

resource "aws_vpc_security_group_egress_rule" "app_mysql" {
  security_group_id            = aws_security_group.app.id
  description                  = "MySQL to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = var.database_security_group_id
}

resource "aws_vpc_security_group_egress_rule" "app_s3" {
  security_group_id = aws_security_group.app.id
  description       = "S3 through the gateway endpoint"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = var.s3_prefix_list_id
}

# AWS APIs other than S3 (SSM, ECR, Secrets Manager, CloudWatch) and external allowlisted hosts
# (school IdP, LLM provider) through fck-nat (architecture.md 3.3).
resource "aws_vpc_security_group_egress_rule" "app_internet" {
  for_each = toset([for port in var.egress_tcp_ports : tostring(port)])

  security_group_id = aws_security_group.app.id
  description       = "Egress through fck-nat"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------------------------------------------------------------------------
# IAM: instance role
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.name}-app"
  assume_role_policy = data.aws_iam_policy_document.assume.json
}

data "aws_iam_policy_document" "app" {
  statement {
    sid       = "ListOwnBuckets"
    actions   = ["s3:ListBucket"]
    resources = values(var.bucket_arns)
  }

  statement {
    sid = "ReadWriteOwnObjects"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = [for arn in values(var.bucket_arns) : "${arn}/*"]
  }

  statement {
    sid = "UseBucketKeysThroughS3"
    actions = [
      "kms:Decrypt",
      "kms:GenerateDataKey",
    ]
    resources = values(var.kms_key_arns)
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["s3.${local.region}.amazonaws.com"]
    }
  }

  statement {
    sid       = "ReadOwnSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for s in aws_secretsmanager_secret.this : s.arn]
  }

  # GetAuthorizationToken has no resource-level permissions.
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    resources = [var.ecr_repository_arn]
  }

  statement {
    sid = "WriteOwnLogs"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.app.arn}:*"]
  }
}

resource "aws_iam_role_policy" "app" {
  name   = "app"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app.json
}

# SSM Session Manager (operator access) and Run Command (deploy).
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "app" {
  name = "${var.name}-app"
  role = aws_iam_role.app.name
}

# ---------------------------------------------------------------------------
# Instance
# ---------------------------------------------------------------------------

data "aws_ec2_instance_type" "app" {
  instance_type = var.instance_type
}

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-${contains(data.aws_ec2_instance_type.app.supported_architectures, "arm64") ? "arm64" : "x86_64"}"
}

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023.insecure_value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.app.id]
  iam_instance_profile        = aws_iam_instance_profile.app.name
  associate_public_ip_address = false
  disable_api_termination     = var.termination_protection
  monitoring                  = false

  metadata_options {
    http_endpoint      = "enabled"
    http_tokens        = "required"
    http_protocol_ipv6 = "disabled"
    # api and worker reach IMDS from the docker egress bridge, one hop further than the host. Every other container is
    # kept away by the host firewall rule in the user data (ADR-0020 6), not by this limit.
    http_put_response_hop_limit = 2
  }

  maintenance_options {
    auto_recovery = "default"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/templates/app-user-data.sh", {
    region                 = local.region
    log_group              = aws_cloudwatch_log_group.app.name
    docker_compose_version = var.docker_compose_version
    docker_compose_arch    = local.docker_compose_arch
    docker_compose_sha256  = var.docker_compose_sha256[local.docker_compose_arch]
    egress_bridge          = local.egress_bridge
  })

  tags = {
    Name         = "${var.name}-app"
    DeployTarget = var.name
  }

  lifecycle {
    # A new AL2023 AMI or bootstrap script must not silently replace or restart the only app instance.
    # Replace deliberately with `terraform apply -replace=module.app.aws_instance.app`.
    ignore_changes = [ami, user_data]

    # clamd keeps about 1.2 GiB of signatures in memory next to api, worker, converter and transcoder (ADR-0018).
    precondition {
      condition     = !var.scan_uploads || data.aws_ec2_instance_type.app.memory_size >= local.scan_min_memory_mib
      error_message = "scan_uploads needs an app instance type with at least 8 GiB of memory (e.g. t3.large)."
    }
  }
}
