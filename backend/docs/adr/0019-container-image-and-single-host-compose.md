# ADR-0019 이미지 하나를 역할로 나눠 실행하고, health는 container 안의 관리 port로만 연다

## 상태
채택 (2026-11-11)

## 맥락
[ADR-0001](0001-modular-monolith.md)은 같은 이미지를 `api`, `worker` 프로필로 나눠 실행하기로 했고, [ADR-0003](0003-aws-single-ec2-until-adoption.md)은 학교 도입 전까지 EC2 1대에서 docker compose로 운영하기로 했다. [ADR-0006](0006-summary-module.md)은 문서 변환을 외부 network와 credential이 없는 converter container에 맡겼다. 같은 jar에 converter main(`com.stackcorp.converter.ConverterServer`)이 있다. 이 결정들을 실행할 이미지와 compose 구성이 없었다.

정할 것은 두 가지였다.

- 이미지: 역할마다 이미지를 따로 만들지, 하나를 역할로 나눠 쓸지.
- health: compose의 healthcheck와 이후 ALB가 부를 상태 확인을 어디에 열지. [architecture.md](../architecture.md) 3.3절은 `/actuator`를 CloudFront 경로에 넣지 않고 내부에서만 접근하게 했다.

| 안 | 장점 | 단점 |
|---|---|---|
| 역할마다 이미지(api·worker 이미지와 converter 전용 이미지) | converter 이미지에서 Spring, AWS SDK, JDBC driver를 뺄 수 있다 | 빌드·서명·배포 대상이 늘고, 같은 commit의 이미지끼리 판이 어긋날 수 있다. converter 전용 classpath를 Gradle에서 따로 관리해야 한다 |
| 이미지 하나, command로 역할 선택 | 배포 단위가 commit 하나에 이미지 하나다. ADR-0001 5번과 같다 | converter container에 쓰지 않는 library가 들어 있다 |
| health를 API port의 `/actuator/health`로 열고 CloudFront 경로에서만 뺀다 | 설정이 적다 | API port에 닿는 모든 것(같은 host의 다른 container, 이후 ALB 앞단 설정 실수)이 actuator 경로를 본다. 경로 하나를 막는 일이 앱 밖 설정에 달린다 |
| 관리 port를 따로 두고 host에 publish한다 | host의 감시 도구가 바로 부를 수 있다 | 공개 port가 둘이 된다 |
| 관리 port를 따로 두고 container 안의 127.0.0.1에만 연다 | 공개 port는 API 하나다. API port에는 `/actuator`가 아예 없다 | host나 ALB에서 직접 부르려면 설정을 바꿔야 한다 |

