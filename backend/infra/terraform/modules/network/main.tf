data "aws_region" "current" {}
data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_default_tags" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
  partition  = data.aws_partition.current.partition
  nat_name   = "${var.name}-fck-nat"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = var.name }
}

# The default security group allows all traffic between its members. Nothing uses it; strip its rules.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-default-unused" }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = var.name }
}

resource "aws_subnet" "public" {
  count = length(var.availability_zones)

  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, var.subnet_newbits, count.index)
  map_public_ip_on_launch = false

  tags = { Name = "${var.name}-public-${var.availability_zones[count.index]}" }
}

resource "aws_subnet" "private" {
  count = length(var.availability_zones)

  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, var.subnet_newbits, count.index + length(var.availability_zones))
  map_public_ip_on_launch = false

  tags = { Name = "${var.name}-private-${var.availability_zones[count.index]}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name}-private" }
}

# All non-S3 egress from the private subnets goes through the fck-nat static ENI (architecture.md 3.3).
resource "aws_route" "private_nat" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  network_interface_id   = aws_network_interface.nat.id
}

resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# S3 traffic stays on the AWS network without passing fck-nat. No interface endpoints (architecture.md 3.3).
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]

  tags = { Name = "${var.name}-s3" }
}

# ---------------------------------------------------------------------------
# fck-nat in high-availability mode: an Auto Scaling group of size 1 whose
# instance attaches a fixed ENI (the private route target) and a fixed EIP at boot.
# https://fck-nat.dev/stable/features/
# ---------------------------------------------------------------------------

data "aws_ec2_instance_type" "nat" {
  instance_type = var.nat_instance_type
}

