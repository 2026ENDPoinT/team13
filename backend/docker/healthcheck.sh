#!/bin/bash
# container 안에서 127.0.0.1의 HTTP 경로가 200으로 답하는지 본다. 이미지에 curl을 넣지 않으려고 bash의 /dev/tcp를 쓴다.
# 사용: astra-health <port> <path>
#   api, worker: astra-health 8081 /actuator/health/readiness
#   converter:   astra-health 8092 /health (변환 port 8090이 아니라 상태 확인 전용 port)
set -euo pipefail

port="${1:?port}"
path="${2:?path}"

exec 3<>"/dev/tcp/127.0.0.1/${port}"
printf 'GET %s HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' "${path}" >&3
IFS= read -r -t 5 status <&3
exec 3<&-
[[ "${status}" =~ ^HTTP/1\.[01]\ 200 ]]
