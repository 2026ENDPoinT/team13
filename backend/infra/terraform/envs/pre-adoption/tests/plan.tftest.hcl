# Plans the whole environment against a mocked AWS provider: no credentials, nothing created.
# Run: terraform init -backend=false && terraform test

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }

  mock_data "aws_region" {
    defaults = { region = "ap-northeast-2" }
  }

  mock_data "aws_partition" {
    defaults = { partition = "aws", dns_suffix = "amazonaws.com" }
  }

  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }

  # t3.large
  mock_data "aws_ec2_instance_type" {
    defaults = { supported_architectures = ["x86_64"], memory_size = 8192 }
  }

  mock_resource "aws_cloudfront_distribution" {
    defaults = { arn = "arn:aws:cloudfront::123456789012:distribution/EMOCK" }
  }

  mock_resource "aws_s3_bucket" {
    defaults = { arn = "arn:aws:s3:::mock-bucket" }
  }

  mock_resource "aws_kms_key" {
    defaults = { arn = "arn:aws:kms:ap-northeast-2:123456789012:key/mock" }
  }

  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/mock" }
  }

  mock_resource "aws_iam_instance_profile" {
    defaults = { arn = "arn:aws:iam::123456789012:instance-profile/mock" }
  }

  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }

  mock_resource "aws_ecr_repository" {
    defaults = {
      arn            = "arn:aws:ecr:ap-northeast-2:123456789012:repository/astra-backend"
      repository_url = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/astra-backend"
    }
  }

  mock_resource "aws_wafv2_web_acl" {
    defaults = { arn = "arn:aws:wafv2:us-east-1:123456789012:global/webacl/mock/mock" }
  }

  mock_resource "aws_secretsmanager_secret" {
    defaults = { arn = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:mock" }
  }

  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:ap-northeast-2:123456789012:log-group:mock" }
  }

  mock_resource "aws_ssm_document" {
    defaults = { arn = "arn:aws:ssm:ap-northeast-2:123456789012:document/mock" }
  }

  mock_resource "aws_sns_topic" {
    defaults = { arn = "arn:aws:sns:ap-northeast-2:123456789012:mock" }
  }

  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:us-east-1:123456789012:certificate/mock"
      domain_validation_options = [{
        domain_name           = "eclass.example.ac.kr"
        resource_record_name  = "_mock.eclass.example.ac.kr."
        resource_record_type  = "CNAME"
        resource_record_value = "_mock.acm-validations.aws."
      }]
    }
  }

  mock_resource "aws_instance" {
    defaults = {
      id          = "i-0123456789abcdef0"
      arn         = "arn:aws:ec2:ap-northeast-2:123456789012:instance/i-0123456789abcdef0"
      private_dns = "ip-10-40-2-10.ap-northeast-2.compute.internal"
    }
  }

  mock_resource "aws_launch_template" {
    defaults = { id = "lt-0123456789abcdef0", latest_version = 1 }
  }

  mock_resource "aws_cloudfront_function" {
    defaults = { arn = "arn:aws:cloudfront::123456789012:function/astra-pre-frontend-routing" }
  }

  mock_resource "aws_acm_certificate_validation" {
    defaults = { certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/mock" }
  }

  mock_resource "aws_db_instance" {
    defaults = {
      address            = "astra-pre.mock.ap-northeast-2.rds.amazonaws.com"
      port               = 3306
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds-mock", kms_key_id = "", secret_status = "active" }]
    }
  }
}

variables {
  aws_account_id  = "123456789012"
  domain_name     = "eclass.example.ac.kr"
  route53_zone_id = "Z0123456789ABCDEFGHIJ"
  cloudfront_signing_public_keys = {
    k2026a = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmock\n-----END PUBLIC KEY-----\n"
  }
  cloudfront_signing_active_key = "k2026a"
}

run "plan" {
  command = apply

  assert {
    condition     = output.app_environment["ASTRA_SESSION_ALLOWED_ORIGINS"] == "https://eclass.example.ac.kr"
    error_message = "Allowed origin must be the service URL."
  }

  assert {
    condition     = startswith(output.app_environment["DB_URL"], "jdbc:mysql://") && endswith(output.app_environment["DB_URL"], "/astra?sslMode=REQUIRED")
    error_message = "DB_URL must point at the RDS endpoint with TLS required."
  }

  assert {
    condition     = output.app_environment["ASTRA_CLIENT_ADDRESS_TRUSTED_PROXIES"] == "10.40.2.0/24"
    error_message = "Only the app subnet, where the CloudFront VPC origin ENIs sit, may supply the viewer address."
  }

  assert {
    condition     = output.app_environment["ASTRA_TIME_ZONE"] == "Asia/Seoul"
    error_message = "The app must run in the institution zone, the same one the RDS parameter group sets (ADR-0039)."
  }

  # M2: the default deployment runs and requires the malware scanner (ADR-0018).
  assert {
    condition = alltrue([for k, v in {
      COMPOSE_PROFILES      = "scan"
      ASTRA_SCAN_CLAMD_HOST = "clamav"
      ASTRA_SCAN_CLAMD_PORT = "3310"
      ASTRA_SCAN_REQUIRED   = "true"
    } : lookup(output.app_environment, k, null) == v])
    error_message = "By default the app environment must start the scan profile and require the scanner."
  }
}

