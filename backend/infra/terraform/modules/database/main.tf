resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids

  tags = { Name = var.name }
}

resource "aws_security_group" "this" {
  name        = "${var.name}-rds"
  description = "RDS MySQL: 3306 from the app instance only, no egress"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name}-rds" }
}

resource "aws_vpc_security_group_ingress_rule" "from_app" {
  security_group_id            = aws_security_group.this.id
  description                  = "MySQL from app instance"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = var.app_security_group_id
}

resource "aws_db_parameter_group" "this" {
  name   = var.name
  family = "mysql8.4"

  parameter {
    name  = "character_set_server"
    value = "utf8mb4"
  }

  parameter {
    name  = "collation_server"
    value = "utf8mb4_0900_ai_ci"
  }

  # Reject unencrypted client connections.
  parameter {
    name  = "require_secure_transport"
    value = "1"
  }

  parameter {
    name  = "slow_query_log"
    value = "1"
  }

  parameter {
    name  = "long_query_time"
    value = tostring(var.slow_query_seconds)
  }

  # Slow query log goes to CloudWatch Logs, not a table.
  parameter {
    name  = "log_output"
    value = "FILE"
  }

  # Default session zone for operator clients, so NOW() matches the stored wall-clock values. The app does not rely on
  # it: every pooled connection sets its own offset and the app refuses to start if the session disagrees (ADR-0039).
  parameter {
    name  = "time_zone"
    value = var.time_zone
  }

  tags = { Name = var.name }

  lifecycle {
    create_before_destroy = true
  }
}

# RDS would otherwise create these log groups itself with no expiry.
resource "aws_cloudwatch_log_group" "rds" {
  for_each = toset(["error", "slowquery"])

  name              = "/aws/rds/instance/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_db_instance" "this" {
  identifier     = var.name
  engine         = "mysql"
  engine_version = var.engine_version
  instance_class = var.instance_class

  db_name  = var.database_name
  username = var.master_username
  # RDS creates and rotates the master password in Secrets Manager; Terraform state never holds it.
  manage_master_user_password = true

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az               = false
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  publicly_accessible    = false
  port                   = 3306

  parameter_group_name = aws_db_parameter_group.this.name

  # Automated backups enable point-in-time recovery within the retention window.
  backup_retention_period  = var.backup_retention_days
  backup_window            = var.backup_window
  maintenance_window       = var.maintenance_window
  copy_tags_to_snapshot    = true
  delete_automated_backups = false

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name}-final"

  auto_minor_version_upgrade      = true
  allow_major_version_upgrade     = false
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["error", "slowquery"]

  tags = { Name = var.name }

  depends_on = [aws_cloudwatch_log_group.rds]
}
