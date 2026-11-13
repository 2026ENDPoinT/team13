output "alarm_topic_arns" {
  description = "SNS topics for alarms: regional and us-east-1 (CloudFront)."
  value = {
    regional  = aws_sns_topic.alarms.arn
    us_east_1 = aws_sns_topic.alarms_us_east_1.arn
  }
}