## 결정
1. 이미지는 하나다. entrypoint가 첫 인자로 역할을 고른다. `api`는 `api` 프로필, `worker`는 `worker` 프로필로 Spring 앱을 띄우고, `converter`는 같은 jar의 converter main을, `transcoder`는 영상 transcoder main([ADR-0016](0016-video-upload-and-transcoder.md))을 띄운다. 역할은 command 인자로만 정하고 환경 변수의 프로필 설정보다 우선한다. transcoder가 부르는 ffmpeg도 이 이미지에 넣는다. 역할별 이미지로 나누지 않기 위해서이고, 다른 역할은 ffmpeg를 실행하지 않는다.
2. 이미지는 multi-stage로 만든다. 빌드 stage는 Gradle wrapper와 JDK 25로 bootJar를 만들고, 실행 stage는 JRE 25 위에 Spring Boot의 layer(의존성, loader, snapshot 의존성, 앱)를 차례로 복사한다. base 이미지는 tag와 digest로 고정한다. 실행 user는 uid 10001이고 파일은 root 소유라 읽기만 한다. root filesystem은 읽기 전용으로 두고 `/tmp`에만 tmpfs를 붙인다.
3. actuator는 `health`만 켠다. 관리 port 8081을 따로 두고 `127.0.0.1`에 묶는다. 응답에는 상태만 있다. readiness는 DB 연결까지, liveness는 process 상태만 본다. compose의 healthcheck는 container 안에서 readiness를 부른다. worker도 API를 container 안의 127.0.0.1에만 연다.
4. compose는 network로 경계를 나눈다. 외부로 나가는 `egress`에는 api와 worker만 붙는다. 외부로 나가는 `signatures`에는 freshclam 하나만 있다. 외부로 나갈 수 없는 `data`에는 api, worker, mysql이, `conversion`에는 worker와 converter만, `transcoding`에는 worker와 transcoder만, `scanning`에는 worker와 clamav만 붙는다. 신뢰할 수 없는 파일을 읽는 converter, transcoder, clamd는 worker와만 공유하는 내부 network 하나에만 있어 외부, EC2 instance metadata(IAM role credential), MySQL, api에 닿지 않고 서로에게도 닿지 않는다. freshclam은 서명 volume 말고는 다른 container와 나누는 것이 없다. EC2에서 instance metadata는 `egress`에서 온 트래픽만 받는다([ADR-0020](0020-terraform-layout.md) 6번). converter와 transcoder는 env file을 받지 않는다. publish하는 port는 api의 8080 하나이고 기본은 host의 127.0.0.1이다.
5. 비밀값은 커밋하지 않는 `.env` 하나로 받는다. api와 worker는 같은 host, 같은 instance role의 신뢰 경계에 있으므로 같은 파일을 받는다. 값이 빈 줄은 두지 않는다. 일부 설정은 빈 값도 "설정됨"으로 보기 때문이다. 빠지면 일부 기능만 조용히 멈추는 설정은 compose가 요구한다. 허용 Origin(`ASTRA_SESSION_ALLOWED_ORIGINS`)이 없으면 compose가 구성을 거부하고, api에는 `ASTRA_DELIVERY_REQUIRED=true`를 넣어 `/doc`, `/vod` 서명이 없으면 시작하지 않게 한다([ADR-0004](0004-sso-bff-session.md) 4번, [ADR-0009](0009-origin-delivery-without-cdn.md) 8번).
6. 같은 host의 MySQL은 compose 프로필 `mysql`로 띄우고, RDS를 쓰면 이 프로필을 끈다. ClamAV는 프로필 `scan`이고, 검사하는 clamd와 서명을 받는 freshclam을 다른 container로 나눠 같은 서명 volume을 쓴다.

## 결과와 트레이드오프
- converter container에도 Spring, AWS SDK, JDBC driver가 들어 있다. converter에는 network와 credential이 없어서 이 library가 할 수 있는 일이 없다고 본다. converter 이미지를 줄여야 하면 converter main을 Gradle source set으로 나누는 안을 다시 연다.
- 관리 port가 container 밖에 없으므로 학교 도입 구성(architecture.md 3.2절)에서 ALB target group health check를 붙일 때 `management.server.address`를 바꾸고 security group으로 ALB에서만 열어야 한다.
- 이미지에 curl을 넣지 않았다. healthcheck는 bash의 `/dev/tcp`로 HTTP 상태 줄만 본다.
- api와 worker가 같은 env file을 받으므로 worker도 break-glass와 CloudFront 서명 key를 환경 변수로 갖는다. 역할마다 파일을 나누면 운영자가 관리할 파일이 늘어 이 단계에서는 하지 않는다.
- JDK AOT cache(architecture.md 3.1절)는 아직 이미지에 넣지 않았다. training run에 DB가 필요해 이미지 빌드 안에서 만들 수 없다.
- 이미지에 instance metadata token을 요청해 보는 `astra-imds-probe`를 넣었다. 앱은 부르지 않고, 배포 문서와 smoke test가 각 container의 network namespace에 붙여 실행한다. ClamAV 이미지에 bash가 없을 수 있어 대상 container 안이 아니라 앱 이미지로 확인한다.
- 보안 검토(L4) 전에는 clamav가 `data`에 있어 clamd의 취약점이 MySQL과 api로 이어질 수 있었고, freshclam이 `egress`에 있어 instance metadata에 닿았다. 지금 구성은 `docker/smoke-test.sh` 6·7번이 확인한다.
