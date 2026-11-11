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

  mock_data "aws_ec2_instance_type" {
    defaults = { supported_architectures = ["x86_64"] }
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
