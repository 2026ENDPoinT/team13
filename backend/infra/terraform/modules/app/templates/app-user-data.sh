#!/bin/bash
# Bootstrap of the app host: docker engine, docker compose plugin, container logs to CloudWatch Logs.
# The application itself is started by the deploy SSM document (modules/deploy).
set -euo pipefail

dnf install -y docker jq

install -d -m 0755 /usr/libexec/docker/cli-plugins
curl -fsSL -o /tmp/docker-compose \
  "https://github.com/docker/compose/releases/download/${docker_compose_version}/docker-compose-linux-${docker_compose_arch}"
echo "${docker_compose_sha256}  /tmp/docker-compose" | sha256sum -c -
install -m 0755 /tmp/docker-compose /usr/libexec/docker/cli-plugins/docker-compose
rm -f /tmp/docker-compose

install -d -m 0755 /etc/docker
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "awslogs",
  "log-opts": {
    "awslogs-region": "${region}",
    "awslogs-group": "${log_group}",
    "tag": "{{.Name}}/{{.ID}}"
  }
}
JSON

systemctl enable --now docker

# compose.yaml is placed here by the operator; .env is rendered by each deploy (root only).
install -d -m 0700 /opt/astra
