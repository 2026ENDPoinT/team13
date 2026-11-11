# syntax=docker/dockerfile:1
# ASTRA 백엔드 이미지. 이미지 하나를 api, worker, converter, transcoder 역할로 실행한다(ADR-0019).
# 실행할 역할은 command로 고른다: docker run <image> api | worker | converter | transcoder
# base 이미지는 tag와 digest로 고정한다. 판을 올릴 때는 두 값을 함께 바꾼다.

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

FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce

RUN groupadd --system --gid 10001 astra \
 && useradd --system --uid 10001 --gid astra --home-dir /nonexistent --no-create-home --shell /usr/sbin/nologin astra

# transcoder 역할이 부르는 ffmpeg, ffprobe(ADR-0016). 역할마다 이미지를 나누지 않는다(ADR-0019 1번). 다른 역할은 ffmpeg를
# 실행하지 않는다. /work는 transcoder의 작업 volume 자리다. compose의 named volume이 이 소유자를 물려받는다.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/* \
 && install --directory --owner=10001 --group=10001 --mode=0700 /work

WORKDIR /app
# 자주 바뀌지 않는 것부터 layer로 쌓는다. 파일은 root 소유이고 실행 user는 읽기만 한다.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
COPY --chmod=0555 docker/entrypoint.sh /usr/local/bin/astra
COPY --chmod=0555 docker/healthcheck.sh /usr/local/bin/astra-health

# 쓰기는 /tmp에만 한다. compose는 root filesystem을 읽기 전용으로 두고 /tmp에 tmpfs를 붙인다.
ENV TMPDIR=/tmp
USER 10001:10001

# 8080: api의 HTTP API. 8090: converter. 8091: transcoder. 관리 port 8081은 container 안의 127.0.0.1에만 열리므로 적지 않는다.
EXPOSE 8080 8090 8091
ENTRYPOINT ["/usr/local/bin/astra"]
CMD ["api"]
