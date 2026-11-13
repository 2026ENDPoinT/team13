output "vpc_id" {
  value = aws_vpc.this.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "private_subnet_cidrs" {
  description = "IPv4 CIDRs of the private subnets, in the order of private_subnet_ids."
  value       = aws_subnet.private[*].cidr_block
}

output "s3_prefix_list_id" {
  description = "Managed prefix list of the S3 gateway endpoint, for security group egress."
  value       = aws_vpc_endpoint.s3.prefix_list_id
}

output "nat_public_ip" {
  description = "Fixed public IPv4 address of all egress through fck-nat."
  value       = aws_eip.nat.public_ip
}

output "nat_autoscaling_group_name" {
  value = aws_autoscaling_group.nat.name
}
