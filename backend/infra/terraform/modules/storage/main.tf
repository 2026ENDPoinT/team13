data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition

  # frontend: static build. content: originals, model inputs, summaries, video keys.
  # delivery: HLS (vod/) and page images (doc/) that CloudFront serves (ADR-0007).
  buckets = {
    frontend = { versioned = true, kms = false }
    content  = { versioned = true, kms = true }
    delivery = { versioned = false, kms = true }
  }

  kms_buckets = { for k, v in local.buckets : k => v if v.kms }
}

# ---------------------------------------------------------------------------
# KMS: one customer managed key per confidential bucket (ADR-0007 decision 3).
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "key" {
  for_each = local.kms_buckets

  # Key administration and use are delegated to IAM policies in this account.
  # Without this statement the key becomes unmanageable.
  statement {
    sid       = "AccountIamDelegation"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account_id}:root"]
    }
  }

  # Only the delivery key: CloudFront (OAC) may decrypt objects for this distribution only.
  dynamic "statement" {
    for_each = each.key == "delivery" ? [1] : []

    content {
      sid       = "CloudFrontOacDecrypt"
      actions   = ["kms:Decrypt"]
      resources = ["*"]
      principals {
        type        = "Service"
        identifiers = ["cloudfront.amazonaws.com"]
      }
      condition {
        test     = "StringEquals"
        variable = "AWS:SourceArn"
        values   = [var.cloudfront_distribution_arn]
      }
    }
  }
}

resource "aws_kms_key" "this" {
  for_each = local.kms_buckets

  description             = "${var.name} ${each.key} bucket SSE-KMS"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  policy                  = data.aws_iam_policy_document.key[each.key].json

  tags = { Name = "${var.name}-${each.key}" }
}

resource "aws_kms_alias" "this" {
  for_each = local.kms_buckets

  name          = "alias/${var.name}-${each.key}"
  target_key_id = aws_kms_key.this[each.key].key_id
}

# ---------------------------------------------------------------------------
# Buckets
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket        = "${var.name}-${each.key}-${local.account_id}"
  force_destroy = false

  tags = { Name = "${var.name}-${each.key}" }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = local.buckets

  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = { for k, v in local.buckets : k => v if v.versioned }

  bucket = aws_s3_bucket.this[each.key].id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = each.value.kms ? "aws:kms" : "AES256"
      kms_master_key_id = each.value.kms ? aws_kms_key.this[each.key].arn : null
    }
    # S3 Bucket Keys cut KMS request volume (and cost) for the many small HLS segments and page images.
    bucket_key_enabled = each.value.kms
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    id     = "abort-incomplete-multipart-upload"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = var.abort_incomplete_multipart_days
    }
  }

  dynamic "rule" {
    for_each = each.value.versioned ? [1] : []

    content {
      id     = "expire-noncurrent-versions"
      status = "Enabled"
      filter {}
      noncurrent_version_expiration {
        noncurrent_days = var.noncurrent_version_days
      }
      expiration {
        expired_object_delete_marker = true
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# ---------------------------------------------------------------------------
# Bucket policies
# ---------------------------------------------------------------------------

data "aws_iam_policy_document" "bucket" {
  for_each = local.buckets

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.this[each.key].arn,
      "${aws_s3_bucket.this[each.key].arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # Objects in the confidential buckets must use the bucket's own key. Requests without SSE headers
  # get the bucket default (that key); requests that name an encryption must name exactly that key.
  dynamic "statement" {
    for_each = each.value.kms ? [1] : []

    content {
      sid       = "DenyOtherEncryption"
      effect    = "Deny"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this[each.key].arn}/*"]
      principals {
        type        = "*"
        identifiers = ["*"]
      }
      condition {
        test     = "Null"
        variable = "s3:x-amz-server-side-encryption"
        values   = ["false"]
      }
      condition {
        test     = "StringNotEquals"
        variable = "s3:x-amz-server-side-encryption"
        values   = ["aws:kms"]
      }
    }
  }

  dynamic "statement" {
    for_each = each.value.kms ? [1] : []

    content {
      sid       = "DenyKmsWithoutBucketKeyId"
      effect    = "Deny"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this[each.key].arn}/*"]
      principals {
        type        = "*"
        identifiers = ["*"]
      }
      condition {
        test     = "Null"
        variable = "s3:x-amz-server-side-encryption"
        values   = ["false"]
      }
      condition {
        test     = "Null"
        variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
        values   = ["true"]
      }
    }
  }

  dynamic "statement" {
    for_each = each.value.kms ? [1] : []

    content {
      sid       = "DenyOtherKmsKeys"
      effect    = "Deny"
      actions   = ["s3:PutObject"]
      resources = ["${aws_s3_bucket.this[each.key].arn}/*"]
      principals {
        type        = "*"
        identifiers = ["*"]
      }
      condition {
        test     = "Null"
        variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
        values   = ["false"]
      }
      condition {
        test     = "StringNotEquals"
        variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
        values   = [aws_kms_key.this[each.key].arn]
      }
    }
  }

  # CloudFront OAC is the only reader of frontend and delivery. delivery is limited to its two prefixes.
  dynamic "statement" {
    for_each = contains(["frontend", "delivery"], each.key) ? [1] : []

    content {
      sid     = "CloudFrontOacRead"
      actions = ["s3:GetObject"]
      resources = each.key == "delivery" ? [
        "${aws_s3_bucket.this[each.key].arn}/vod/*",
        "${aws_s3_bucket.this[each.key].arn}/doc/*",
      ] : ["${aws_s3_bucket.this[each.key].arn}/*"]
      principals {
        type        = "Service"
        identifiers = ["cloudfront.amazonaws.com"]
      }
      condition {
        test     = "StringEquals"
        variable = "AWS:SourceArn"
        values   = [var.cloudfront_distribution_arn]
      }
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  policy = data.aws_iam_policy_document.bucket[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}
