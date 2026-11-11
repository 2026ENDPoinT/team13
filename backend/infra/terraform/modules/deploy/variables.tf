variable "name" {
  description = "Name prefix."
  type        = string
}

variable "ecr_repository_name" {
  type = string
}

variable "ecr_keep_images" {
  description = "Number of most recent images kept in ECR."
  type        = number
}

variable "github_repository" {
  description = "GitHub repository slug (owner/name) allowed to assume the deploy role."
  type        = string
}

variable "github_refs" {
  description = "Git refs whose workflows may assume the deploy role. Ignored when github_environment is set."
  type        = list(string)
}

variable "github_environment" {
  description = "GitHub environment whose workflows may assume the deploy role. Takes precedence over github_refs."
  type        = string
  default     = null
}

variable "create_github_oidc_provider" {
  description = "Create the account-wide GitHub Actions OIDC provider. Set false if the account already has one."
  type        = bool
}

variable "deploy_target_tag" {
  description = "Tag that marks the instance the deploy role may send commands to."
  type = object({
    key   = string
    value = string
  })
}

variable "egress_bridge" {
  description = "Bridge of the compose egress network; the only one the host firewall lets reach instance metadata."
  type        = string
}

variable "app_environment" {
  description = "Non-secret environment written to /opt/astra/.env on each deploy. Values are single-quoted."
  type        = map(string)
}

variable "secrets" {
  description = "Secrets rendered into /opt/astra/.env, by id: ARN, allowed JSON keys, required flag."
  type = map(object({
    arn      = string
    keys     = list(string)
    required = bool
  }))
}