data "aws_ami" "fck_nat" {
  most_recent = true
  owners      = [var.fck_nat_ami_owner]

  filter {
    name   = "name"
    values = ["fck-nat-al2023-hvm-*"]
  }

  filter {
    name   = "architecture"
    values = [contains(data.aws_ec2_instance_type.nat.supported_architectures, "arm64") ? "arm64" : "x86_64"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}

resource "aws_security_group" "nat" {
  name        = local.nat_name
  description = "fck-nat: forwards egress from the private subnets only"
  vpc_id      = aws_vpc.this.id

  tags = { Name = local.nat_name }
}

resource "aws_vpc_security_group_ingress_rule" "nat_from_private" {
  for_each = { for pair in setproduct(aws_subnet.private[*].cidr_block, var.egress_tcp_ports) : "${pair[0]}-${pair[1]}" => pair }

  security_group_id = aws_security_group.nat.id
  description       = "Forwarded egress from private subnet"
  ip_protocol       = "tcp"
  from_port         = each.value[1]
  to_port           = each.value[1]
  cidr_ipv4         = each.value[0]
}

resource "aws_vpc_security_group_egress_rule" "nat_to_internet" {
  for_each = toset([for port in var.egress_tcp_ports : tostring(port)])

  security_group_id = aws_security_group.nat.id
  description       = "NAT egress to the internet"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_network_interface" "nat" {
  description       = "${local.nat_name} static private ENI"
  subnet_id         = aws_subnet.public[0].id
  security_groups   = [aws_security_group.nat.id]
  source_dest_check = false

  tags = { Name = local.nat_name }
}

# Fixed public egress address. External parties (school IdP, LLM provider) can allowlist it.
resource "aws_eip" "nat" {
  domain = "vpc"

  tags = { Name = local.nat_name }
}

data "aws_iam_policy_document" "nat_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "nat" {
  name               = local.nat_name
  assume_role_policy = data.aws_iam_policy_document.nat_assume.json
}

data "aws_iam_policy_document" "nat" {
  # fck-nat attaches the static ENI and associates the EIP at boot. EC2 does not support
  # resource-level ARNs known before launch for the replacement instance, so the grant is
  # narrowed with the Name tag that only fck-nat resources carry.
  statement {
    sid = "AttachStaticEni"
    actions = [
      "ec2:AttachNetworkInterface",
      "ec2:ModifyNetworkInterfaceAttribute",
    ]
    resources = [
      "arn:${local.partition}:ec2:${local.region}:${local.account_id}:instance/*",
      "arn:${local.partition}:ec2:${local.region}:${local.account_id}:network-interface/*",
    ]
    condition {
      test     = "StringEquals"
      variable = "ec2:ResourceTag/Name"
      values   = [local.nat_name]
    }
  }

  statement {
    sid = "AssociateEipAddress"
    actions = [
      "ec2:AssociateAddress",
      "ec2:DisassociateAddress",
    ]
    resources = ["arn:${local.partition}:ec2:${local.region}:${local.account_id}:elastic-ip/${aws_eip.nat.allocation_id}"]
  }

  statement {
    sid = "AssociateEipInterface"
    actions = [
      "ec2:AssociateAddress",
      "ec2:DisassociateAddress",
    ]
    resources = [
      "arn:${local.partition}:ec2:${local.region}:${local.account_id}:instance/*",
      "arn:${local.partition}:ec2:${local.region}:${local.account_id}:network-interface/*",
    ]
    condition {
      test     = "StringEquals"
      variable = "ec2:ResourceTag/Name"
      values   = [local.nat_name]
    }
  }
}

resource "aws_iam_role_policy" "nat" {
  name   = "fck-nat-ha"
  role   = aws_iam_role.nat.id
  policy = data.aws_iam_policy_document.nat.json
}

# SSM Session Manager for operator access; no SSH key and no port 22.
resource "aws_iam_role_policy_attachment" "nat_ssm" {
  role       = aws_iam_role.nat.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "nat" {
  name = local.nat_name
  role = aws_iam_role.nat.name
}

resource "aws_launch_template" "nat" {
  name          = local.nat_name
  image_id      = data.aws_ami.fck_nat.id
  instance_type = var.nat_instance_type

  iam_instance_profile {
    arn = aws_iam_instance_profile.nat.arn
  }

  # The primary ENI needs a public address to call the EC2 API before the EIP is associated.
  network_interfaces {
    description                 = "${local.nat_name} ephemeral public ENI"
    subnet_id                   = aws_subnet.public[0].id
    associate_public_ip_address = true
    security_groups             = [aws_security_group.nat.id]
    delete_on_termination       = true
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_type           = "gp3"
      volume_size           = var.nat_root_volume_size
      encrypted             = true
      delete_on_termination = true
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  credit_specification {
    cpu_credits = "unlimited"
  }

  user_data = base64encode(templatefile("${path.module}/templates/fck-nat-user-data.sh", {
    eni_id = aws_network_interface.nat.id
    eip_id = aws_eip.nat.allocation_id
  }))

  dynamic "tag_specifications" {
    for_each = ["instance", "network-interface", "volume"]

    content {
      resource_type = tag_specifications.value
      tags          = merge(data.aws_default_tags.current.tags, { Name = local.nat_name })
    }
  }

  tags = { Name = local.nat_name }

  lifecycle {
    # A newer fck-nat AMI would otherwise roll the NAT instance (and pause all egress) on any apply.
    # Pick it up deliberately: terraform apply -replace=module.network.aws_launch_template.nat
    ignore_changes = [image_id]
  }
}

resource "aws_autoscaling_group" "nat" {
  name                = local.nat_name
  min_size            = 1
  max_size            = 1
  desired_capacity    = 1
  health_check_type   = "EC2"
  vpc_zone_identifier = [aws_subnet.public[0].id]

  launch_template {
    id      = aws_launch_template.nat.id
    version = aws_launch_template.nat.latest_version
  }

  # The static ENI must be released before a replacement can attach it.
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 0
    }
  }

  enabled_metrics = ["GroupInServiceInstances"]

  tag {
    key                 = "Name"
    value               = local.nat_name
    propagate_at_launch = false
  }

  timeouts {
    delete = "15m"
  }
}
