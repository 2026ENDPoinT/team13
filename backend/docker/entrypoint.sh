#!/bin/bash
# 이미지 하나를 역할별로 실행한다(ADR-0019, architecture.md 4.3절). 인자는 역할 이름 하나다.
#   api        HTTP API. 8080에서 듣는다
#   worker     배경 작업(worker 프로필). HTTP API는 container 안의 127.0.0.1에만 연다
#   converter  격리한 문서 변환 process(com.stackcorp.converter.ConverterServer). 8090에서 듣는다. CONVERTER_SOFFICE가 있는
#              이미지(target converter)는 office 문서도 받는다(ADR-0033)
#   transcoder 격리한 영상 변환 process(com.stackcorp.transcoder.TranscoderServer, ffmpeg). 8091에서 듣는다
# 역할 이름은 command 인자로만 정한다. 환경 변수의 SPRING_PROFILES_ACTIVE보다 command 인자가 우선한다.
# JAVA_OPTS는 아래 기본 option 뒤에 붙으므로 같은 option을 넣으면 JAVA_OPTS의 값이 이긴다.
set -euo pipefail

# JVM 기본 시간대를 처음부터 기관 시간대로 둔다(ADR-0039). api와 worker는 시작할 때 astra.time.zone(ASTRA_TIME_ZONE)으로 다시
# 맞추고 DB session과 함께 확인한다. converter와 transcoder는 시각을 쓰지 않지만 같은 값으로 띄운다.
jvm=(
	-XX:MaxRAMPercentage=60
	-XX:+ExitOnOutOfMemoryError
	-Djava.io.tmpdir=/tmp
	"-Duser.timezone=${ASTRA_TIME_ZONE:-Asia/Seoul}"
)
# 의도적으로 공백에서 나눈다. JAVA_OPTS에는 공백이 든 값을 넣지 않는다.
# shellcheck disable=SC2206
extra=(${JAVA_OPTS:-})

case "${1:-}" in
	api)
		exec java "${jvm[@]}" "${extra[@]}" -jar /app/app.jar --spring.profiles.active=api
		;;
	worker)
		exec java "${jvm[@]}" "${extra[@]}" -jar /app/app.jar --spring.profiles.active=worker \
			--server.address=127.0.0.1
		;;
	converter)
		# PDFBox의 font cache를 읽기 전용 home이 아니라 /tmp에 둔다.
		exec java "${jvm[@]}" -Dpdfbox.fontcache=/tmp "${extra[@]}" \
			-cp /app/app.jar com.stackcorp.converter.ConverterServer
		;;
	transcoder)
		# 영상 변환 process(com.stackcorp.transcoder.TranscoderServer, ADR-0016). ffmpeg가 JVM 밖에서 메모리를 쓰므로 heap을 작게 둔다.
		# 원본과 결과는 TRANSCODER_WORK_DIR(compose에서 /work volume)에 요청마다 만들고 지운다.
		exec java "${jvm[@]}" -XX:MaxRAMPercentage=20 "${extra[@]}" \
			-cp /app/app.jar com.stackcorp.transcoder.TranscoderServer
		;;
	*)
		echo "usage: astra api|worker|converter|transcoder" >&2
		exit 64
		;;
esac
