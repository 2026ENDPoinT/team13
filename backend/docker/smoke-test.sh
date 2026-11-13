#!/bin/bash
# compose 구성을 띄워 경계와 기동을 확인한다. CI(.github/workflows/ci.yml)와 로컬에서 같은 절차로 쓴다.
# 전제: 이미지 ${ASTRA_IMAGE:-astra-backend:local}이 있고, 저장소 root에 .env가 있으며 COMPOSE_PROFILES에 mysql이 있다. host에 curl과 jq가 있다.
# COMPOSE_PROFILES에 scan이 있으면 clamav와 freshclam의 network 경계도 실제로 확인한다. 없으면 구성 파일로만 확인한다.
# 끝나도 container를 내리지 않는다. 정리는 docker compose down --volumes로 한다. 마지막 단계가 api와 worker를 멈추므로 운영 host에서
# 실행하지 않는다. 운영 host의 instance metadata 확인(12번)은 배포 문서가 배포할 때마다 한다.
set -euo pipefail
cd "$(dirname "$0")/.."

fail() {
	echo "FAIL: $*" >&2
	exit 1
}

pass() {
	echo "ok: $*"
}

# 0. 허용 Origin이 없으면 compose가 구성을 거부하고 무엇이 빠졌는지 알린다. shell 환경 변수가 .env보다 앞선다.
if missing="$(ASTRA_SESSION_ALLOWED_ORIGINS='' docker compose config --quiet 2>&1)"; then
	fail "compose accepted an empty ASTRA_SESSION_ALLOWED_ORIGINS"
fi
grep -q 'ASTRA_SESSION_ALLOWED_ORIGINS' <<<"${missing}" || fail "compose refused the config without naming ASTRA_SESSION_ALLOWED_ORIGINS: ${missing}"
pass "compose refuses to start api without ASTRA_SESSION_ALLOWED_ORIGINS"

# 1. 모든 서비스가 healthcheck를 통과할 때까지 기다린다.
docker compose up --detach --wait --wait-timeout 420 || {
	docker compose ps
	fail "services did not become healthy"
}
pass "api, worker, converter, transcoder, mysql are healthy"

api_port="$(docker compose port api 8080)"
[[ "${api_port}" == 127.0.0.1:* ]] || fail "api port is published on ${api_port}, expected 127.0.0.1"
pass "api port published only on loopback (${api_port})"

# 2. 공개 port는 api 하나다. MySQL, converter, transcoder, 관리 port는 host에 열리지 않는다.
for target in "mysql 3306" "converter 8090" "transcoder 8091" "api 8081" "worker 8081" "worker 8080"; do
	# shellcheck disable=SC2086
	# 열리지 않은 port는 compose 판에 따라 오류, 빈 줄, ":0", "invalid IP:0"으로 나온다.
	if published="$(docker compose port ${target} 2>/dev/null)" && [[ -n "${published}" && "${published}" != *":0" ]]; then
		fail "${target} is published on ${published}"
	fi
done
pass "mysql, converter, transcoder and management ports are not published"

# 3. api container를 거쳐 CSRF token을 받는다.
headers="$(curl --silent --show-error --fail --max-time 10 --dump-header - --output /dev/null "http://${api_port}/api/auth/csrf")" \
	|| fail "GET /api/auth/csrf failed"
grep -qi '^set-cookie: XSRF-TOKEN=' <<<"${headers}" || fail "GET /api/auth/csrf did not set XSRF-TOKEN"
pass "GET /api/auth/csrf answers through the api container"

