data "aws_region" "current" {}
data "aws_partition" "current" {}

# CloudFront publishes its metrics in us-east-1, and an alarm can only notify a topic in its own region.
# Both topics carry alarm state only (no application data) and use SNS default encryption settings.
resource "aws_sns_topic" "alarms" {
  name = "${var.name}-alarms"
}

resource "aws_sns_topic" "alarms_us_east_1" {
  region = "us-east-1"

  name = "${var.name}-alarms"
}

resource "aws_sns_topic_subscription" "email" {
  for_each = toset(var.alarm_emails)

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = each.value
}

resource "aws_sns_topic_subscription" "email_us_east_1" {
  region   = "us-east-1"
  for_each = toset(var.alarm_emails)

  topic_arn = aws_sns_topic.alarms_us_east_1.arn
  protocol  = "email"
  endpoint  = each.value
}

# ---------------------------------------------------------------------------
# EC2
# ---------------------------------------------------------------------------

# A failed system status check also triggers EC2 recovery onto healthy hardware (ADR-0003).
resource "aws_cloudwatch_metric_alarm" "ec2_system_status" {
  alarm_name          = "${var.name}-app-system-status-check"
  alarm_description   = "App instance system status check failed; EC2 recover action started."
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  dimensions          = { InstanceId = var.app_instance_id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "missing"
  alarm_actions = [
    "arn:${data.aws_partition.current.partition}:automate:${data.aws_region.current.region}:ec2:recover",
    aws_sns_topic.alarms.arn,
  ]
  ok_actions = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "ec2_instance_status" {
  alarm_name          = "${var.name}-app-instance-status-check"
  alarm_description   = "App instance status check failed (OS or network inside the instance)."
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  dimensions          = { InstanceId = var.app_instance_id }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# fck-nat outage stops deploys, SSM, logs and IdP/LLM calls (architecture.md 3.3).
resource "aws_cloudwatch_metric_alarm" "nat_in_service" {
  alarm_name          = "${var.name}-fck-nat-in-service"
  alarm_description   = "No fck-nat instance in service; private subnet egress is down."
  namespace           = "AWS/AutoScaling"
  metric_name         = "GroupInServiceInstances"
  dimensions          = { AutoScalingGroupName = var.nat_autoscaling_group_name }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 5
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# RDS
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.name}-rds-cpu"
  alarm_description   = "RDS CPU above ${var.rds_cpu_percent}% for 15 minutes."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.rds_cpu_percent
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${var.name}-rds-free-storage"
  alarm_description   = "RDS free storage below ${var.rds_free_storage_bytes} bytes."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = var.rds_free_storage_bytes
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name          = "${var.name}-rds-connections"
  alarm_description   = "RDS connections above ${var.rds_max_connections}."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = var.db_instance_identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.rds_max_connections
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# ---------------------------------------------------------------------------
# CloudFront (metrics live in us-east-1 with Region=Global)
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "cloudfront_5xx" {
  region = "us-east-1"

  alarm_name          = "${var.name}-cloudfront-5xx-rate"
  alarm_description   = "CloudFront 5xx error rate above ${var.cloudfront_5xx_percent}% for 10 minutes."
  namespace           = "AWS/CloudFront"
  metric_name         = "5xxErrorRate"
  dimensions          = { DistributionId = var.distribution_id, Region = "Global" }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.cloudfront_5xx_percent
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms_us_east_1.arn]
  ok_actions          = [aws_sns_topic.alarms_us_east_1.arn]
}
