#!/bin/bash
# compose 구성을 띄워 경계와 기동을 확인한다. CI(.github/workflows/ci.yml)와 로컬에서 같은 절차로 쓴다.
# 전제: 이미지 ${ASTRA_IMAGE:-astra-backend:local}이 있고, 저장소 root에 .env가 있으며 COMPOSE_PROFILES에 mysql이 있다.
# 끝나도 container를 내리지 않는다. 정리는 docker compose down --volumes로 한다.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
	echo "FAIL: $*" >&2
	exit 1
}

pass() {
	echo "ok: $*"
}

# 1. 모든 서비스가 healthcheck를 통과할 때까지 기다린다.
docker compose up --detach --wait --wait-timeout 420 || {
	docker compose ps
	fail "services did not become healthy"
}
pass "api, worker, converter, mysql are healthy"

api_port="$(docker compose port api 8080)"
[[ "${api_port}" == 127.0.0.1:* ]] || fail "api port is published on ${api_port}, expected 127.0.0.1"
pass "api port published only on loopback (${api_port})"

# 2. 공개 port는 api 하나다. MySQL, converter, 관리 port는 host에 열리지 않는다.
for target in "mysql 3306" "converter 8090" "api 8081" "worker 8081" "worker 8080"; do
	# shellcheck disable=SC2086
	if published="$(docker compose port ${target} 2>/dev/null)" && [[ -n "${published}" && "${published}" != ":0" ]]; then
		fail "${target} is published on ${published}"
	fi
done
pass "mysql, converter and management ports are not published"

# 3. api container를 거쳐 CSRF token을 받는다.
headers="$(curl --silent --show-error --fail --max-time 10 --dump-header - --output /dev/null "http://${api_port}/api/auth/csrf")" \
	|| fail "GET /api/auth/csrf failed"
grep -qi '^set-cookie: XSRF-TOKEN=' <<<"${headers}" || fail "GET /api/auth/csrf did not set XSRF-TOKEN"
pass "GET /api/auth/csrf answers through the api container"

# 4. /actuator는 공개 port의 경로에 없다.
status="$(curl --silent --output /dev/null --max-time 10 --write-out '%{http_code}' "http://${api_port}/actuator/health")"
[[ "${status}" != 200 ]] || fail "/actuator/health answered 200 on the public port"
pass "/actuator/health is not served on the public port (${status})"

# 5. converter는 외부로 나가지 못하고, worker만 converter에 닿는다. api의 외부 연결은 대조군이다.
probe() {
	docker compose exec -T "$1" timeout 5 bash -c "exec 3<>/dev/tcp/$2/$3" >/dev/null 2>&1
}
probe api 1.1.1.1 443 || fail "control: api cannot reach the internet, network check is inconclusive"
if probe converter 1.1.1.1 443; then
	fail "converter reached the internet"
fi
pass "converter has no outbound network"
probe worker converter 8090 || fail "worker cannot reach converter"
if probe api converter 8090; then
	fail "api reached converter"
fi
pass "only worker reaches converter"

# 6. converter에는 DB, 저장소, 모델 설정이 없다.
if docker compose exec -T converter env | grep -E '^(DB_|ASTRA_|AWS_|SPRING_)'; then
	fail "converter received application settings or secrets"
fi
pass "converter has no secrets"

# 7. 실행 user와 파일 시스템. /var/tmp는 보통 누구나 쓸 수 있으므로 root filesystem이 읽기 전용인지 여기서 본다.
for service in api worker converter; do
	uid="$(docker compose exec -T "${service}" id -u)"
	[[ "${uid}" != 0 ]] || fail "${service} runs as root"
	if docker compose exec -T "${service}" bash -c 'echo x > /var/tmp/probe' >/dev/null 2>&1; then
		fail "${service} can write to its root filesystem"
	fi
	docker compose exec -T "${service}" bash -c 'echo x > /tmp/probe' || fail "${service} cannot write to /tmp"
done
pass "containers run as non-root on a read-only root filesystem with a writable /tmp"

# 8. 로그는 JSON 한 줄이고 query string을 남기지 않는다.
curl --silent --output /dev/null --max-time 10 "http://${api_port}/api/auth/csrf?probe=smoke-query-value"
sleep 1
logs="$(docker compose logs --no-color --no-log-prefix api)"
grep -q '"message":"request method=GET route=/api/auth/csrf status=204' <<<"${logs}" || fail "no JSON request log line"
if grep -q 'smoke-query-value' <<<"${logs}"; then
	fail "query string was logged"
fi
pass "request log is JSON without query string"

# 9. 종료 신호를 받으면 graceful shutdown을 마치고 끝난다(137 = 강제 종료).
docker compose stop api worker
for service in api worker; do
	code="$(docker inspect --format '{{.State.ExitCode}}' "$(docker compose ps --all --quiet "${service}")")"
	[[ "${code}" != 137 ]] || fail "${service} was killed before it finished shutting down"
done
docker compose logs --no-color --no-log-prefix api | grep -q 'Graceful shutdown complete' \
	|| fail "api did not log a completed graceful shutdown"
pass "api and worker stop gracefully"