# 3-1. 프론트엔드 서버(Frontend-final server.mjs)를 거친 상태 변경 요청. 그 proxy는 Host를 api 주소로 바꾸고, 브라우저의 Origin과
#      Sec-Fetch-Site를 그대로 넘기며 X-Forwarded-Host·Proto를 붙인다. 여기서 curl이 보내는 Host(127.0.0.1:port)가 그 바뀐 Host다.
#      허용 Origin에서 온 요청은 Origin·CSRF 검사를 지나 인증 단계의 401이 되고, 다른 Origin은 X-Forwarded-Host를 맞춰도 403이다.
frontend_origin="$(docker compose exec -T api env | sed -n 's/^ASTRA_SESSION_ALLOWED_ORIGINS=//p' | cut -d, -f1 | tr -d '\r ')"
[[ -n "${frontend_origin}" ]] || fail "api has no ASTRA_SESSION_ALLOWED_ORIGINS"
xsrf="$(sed -n 's/^[Ss]et-[Cc]ookie: XSRF-TOKEN=\([^;]*\).*/\1/p' <<<"${headers}" | head -n 1 | tr -d '\r')"
through_frontend_proxy() {
	curl --silent --max-time 10 --output "$2" --write-out '%{http_code}' --request POST \
		--header "Origin: $1" --header 'Sec-Fetch-Site: same-origin' --header "X-Forwarded-Host: ${1#*://}" \
		--header 'X-Forwarded-Proto: https' --header "X-XSRF-TOKEN: ${xsrf}" --cookie "XSRF-TOKEN=${xsrf}" \
		"http://${api_port}/api/auth/logout"
}
body="$(mktemp)"
status="$(through_frontend_proxy "${frontend_origin}" "${body}")"
[[ "${status}" == 401 ]] || fail "POST from ${frontend_origin} through a Host-rewriting proxy answered ${status}, expected 401: $(cat "${body}")"
status="$(through_frontend_proxy "https://evil.example" "${body}")"
[[ "${status}" == 403 ]] && jq -e '.detail | contains("출처")' "${body}" >/dev/null \
	|| fail "POST from another origin answered ${status}, expected 403 from the origin check: $(cat "${body}")"
rm -f "${body}"
pass "a mutating request from ${frontend_origin} passes the origin check behind the proxy; another origin is refused"

# 3-2. /doc, /vod 전달 cookie 서명이 없으면 api가 시작하지 않는다(ADR-0009). 1번에서 api가 떴으므로 서명이 있다.
docker compose exec -T api env | grep -qx 'ASTRA_DELIVERY_REQUIRED=true' || fail "api does not require a /doc, /vod signer"
pass "api started with a /doc, /vod cookie signer"

# 4. /actuator는 공개 port의 경로에 없다.
status="$(curl --silent --output /dev/null --max-time 10 --write-out '%{http_code}' "http://${api_port}/actuator/health")"
[[ "${status}" != 200 ]] || fail "/actuator/health answered 200 on the public port"
pass "/actuator/health is not served on the public port (${status})"

# 5. converter와 transcoder는 외부로 나가지 못하고, worker만 각각에 닿는다. api의 외부 연결은 대조군이다.
probe() {
	docker compose exec -T "$1" timeout 5 bash -c "exec 3<>/dev/tcp/$2/$3" >/dev/null 2>&1
}
probe api 1.1.1.1 443 || fail "control: api cannot reach the internet, network check is inconclusive"
for isolated in converter transcoder; do
	if probe "${isolated}" 1.1.1.1 443; then
		fail "${isolated} reached the internet"
	fi
done
pass "converter and transcoder have no outbound network"
probe worker converter 8090 || fail "worker cannot reach converter"
probe worker transcoder 8091 || fail "worker cannot reach transcoder"
if probe api converter 8090; then
	fail "api reached converter"
fi
if probe api transcoder 8091; then
	fail "api reached transcoder"
fi
if probe converter transcoder 8091; then
	fail "converter reached transcoder"
fi
pass "only worker reaches converter and transcoder"

