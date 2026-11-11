#!/bin/bash
# 이 process가 있는 network에서 EC2 instance metadata(IMDSv2)의 token을 받을 수 있는지 본다(ADR-0020 6번).
# 받으면 0, 못 받으면 1로 끝난다. token 값은 출력하지 않는다. 5초 안에 답이 없으면 못 받은 것으로 본다.
# 배포 문서(infra/terraform/modules/deploy/templates/deploy.sh)와 docker/smoke-test.sh가 대상 container의 network namespace에서
# 실행한다: docker run --rm --network container:<대상> --entrypoint astra-imds-probe <이미지>
set -uo pipefail

if [[ -z "${ASTRA_IMDS_PROBE_CHILD:-}" ]]; then
	ASTRA_IMDS_PROBE_CHILD=1 exec timeout 5 "$0"
fi

exec 3<>/dev/tcp/169.254.169.254/80 2>/dev/null || exit 1
printf 'PUT /latest/api/token HTTP/1.0\r\nHost: 169.254.169.254\r\nX-aws-ec2-metadata-token-ttl-seconds: 60\r\nContent-Length: 0\r\n\r\n' >&3
IFS= read -r status <&3 || exit 1
exec 3<&-
[[ "${status}" =~ ^HTTP/1\.[01]\ 200 ]]
