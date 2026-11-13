variable "name" {
  description = "Name prefix for network resources."
  type        = string
}

variable "vpc_cidr" {
  description = "IPv4 CIDR of the VPC."
  type        = string
}

variable "availability_zones" {
  description = "Availability zones. The first one hosts fck-nat and the app instance; RDS needs at least two."
  type        = list(string)
}

variable "subnet_newbits" {
  description = "Bits added to the VPC prefix for each subnet (cidrsubnet newbits)."
  type        = number
}

variable "egress_tcp_ports" {
  description = "TCP ports the private subnets may reach on the internet through fck-nat."
  type        = list(number)
}

variable "nat_instance_type" {
  description = "fck-nat instance type."
  type        = string
}

variable "nat_root_volume_size" {
  description = "fck-nat root volume size in GiB."
  type        = number
}

variable "fck_nat_ami_owner" {
  description = "AWS account that publishes the fck-nat AMIs."
  type        = string
}