# Renders the deploy document from the environment the run above produced.
run "deploy_document" {
  command = apply

  module {
    source = "../../modules/deploy"
  }

  variables {
    name                        = "astra-pre"
    ecr_repository_name         = "astra-backend"
    ecr_keep_images             = 30
    github_repository           = "STack-Corp/STack-eclass-Backend"
    github_refs                 = ["refs/heads/main"]
    create_github_oidc_provider = true
    deploy_target_tag           = { key = "DeployTarget", value = "astra-pre" }
    egress_bridge               = "astra-egress"
    app_environment             = run.plan.app_environment
    secrets                     = {}
  }

  # The deploy document writes exactly these lines into /opt/astra/.env.
  assert {
    condition = alltrue([for line in [
      "COMPOSE_PROFILES='scan'", "ASTRA_SCAN_CLAMD_HOST='clamav'", "ASTRA_SCAN_CLAMD_PORT='3310'", "ASTRA_SCAN_REQUIRED='true'",
    ] : contains(jsondecode(aws_ssm_document.deploy.content).mainSteps[0].inputs.runCommand, line)])
    error_message = "The rendered .env must contain the scanner settings."
  }

  # M3: the deploy document refuses a host without the metadata guard and probes every container after the restart.
  assert {
    condition = anytrue([for line in jsondecode(aws_ssm_document.deploy.content).mainSteps[0].inputs.runCommand :
    strcontains(line, "iptables -w -C DOCKER-USER -d 169.254.169.254/32 ! -i 'astra-egress' -j DROP")])
    error_message = "The deploy document must check the instance metadata firewall rule for the egress bridge."
  }
}

# Plans the app module alone so that the instance and its precondition are checkable objects of the run.
run "app_instance" {
  command = apply

  module {
    source = "../../modules/app"
  }

  variables {
    name                        = "astra-pre"
    vpc_id                      = "vpc-0123456789abcdef0"
    subnet_id                   = "subnet-0123456789abcdef0"
    s3_prefix_list_id           = "pl-78a54011"
    database_security_group_id  = "sg-0123456789abcdef0"
    egress_tcp_ports            = [443]
    instance_type               = "t3.large"
    scan_uploads                = true
    root_volume_size            = 30
    termination_protection      = true
    bucket_arns                 = { content = "arn:aws:s3:::c", delivery = "arn:aws:s3:::d" }
    kms_key_arns                = { content = "arn:aws:kms:ap-northeast-2:123456789012:key/c" }
    ecr_repository_arn          = "arn:aws:ecr:ap-northeast-2:123456789012:repository/astra-backend"
    log_retention_days          = 90
    secret_recovery_window_days = 30
    docker_compose_version      = "v5.7.1"
    docker_compose_sha256       = { x86_64 = "af426722a8af5de812fcee30cc1c39f0639f5f723cbadefb25e5d4db054064cf" }
  }

  assert {
    condition     = aws_instance.app.metadata_options[0].http_tokens == "required"
    error_message = "IMDSv2 must be required."
  }

  # M3: the bootstrap drops forwarded metadata traffic that does not enter from the egress bridge.
  assert {
    condition     = strcontains(aws_instance.app.user_data, "-d 169.254.169.254/32 ! -i astra-egress -j DROP")
    error_message = "The user data must install the instance metadata guard for the egress bridge."
  }
}

run "scan_disabled_explicitly" {
  command = plan

  variables {
    scan_uploads = false
  }

  assert {
    condition     = length(setintersection(keys(output.app_environment), ["COMPOSE_PROFILES", "ASTRA_SCAN_CLAMD_HOST", "ASTRA_SCAN_CLAMD_PORT", "ASTRA_SCAN_REQUIRED"])) == 0
    error_message = "scan_uploads = false must leave the scanner settings out."
  }
}

run "reject_scan_switch_in_app_environment" {
  command = plan

  variables {
    app_environment = { ASTRA_SCAN_REQUIRED = "false" }
  }

  expect_failures = [var.app_environment]
}

run "reject_scan_on_small_instance" {
  command = plan

  module {
    source = "../../modules/app"
  }

  variables {
    name                        = "astra-pre"
    vpc_id                      = "vpc-0123456789abcdef0"
    subnet_id                   = "subnet-0123456789abcdef0"
    s3_prefix_list_id           = "pl-78a54011"
    database_security_group_id  = "sg-0123456789abcdef0"
    egress_tcp_ports            = [443]
    instance_type               = "t3.medium"
    scan_uploads                = true
    root_volume_size            = 30
    termination_protection      = true
    bucket_arns                 = { content = "arn:aws:s3:::c", delivery = "arn:aws:s3:::d" }
    kms_key_arns                = { content = "arn:aws:kms:ap-northeast-2:123456789012:key/c" }
    ecr_repository_arn          = "arn:aws:ecr:ap-northeast-2:123456789012:repository/astra-backend"
    log_retention_days          = 90
    secret_recovery_window_days = 30
    docker_compose_version      = "v5.7.1"
    docker_compose_sha256       = { x86_64 = "af426722a8af5de812fcee30cc1c39f0639f5f723cbadefb25e5d4db054064cf" }
  }

  # t3.medium
  override_data {
    target = data.aws_ec2_instance_type.app
    values = { supported_architectures = ["x86_64"], memory_size = 4096 }
  }

  expect_failures = [aws_instance.app]
}

run "reject_reserved_app_environment" {
  command = plan

  variables {
    app_environment = { DB_PASSWORD = "x" }
  }

  expect_failures = [var.app_environment]
}

run "reject_private_key" {
  command = plan

  variables {
    cloudfront_signing_public_keys = { k = "-----BEGIN PUBLIC KEY-----\n-----BEGIN PRIVATE KEY-----" }
    cloudfront_signing_active_key  = "k"
  }

  expect_failures = [var.cloudfront_signing_public_keys]
}
