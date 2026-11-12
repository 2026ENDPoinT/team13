#!/bin/bash
# Renders /opt/astra/.env from Terraform-managed settings and Secrets Manager values,
# then pulls the requested image and restarts the compose project.
# {{ ImageTag }} is substituted by SSM after the document's allowedPattern check.
# The converter role runs the Dockerfile target converter, pushed to the same repository as <ImageTag>-converter.
# After the restart it checks that only api and worker can get an instance metadata token (ADR-0020 6).
set -euo pipefail
umask 077

cd /opt/astra
if [ ! -f compose.yaml ]; then
  echo "/opt/astra/compose.yaml is missing" >&2
  exit 1
fi

region='${region}'
registry='${registry}'
image='${repository_url}:{{ ImageTag }}'
converter_image='${repository_url}:{{ ImageTag }}-converter'

env_tmp=$(mktemp /opt/astra/.env.XXXXXX)
err_tmp=$(mktemp)
trap 'rm -f "$env_tmp" "$err_tmp"' EXIT

cat > "$env_tmp" <<'CONFIG'
${config_lines}
CONFIG
printf "ASTRA_IMAGE='%s'\n" "$image" >> "$env_tmp"
printf "ASTRA_CONVERTER_IMAGE='%s'\n" "$converter_image" >> "$env_tmp"

# stdin: secret value (JSON object). $1: JSON array of allowed keys.
# Single-line values are single-quoted (literal in compose env files).
# Multi-line values (PEM) are double-quoted with \n escapes.
render() {
  jq -r --argjson allowed "$1" -f /dev/fd/3 3<<'JQ'
if type != "object" then error("secret value must be a JSON object") else . end
| to_entries[]
| if (.key as $k | $allowed | index($k)) == null then error("key not allowed: " + .key) else . end
| if (.value | type) != "string" then error("value of " + .key + " must be a string")
  elif (.value | test("\n")) then
    if (.value | test("[\"\\\\$`]")) then error("multi-line value of " + .key + " must not contain quote, backslash, backtick or dollar")
    else .key + "=\"" + (.value | gsub("\r"; "") | gsub("\n"; "\\n")) + "\"" end
  elif (.value | test("'")) then error("value of " + .key + " must not contain a single quote")
  else .key + "='" + .value + "'" end
JQ
}

# $1: secret ARN, $2: allowed keys, $3: required|optional
fetch() {
  local value
  if value=$(aws secretsmanager get-secret-value --region "$region" --secret-id "$1" \
      --query SecretString --output text 2>"$err_tmp"); then
    printf '%s' "$value" | render "$2" >> "$env_tmp"
  elif [ "$3" = optional ] && grep -q ResourceNotFoundException "$err_tmp"; then
    echo "no value in $1; skipped" >&2
  else
    cat "$err_tmp" >&2
    exit 1
  fi
}

${fetch_lines}

duplicates=$(cut -d= -f1 "$env_tmp" | sort | uniq -d)
if [ -n "$duplicates" ]; then
  echo "duplicate keys in rendered env: $duplicates" >&2
  exit 1
fi

chmod 0600 "$env_tmp"
mv "$env_tmp" .env

# The host firewall must keep instance metadata away from every container except api and worker before any container
# starts (modules/app user data). An instance bootstrapped without it is replaced, not deployed to.
if ! systemctl is-active --quiet astra-imds-guard.service \
    || ! iptables -w -C DOCKER-USER -d 169.254.169.254/32 ! -i '${egress_bridge}' -j DROP 2>/dev/null; then
  echo "instance metadata guard is not active on this host; replace the instance (infra/README.md section 6)" >&2
  exit 1
fi

aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry"
docker compose pull
docker compose up -d --wait --remove-orphans

# Probe instance metadata from the network namespace of every running container with the app image.
# api and worker must get a token (S3, KMS through S3); any other container getting one fails the deploy.
imds_probe() {
  docker run --rm --pull never --network "container:$(docker compose ps --quiet "$1")" --read-only --cap-drop ALL \
    --security-opt no-new-privileges:true --user 10001:10001 --entrypoint astra-imds-probe "$image"
}
for service in $(docker compose ps --services --status running); do
  case "$service" in
    api | worker)
      imds_probe "$service" || { echo "$service cannot get an instance metadata token" >&2; exit 1; }
      ;;
    *)
      if imds_probe "$service"; then
        echo "$service got an instance metadata token; stopping it" >&2
        docker compose stop "$service"
        exit 1
      fi
      ;;
  esac
done

docker image prune -af --filter until=168h