# 6. compose 구성의 network 경계(ADR-0019 4번). scan 프로필을 켜지 않아도 구성 파일로 본다.
#    egress(외부, instance metadata 허용)에는 api와 worker만, signatures에는 freshclam만 있다. clamav는 worker와만 만난다.
config="$(docker compose --profile mysql --profile scan config --format json)"
members() {
	jq -r --arg n "$1" '[.services | to_entries[] | select((.value.networks // {}) | has($n)) | .key] | sort | join(" ")' <<<"${config}"
}
for expected in "egress:api worker" "signatures:freshclam" "data:api mysql worker" "conversion:converter worker" \
	"transcoding:transcoder worker" "scanning:clamav worker"; do
	network="${expected%%:*}"
	actual="$(members "${network}")"
	[[ "${actual}" == "${expected#*:}" ]] || fail "network ${network} has [${actual}], expected [${expected#*:}]"
done
for network in data conversion transcoding scanning; do
	[[ "$(jq -r --arg n "${network}" '.networks[$n].internal // false' <<<"${config}")" == true ]] \
		|| fail "network ${network} is not internal"
done
[[ "$(jq -r '.networks.egress.driver_opts["com.docker.network.bridge.name"] // ""' <<<"${config}")" == astra-egress ]] \
	|| fail "egress bridge is not named astra-egress; the host firewall allows instance metadata by that name"
pass "egress holds only api and worker, clamav shares a network only with worker, freshclam is alone"

# 7. 이미지에 bash가 없을 수 있는 container(clamav, freshclam)는 같은 network namespace에 붙인 앱 이미지로 확인한다.
app_image="$(docker inspect --format '{{.Config.Image}}' "$(docker compose ps --quiet api)")"
in_network_of() {
	docker run --rm --pull never --network "container:$(docker compose ps --quiet "$1")" --read-only --cap-drop ALL \
		--security-opt no-new-privileges:true --user 10001:10001 --entrypoint "$2" "${app_image}" "${@:3}"
}
probe_from() {
	in_network_of "$1" timeout 5 bash -c "exec 3<>/dev/tcp/$2/$3" >/dev/null 2>&1
}
if docker compose ps --services --status running | grep -qx clamav; then
	probe worker clamav 3310 || fail "worker cannot reach clamav"
	for client in api freshclam; do
		if probe_from "${client}" clamav 3310; then
			fail "${client} reached clamav"
		fi
	done
	for target in "1.1.1.1 443" "api 8080" "mysql 3306"; do
		# shellcheck disable=SC2086
		if probe_from clamav ${target}; then
			fail "clamav reached ${target}"
		fi
	done
	if probe_from freshclam api 8080; then
		fail "freshclam reached api"
	fi
	pass "only worker reaches clamav; clamav has no outbound network, freshclam reaches no other container"
else
	echo "skip: scan profile is not running; clamav and freshclam reachability checked only in the compose file"
fi

# 8. converter와 transcoder에는 DB, 저장소, 모델 설정이 없다. transcoder에는 ffmpeg가 있다.
for isolated in converter transcoder; do
	if docker compose exec -T "${isolated}" env | grep -E '^(DB_|ASTRA_|AWS_|SPRING_)'; then
		fail "${isolated} received application settings or secrets"
	fi
done
docker compose exec -T transcoder ffmpeg -hide_banner -version >/dev/null || fail "transcoder has no ffmpeg"
docker compose exec -T transcoder bash -c 'echo x > /work/probe && rm /work/probe' \
	|| fail "transcoder cannot write to its work volume"
pass "converter and transcoder have no secrets; transcoder has ffmpeg and a writable /work"

# 9. 실행 user와 파일 시스템. /var/tmp는 보통 누구나 쓸 수 있으므로 root filesystem이 읽기 전용인지 여기서 본다.
for service in api worker converter transcoder; do
	uid="$(docker compose exec -T "${service}" id -u)"
	[[ "${uid}" != 0 ]] || fail "${service} runs as root"
	if docker compose exec -T "${service}" bash -c 'echo x > /var/tmp/probe' >/dev/null 2>&1; then
		fail "${service} can write to its root filesystem"
	fi
	docker compose exec -T "${service}" bash -c 'echo x > /tmp/probe' || fail "${service} cannot write to /tmp"
