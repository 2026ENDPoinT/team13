# syntax=docker/dockerfile:1.27.1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e
# ASTRA 백엔드 이미지. 이미지 하나를 api, worker, converter, transcoder 역할로 실행한다(ADR-0019).
# 실행할 역할은 command로 고른다: docker run <image> api | worker | converter | transcoder
# converter 역할에 office 문서 변환(LibreOffice)을 더한 이미지는 target converter로 만든다(ADR-0033):
#   docker build --target converter -t astra-backend-converter:local .
# 기본 target은 LibreOffice가 없는 runtime이다. api, worker, transcoder는 이 이미지로 실행한다.
# base 이미지와 위 syntax 줄의 BuildKit frontend는 tag와 digest로 고정한다. 판을 올릴 때는 두 값을 함께 바꾼다.

FROM eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build
WORKDIR /workspace

# 의존성 layer. build 설정이 바뀔 때만 다시 받고, 코드만 바뀌면 이 layer를 그대로 쓴다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon --quiet dependencies --configuration compileClasspath > /dev/null \
 && ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null

# 배포 코드만 빌드한다. 테스트 소스는 build context에 넣지 않는다(.dockerignore). 테스트는 CI의 gradle build가 돌린다.
COPY src/main/ src/main/
RUN ./gradlew --no-daemon --quiet bootJar \
 && cp "$(find build/libs -name '*.jar' ! -name '*-plain.jar')" app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce AS runtime

RUN groupadd --system --gid 10001 astra \
 && useradd --system --uid 10001 --gid astra --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin astra

# transcoder 역할이 부르는 ffmpeg, ffprobe(ADR-0016). 역할마다 이미지를 나누지 않는다(ADR-0019 1번). 다른 역할은 ffmpeg를
# 실행하지 않는다. /work는 transcoder의 작업 volume 자리다. /spool은 api가 multipart 업로드 본문을 임시로 받는 volume 자리다
# (compose의 SPRING_SERVLET_MULTIPART_LOCATION). compose의 named volume이 이 소유자를 물려받는다.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/* \
 && install --directory --owner=10001 --group=10001 --mode=0700 /work /spool

WORKDIR /app
# 자주 바뀌지 않는 것부터 layer로 쌓는다. 파일은 root 소유이고 실행 user는 읽기만 한다.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
COPY --chmod=0555 docker/entrypoint.sh /usr/local/bin/astra
COPY --chmod=0555 docker/healthcheck.sh /usr/local/bin/astra-health
# 배포 문서와 smoke test가 각 container의 network에서 instance metadata에 닿는지 볼 때 쓴다(ADR-0020 6번). 앱은 부르지 않는다.
COPY --chmod=0555 docker/imds-probe.sh /usr/local/bin/astra-imds-probe

# 쓰기는 /tmp에만 한다. compose는 root filesystem을 읽기 전용으로 두고 /tmp에 tmpfs를 붙인다.
ENV TMPDIR=/tmp
USER 10001:10001

# 8080: api의 HTTP API. 8090: converter. 8091: transcoder. 관리 port 8081은 container 안의 127.0.0.1에만 열리므로 적지 않는다.
EXPOSE 8080 8090 8091
ENTRYPOINT ["/usr/local/bin/astra"]
CMD ["api"]

# converter 역할 전용. LibreOffice(MPL-2.0) headless와 문서 글꼴을 더한다. api, worker 이미지에는 넣지 않는다.
# 패키지는 Ubuntu noble(universe)의 LibreOffice 24.2다. GUI가 없는 -nogui 패키지만 받고 Java 연동은 받지 않는다.
# 판을 고정하지 않으므로 이미지를 다시 만들면 보안 갱신이 들어온다.
FROM runtime AS converter
USER root
RUN apt-get update \
 && apt-get install --yes --no-install-recommends \
      libreoffice-core-nogui libreoffice-writer-nogui libreoffice-calc-nogui libreoffice-impress-nogui \
      fonts-nanum fonts-liberation2 fonts-crosextra-carlito fonts-crosextra-caladea \
 && rm -rf /var/lib/apt/lists/*
# converter main이 읽는다. 실행 파일은 고정한 인자 목록으로 shell 없이 부른다(com.stackcorp.converter.SofficeEngine).
ENV CONVERTER_SOFFICE=/usr/lib/libreoffice/program/soffice \
    CONVERTER_PRLIMIT=/usr/bin/prlimit
USER 10001:10001
CMD ["converter"]

# 기본 target. 이 줄이 마지막이어야 docker build가 LibreOffice 없는 이미지를 만든다.
FROM runtime
