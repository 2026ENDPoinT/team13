terraform {
  required_version = "~> 1.15.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.72"
    }
  }

  # Partial configuration: bucket, key and region come from `terraform init -backend-config=backend.hcl`.
  # See backend.hcl.example. Locking uses the S3 lock file (use_lockfile), no DynamoDB table.
  backend "s3" {}
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]

  default_tags {
    tags = {
      Project     = "astra"
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    }
  }
}