done
pass "containers run as non-root on a read-only root filesystem with a writable /tmp"

# 10. 로그는 JSON 한 줄이고 query string을 남기지 않는다.
curl --silent --output /dev/null --max-time 10 "http://${api_port}/api/auth/csrf?probe=smoke-query-value"
sleep 1
logs="$(docker compose logs --no-color --no-log-prefix api)"
grep -q '"message":"request method=GET route=/api/auth/csrf status=204' <<<"${logs}" || fail "no JSON request log line"
if grep -q 'smoke-query-value' <<<"${logs}"; then
	fail "query string was logged"
fi
pass "request log is JSON without query string"

# 11. LibreOffice는 converter 이미지에만 있고, converter가 office 문서를 쪽 이미지로 바꾼다(ADR-0033).
#     표본 docx는 converter container 안의 LibreOffice로 만들고, 같은 container의 8090에 보내 쪽 zip을 받는지 본다.
for service in api worker; do
	if docker compose exec -T "${service}" test -e /usr/lib/libreoffice/program/soffice; then
		fail "${service} image contains LibreOffice"
	fi
done
docker compose exec -T converter bash -c '
	set -euo pipefail
	work="$(mktemp -d)"
	trap "rm -rf \"${work}\"" EXIT
	printf "ASTRA smoke\n" > "${work}/sample.txt"
	HOME="${work}" /usr/lib/libreoffice/program/soffice --headless --convert-to docx --outdir "${work}" \
		"${work}/sample.txt" > /dev/null 2>&1
	exec 3<>/dev/tcp/127.0.0.1/8090
	printf "POST /render?maxPages=5&format=docx HTTP/1.1\r\nHost: converter\r\nContent-Type: application/octet-stream\r\nContent-Length: %s\r\nConnection: close\r\n\r\n" \
		"$(stat -c %s "${work}/sample.docx")" >&3
	cat "${work}/sample.docx" >&3
	cat <&3 > "${work}/response"
	head -n 1 "${work}/response" | grep -q "^HTTP/1.1 200"
	grep -aq "document.json" "${work}/response"
	! grep -aq "error.json" "${work}/response"
' || fail "converter could not turn a docx into pages"
pass "only the converter has LibreOffice and it converts a docx into pages"

# 12. instance metadata(IAM role credential)의 token은 api와 worker만 받는다(ADR-0020 6번). 각 container의 network namespace에서
#     token을 요청한다. host가 token을 받지 못하면 EC2가 아니므로 건너뛴다.
if curl --silent --fail --max-time 2 --request PUT --header 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
	--output /dev/null http://169.254.169.254/latest/api/token; then
	for service in $(docker compose ps --services --status running); do
		case "${service}" in
			api | worker)
				in_network_of "${service}" astra-imds-probe || fail "${service} cannot get an instance metadata token"
				;;
			*)
				if in_network_of "${service}" astra-imds-probe; then
					fail "${service} got an instance metadata token"
				fi
				;;
		esac
	done
	pass "only api and worker get an instance metadata token"
else
	echo "skip: this host has no EC2 instance metadata service; token isolation not checked"
fi

# 13. 종료 신호를 받으면 graceful shutdown을 마치고 끝난다(137 = 강제 종료).
docker compose stop api worker
for service in api worker; do
	code="$(docker inspect --format '{{.State.ExitCode}}' "$(docker compose ps --all --quiet "${service}")")"
	[[ "${code}" != 137 ]] || fail "${service} was killed before it finished shutting down"
done
docker compose logs --no-color --no-log-prefix api | grep -q 'Graceful shutdown complete' \
	|| fail "api did not log a completed graceful shutdown"
pass "api and worker stop gracefully"
