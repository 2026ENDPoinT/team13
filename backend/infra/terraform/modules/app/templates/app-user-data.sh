#!/bin/bash
# Bootstrap of the app host: docker engine, docker compose plugin, container logs to CloudWatch Logs,
# and the firewall rule that keeps instance metadata (role credentials) away from containers other than api and worker.
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

# Instance metadata guard (ADR-0020 6). The hop limit of 2 lets every container on a routed bridge reach IMDS.
# Forwarded packets to 169.254.169.254 pass only when they enter from the compose egress bridge (api and worker,
# compose.yaml networks.egress); everything else (freshclam, docker0, any later network) is dropped.
# The host's own requests (docker awslogs, deploy document, SSM agent) are not forwarded and are unaffected.
# The rule is put into DOCKER-USER before docker starts: docker keeps that chain's rules and jumps to it from FORWARD
# first, so no container starts without it, also after a reboot.
cat > /usr/local/sbin/astra-imds-guard <<'SH'
#!/bin/bash
set -euo pipefail
iptables -w -N DOCKER-USER 2>/dev/null || true
if ! iptables -w -C DOCKER-USER -d 169.254.169.254/32 ! -i ${egress_bridge} -j DROP 2>/dev/null; then
  iptables -w -I DOCKER-USER 1 -d 169.254.169.254/32 ! -i ${egress_bridge} -j DROP
fi
SH
chmod 0755 /usr/local/sbin/astra-imds-guard
cat > /etc/systemd/system/astra-imds-guard.service <<'UNIT'
[Unit]
Description=Allow instance metadata only from the compose egress bridge
Before=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/sbin/astra-imds-guard

[Install]
RequiredBy=docker.service
UNIT
systemctl daemon-reload
systemctl enable --now astra-imds-guard.service

systemctl enable --now docker

# compose.yaml is placed here by the operator; .env is rendered by each deploy (root only).
install -d -m 0700 /opt/astra
