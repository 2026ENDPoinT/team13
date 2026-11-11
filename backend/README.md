<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/stack-corp-logo-dark.png">
    <img src="assets/stack-corp-logo.png" alt="STack-Corp" width="360">
  </picture>
</p>

# STack-eclass-Backend

서울과학기술대학교 e-Class(ILOS LMS, 원본 서비스명 `ASTRA`) 재구현의 백엔드 레포다. e-class는 캠퍼스 온과 **별개 서비스**이며 백엔드·DB·ERD를 따로 둔다. 두 서비스는 API로 연결한다([서비스 경계 초안](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/05_service_boundary.md)).

| 서비스 | 프론트엔드 | 백엔드 | ERD |
|---|---|---|---|
| e-class | [STack-eclass-Frontend](https://github.com/STack-Corp/STack-eclass-Frontend) | 이 레포 (MySQL 8.4) | [ERD A](https://www.erdcloud.com/d/B5bWYfGmFisJTYLka) |
| 캠퍼스 온 | [STack-Frontend](https://github.com/STack-Corp/STack-Frontend) | [STack-Backend](https://github.com/STack-Corp/STack-Backend) (PostgreSQL 17) | [ERD B](https://www.erdcloud.com/d/G75sZP24bZNHuezyy) |

2026-11-10 STack-Backend에서 루트 `src/`·MySQL 마이그레이션·`db/`·`docs/`를 커밋 이력과 함께 옮겼다(STAC-90).

## 폴더 구성

| 경로 | 내용 |
|---|---|
| `reference/` | [ASTRA-Reference](https://github.com/STack-Corp/ASTRA-Reference) 서브모듈: 화면 명세, 엔드포인트 계약, DB 설계 근거 |
| `src/` | Spring Boot 애플리케이션. DB 마이그레이션은 `src/main/resources/db/migration/` |
| `db/` | ERD 생성·비교 도구, ERDCloud 스냅샷, [DB 가이드](db/README.md) |
| `docs/` | [백엔드 아키텍처](docs/architecture.md)와 결정 기록 [docs/adr/](docs/adr/) |
| `assets/` | 팀 로고 |

처음 받을 때는 서브모듈까지 함께 받는다.

```sh
git clone --recurse-submodules https://github.com/STack-Corp/STack-eclass-Backend.git
# 이미 받았다면
git submodule update --init
```

## 참고 자료

| 문서 | 내용 |
|---|---|
| [reference/README.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/README.md) | 화면별 UI·동작 명세 |
| [reference/backend/README.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/README.md) | 서버 책임, 근거 수준(`OBSERVED`/`CLIENT`/`INFERRED`/`PROPOSED`/`UNKNOWN`) |
| [reference/backend/00_server_contract.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md) | 권한·과목 context·트랜잭션·시간 등 서버 공통 불변식 |
| [reference/backend/endpoint_inventory.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/endpoint_inventory.md) | 엔드포인트 ID(`EP-xxxx`)와 계약 문서 |
| [reference/backend/90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md) | 바꿔야 할 부분과 우선순위 |
| [reference/db/design/](https://github.com/STack-Corp/ASTRA-Reference/tree/main/db/design) | DB 영역별 설계 근거와 미결 사항 |
| [db/README.md](db/README.md) | DB 영역 구성, 데이터 흐름, 설계 규칙, 스키마 변경·ERD 반영 절차 |
| [docs/architecture.md](docs/architecture.md) | 배포 토폴로지, 모듈 구조, 인증·인가, 영상 전달, 관측, 단계 계획 |
| [reference/backend/05_service_boundary.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/05_service_boundary.md) | 캠퍼스 온–e-class 서비스 경계, 신원 발급, 서비스 간 API (논의용 초안) |

## 두 레포의 역할

| | ASTRA-Reference | STack-eclass-Backend |
|---|---|---|
| 담는 것 | 화면 명세, 엔드포인트 계약, DB 설계 근거 | 서버 코드, 정본 DB 스키마, ERD A 도구·스냅샷, 테스트 |
| 변경 기준 | 근거와 근거 수준을 함께 기록 | 계약을 지키는지 테스트로 확인 |

명세나 계약을 바꿔야 하면 Reference를 먼저 고친다. 그다음 이 레포에서 서브모듈을 그 변경으로 올리고 구현한다.

```sh
git -C reference pull origin main
git add reference
git commit -m "Update reference to <변경 요약>"
```

서브모듈은 특정 커밋에 고정된다. Reference에 새 커밋이 생겨도 이 레포의 `reference/`는 위 명령으로 올리기 전까지 바뀌지 않는다.

## DB 스키마 기준

- 정본은 Flyway 마이그레이션 [`src/main/resources/db/migration/`](src/main/resources/db/migration/)이다. `V1__baseline.sql`은 Reference에서 설계한 97개 테이블 그대로다. Reference의 `db/design/*.md`에 있는 DDL 조각은 설계 당시 초안이다.
- 이미 적용된 마이그레이션 파일은 고치지 않는다. 바꿀 내용은 다음 번호의 새 파일로 만든다.
- 변경·점검·ERD 반영 절차는 [db/README.md](db/README.md) 5~6절을 따른다.
- ERD는 [ERD A](https://www.erdcloud.com/d/B5bWYfGmFisJTYLka)(MySQL, 97 테이블)다. 캠퍼스 온의 ERD B는 STack-Backend 레포가 관리한다.

## 빌드와 실행

| 항목 | 버전 |
|---|---|
| JDK | 25 (Gradle toolchain으로 지정) |
| Spring Boot | 4.1.2 |
| Gradle | 9.8.2 (wrapper 포함, 따로 설치하지 않음) |
| DB | MySQL 8.4, Flyway |

테스트와 로컬 실행에는 Docker와 `ffmpeg`·`ffprobe`(PATH)가 필요하다. Testcontainers가 MySQL 8.4와 S3 호환 저장소 컨테이너를 띄우고 마이그레이션을 적용한다. 영상 변환 테스트는 ffmpeg로 짧은 시험 영상을 만들어 쓴다.

```sh
./gradlew build          # Windows: gradlew.bat build
./gradlew bootTestRun    # DB 없이 로컬 실행. MySQL과 S3 호환 저장소 컨테이너를 자동으로 띄운다
```

`bootTestRun`은 테스트 소스의 `TestStackBackendApplication`을 실행한다. 배포하는 jar에 없는 것을 세 가지 더한다.

| 더하는 것 | 내용 |
|---|---|
| 표본 데이터 | 학생 박민준(`21101234`), 교수 박은주(`P-1042`), 이번 학기 과목 7개. 과목 키는 프론트엔드가 `kj`로 쓰는 값(`bi`, `algo`, `ai`, `db`, `oss`, `se`, `secu`)이다 |
| 개발용 로그인 | `POST /api/auth/dev-login` `{"externalRef":"21101234"}`. 이 컴퓨터에서 온 요청만 받는다. CSRF token이 필요하다 |
| `local` 프로필 | `127.0.0.1`에만 listen, cookie의 `Secure` 끔, 허용 Origin `http://127.0.0.1:4190`·`http://localhost:4190`, 앱이 `/doc`·`/vod`를 직접 응답([ADR-0009](docs/adr/0009-origin-delivery-without-cdn.md)), `worker` 프로필 포함 |

프론트엔드([STack-eclass-Frontend](https://github.com/STack-Corp/STack-eclass-Frontend))는 개발 서버가 `/api`, `/doc`, `/vod`를 이 서버로 넘긴다. 브라우저는 프론트엔드 주소만 쓴다. 백엔드는 CORS를 열지 않는다.

```sh
ASTRA_API_ORIGIN=http://127.0.0.1:8080 npm start    # STack-eclass-Frontend에서
```

8080 포트를 다른 프로그램이 쓰고 있으면 `Port 8080 was already in use`로 실패한다. Docker로 띄운 다른 앱이 8080을 쓰는 경우가 많다. 이때는 포트를 바꿔 실행한다.

```sh
./gradlew bootTestRun --args=--server.port=18080    # Windows: gradlew.bat bootTestRun --args=--server.port=18080
```

상태 확인용 관리 port 8081(`127.0.0.1`에만 열린다)도 같다. 겹치면 `--management.server.port=18081`을 함께 넘긴다.

실제 DB에 붙여 실행할 때는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수를 넣고 `./gradlew bootRun`을 쓴다.

### LLM 설정

쪽별 요약([ADR-0006](docs/adr/0006-summary-module.md))은 OpenAI Chat Completions 호환 제공자를 부른다([ADR-0008](docs/adr/0008-llm-port-deepseek.md)). 설정은 환경 변수로 넣는다. key가 없으면 앱은 뜨고 요약 생성만 쓸 수 없다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_LLM_API_KEY` | 없음 | 제공자 API key. 저장소와 로그에 남기지 않는다 |
| `ASTRA_LLM_BASE_URL` | `https://api.deepseek.com` | 이 주소 뒤에 `/chat/completions`를 붙여 부른다. https만 허용한다 |
| `ASTRA_LLM_MODEL` | `deepseek-flash` | 이미지 입력을 받는 model |
| `ASTRA_LLM_VISION` | `true` | `false`면 쪽 이미지를 보내지 않고 추출 텍스트만 보낸다 |
| `ASTRA_LLM_TIMEOUT` | `300s` | 요청 하나의 전체 제한 시간 |
| `ASTRA_LLM_MAX_CONCURRENCY` | `2` | 동시에 보내는 요청 수 상한 |
| `ASTRA_LLM_EXTRABODY_*` | 없음 | 요청 본문에 더하는 제공자 전용 값. 예: `ASTRA_LLM_EXTRABODY_THINKING_TYPE=disabled`는 본문에 `{"thinking":{"type":"disabled"}}`를 더한다 |

### 저장소와 문서 변환 설정

파일 원본과 파생물은 S3 API로 읽고 쓴다([ADR-0007](docs/adr/0007-confidential-content-placement.md)). 운영에서는 bucket 이름만 넣고 credential은 EC2 instance role로 받는다. 로컬 실행과 테스트는 Testcontainers가 띄운 S3 호환 저장소(`adobe/s3mock`)와 같은 process의 converter를 쓴다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_STORAGE_CONTENT_BUCKET` | `astra-content` | 원본, 모델 입력용 rendition, 요약 결과 |
| `ASTRA_STORAGE_DELIVERY_BUCKET` | `astra-delivery` | CloudFront가 읽는 HLS(`vod/`)와 화면용 쪽 이미지(`doc/`) |
| `ASTRA_STORAGE_REGION` | `ap-northeast-2` | |
| `ASTRA_CONVERTER_URL` | `http://converter:8090` | converter 주소 |
| `ASTRA_SUMMARY_POLL_INTERVAL` | `5s` | worker가 대기 중인 요약 작업을 찾는 간격 |
| `ASTRA_UPLOAD_TTL` | `24h` | 첨부하지 않은 업로드를 쓸 수 있는 시간 |
| `ASTRA_UPLOAD_RATE_LIMIT` · `ASTRA_UPLOAD_RATE_WINDOW` | `30` · `10m` | 한 사용자가 구간 안에 올릴 수 있는 파일 수 |
| `ASTRA_FILES_GC_INTERVAL` | `1m` | worker 저장소 GC가 한 번 돈 뒤 쉬는 시간 |
| `ASTRA_FILES_GC_BATCH_SIZE` | `100` | GC 한 번에 단계마다 처리하는 행 수 상한 |
| `ASTRA_FILES_GC_DERIVATIVE_GRACE` | `10m` | 파일을 지운 뒤 파생물·rendition 객체를 지우기까지 기다리는 시간 |
| `ASTRA_FILES_GC_BLOB_GRACE` | `1h` | 참조가 0이 된 원본 객체를 지우기까지 기다리는 시간 |

업로드는 파일 하나에 50 MB까지 받는다. 허용 확장자와 크기는 `upload_policies`에 있고, 확장자와 내용 형식이 맞지 않으면 거부한다([ADR-0010](docs/adr/0010-upload-acceptance.md)). 강의 영상은 `POST /api/uploads/stream`으로 본문을 그대로 받고 `lecture_video` 정책의 `max_file_bytes`(기본 2 GiB)까지 받는다([ADR-0016](docs/adr/0016-video-upload-and-transcoder.md)). 상한을 바꾸려면 새 마이그레이션으로 그 행을 고친다.

요약 작업과 저장소 GC(만료 업로드, 지운 파일의 파생물, 참조 없는 원본 객체 정리, [ADR-0011](docs/adr/0011-material-deletion.md))는 `worker` 프로필(`--spring.profiles.active=worker`)에서만 실행된다. converter는 같은 jar를 다른 main으로 띄운다. 외부 network가 없는 container에서 실행한다.

```sh
java -Dloader.main=com.stackcorp.converter.ConverterServer -cp stack-eclass-backend.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher   # CONVERTER_PORT 기본 8090
```

### 악성코드 검사 설정

업로드는 같은 배포의 ClamAV daemon(clamd, compose 서비스 `clamav`)에 TCP `INSTREAM`으로 보내 검사한다([ADR-0018](docs/adr/0018-malware-scan-clamd.md)). 검사기를 설정하면 새 내용은 검사를 마칠 때까지 `quarantined`이고, `worker` 프로필의 검사 worker가 `clean` 또는 `rejected`로 바꾼다. 검사기에 닿지 못하면 `quarantined`로 두고 다시 시도한다. host를 비우면 형식 검사만 하고 시작할 때 경고를 남긴다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_SCAN_CLAMD_HOST` | 없음 | clamd host 이름이나 IP. 앱이 연결하는 검사기는 이 host와 port 하나다 |
| `ASTRA_SCAN_CLAMD_PORT` | `3310` | clamd TCP port |
| `ASTRA_SCAN_REQUIRED` | `false` | `true`면 검사기 없이 시작하지 않는다. 학교 자료를 받는 배포에서 켠다 |
| `ASTRA_SCAN_CLAMD_CONNECT_TIMEOUT` · `ASTRA_SCAN_CLAMD_TIMEOUT` | `2s` · `120s` | 연결 제한 시간, 파일 하나의 전체 제한 시간 |
| `ASTRA_SCAN_CLAMD_MAX_STREAM_BYTES` | `64MB` | 보내는 내용의 상한. clamd의 `StreamMaxLength`와 같게 둔다 |
| `ASTRA_SCAN_CLAMD_CHUNK_SIZE` | `64KB` | `INSTREAM` chunk 크기 |
| `ASTRA_SCAN_POLL_INTERVAL` · `ASTRA_SCAN_BATCH_SIZE` | `10s` · `20` | worker가 검사 대기 내용을 찾는 간격과 한 번에 검사하는 수 |
| `ASTRA_SCAN_RETRY_BACKOFF` · `ASTRA_SCAN_MAX_RETRY_BACKOFF` | `30s` · `1h` | 검사기에 닿지 못했을 때 첫 재시도 간격과 상한. 시도마다 두 배로 늘린다 |
| `ASTRA_SCAN_LEASE` | `10m` | 검사 하나의 lease. clamd 전체 제한 시간보다 길게 둔다 |

clamd의 `StreamMaxLength`, `MaxFileSize`는 업로드 상한(50 MB)보다 크게, `MaxScanSize`는 그 이상으로 둔다. `StreamMaxLength`가 작으면 큰 파일은 검사되지 못하고 `quarantined`에 남는다.

로컬에서 검사기를 붙여 실행하려면 clamd를 띄우고 host를 넣는다. 이미지가 처음 뜰 때 signature를 받느라 1분 남짓 연결을 받지 않는다. 그동안 올린 파일은 `quarantined`로 남았다가 다음 재시도에 검사된다.

```sh
docker run -d --name clamav -p 127.0.0.1:3310:3310 clamav/clamav:stable
ASTRA_SCAN_CLAMD_HOST=127.0.0.1 ./gradlew bootTestRun
```

EICAR 표준 시험 문자열(68 byte)만 담은 `.txt`를 올리면 `rejected`가 된다. 테스트는 clamd 대신 테스트 소스의 `FakeClamd`(INSTREAM 형식을 구현한 가짜 서버)를 쓴다.

### 온라인 강의 학습 설정

학습 session과 출석 인정 적립은 [ADR-0014](docs/adr/0014-learning-session-credit.md)를 따른다. 적립 간격의 하한과 상한, lease는 하트비트 주기에서 정해진다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_LEARNING_HEARTBEAT_INTERVAL` | `60s` | 서버가 player에 알려 주는 하트비트 주기(10초~5분). 영상 cookie 유효 시간(10분)보다 짧아야 한다 |
| `ASTRA_LEARNING_LEASE_SWEEP_INTERVAL` | `30s` | worker가 lease가 지난 학습 session을 닫는 간격 |
| `ASTRA_LEARNING_REQUIRE_AUTH_GRANT` | `false` | `true`면 학생의 학습 시작과 적립에 학습 인증 grant를 요구하고 그 만료에서 적립을 자른다(SC-06-A, [ADR-0029](docs/adr/0029-step-up-and-learning-auth.md)). 학교 IdP를 붙인 배포에서 켠다 |

### 알림 설정

예약 공지, 시작 일시가 미래인 자료·과제의 알림은 worker가 그 시각 뒤에 보낸다([ADR-0027](docs/adr/0027-notifications-in-producer-transaction.md)). 같은 주기에 보존 기간(60일)이 지난 알림을 지운다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_NOTIFICATION_SWEEP_INTERVAL` | `30s` | worker가 보낼 시각이 된 알림을 찾는 간격. 예약 알림은 보일 시각부터 이 간격만큼 늦을 수 있다 |

### 영상 변환 설정

강의 영상은 worker가 transcoder로 HLS segment를 만들고 AES-128로 암호화해 `delivery` bucket의 `vod/`에 게시한다([ADR-0016](docs/adr/0016-video-upload-and-transcoder.md)). transcoder는 converter처럼 같은 jar를 다른 main으로 띄운다. `ffmpeg`와 `ffprobe`가 있는 container에서, 외부 network와 AWS credential 없이 실행하고 worker에서 오는 요청만 받는다.

```sh
java -Dloader.main=com.stackcorp.transcoder.TranscoderServer -cp stack-eclass-backend.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher   # TRANSCODER_PORT 기본 8091
```

transcoder process의 환경 변수:

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `TRANSCODER_PORT` | `8091` | listen 포트 |
| `TRANSCODER_MAX_BYTES` · `TRANSCODER_MAX_OUTPUT_BYTES` | 2 GiB · 4 GiB | 원본, 결과 전체의 크기 상한(byte) |
| `TRANSCODER_MAX_DURATION_SECONDS` | `14400` | 재생 길이 상한 |
| `TRANSCODER_TIMEOUT_SECONDS` | `7200` | ffmpeg 실행 제한 시간 |
| `TRANSCODER_THREADS` | `2` | ffmpeg encoder thread 수. container CPU 상한과 맞춘다 |
| `TRANSCODER_WORK_DIR` | `java.io.tmpdir` | 요청마다 작업 directory를 만들고 응답 뒤 지우는 곳 |
| `TRANSCODER_FFMPEG` · `TRANSCODER_FFPROBE` | `ffmpeg` · `ffprobe` | 실행 파일 |

api·worker의 환경 변수:

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_TRANSCODER_URL` | `http://transcoder:8091` | transcoder 주소 |
| `ASTRA_TRANSCODER_TIMEOUT` | `3h` | 영상 하나의 변환과 응답을 기다리는 시간. transcoder 제한 시간보다 길게 둔다 |
| `ASTRA_TRANSCODER_WORKER_POLL_INTERVAL` | `10s` | worker가 대기 작업과 삭제된 원본을 찾는 간격 |
| `ASTRA_TRANSCODER_WORKER_MAX_ATTEMPTS` · `ASTRA_TRANSCODER_WORKER_RETRY_BACKOFF` | `3` · `1m` | 일시 오류일 때 실행 횟수와 첫 재시도 대기(4배씩 늘고 1시간 상한) |
| `ASTRA_TRANSCODER_WORKER_LEASE` | `10m` | 실행 lease. worker가 멈추면 이 시간 뒤 작업을 다시 대기로 돌린다 |
| `ASTRA_UPLOAD_STREAM_PART_SIZE` | `8MB` | 영상 업로드의 S3 multipart part 크기. 업로드 하나가 이만큼 메모리를 쓴다 |
| `ASTRA_UPLOAD_STREAM_ABANDON_AFTER` | `24h` | 이 시간이 지나도 끝나지 않은 multipart upload는 worker가 취소한다 |

`bootTestRun`은 transcoder를 같은 process에서 띄운다. PATH에 `ffmpeg`가 없으면 영상 콘텐츠가 `failed`로 끝난다.

### 인증과 CDN 서명 설정

학교 IdP 연결 전까지 로그인은 break-glass 계정 하나만 된다([ADR-0004](docs/adr/0004-sso-bff-session.md) 6번). 비밀번호 hash와 TOTP secret은 DB가 아니라 Secrets Manager에서 환경 변수로 넣는다. 셋 중 하나라도 없으면 로컬 로그인은 꺼진다. 운영자는 `users`에 같은 `external_ref`의 행을 먼저 만든다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_BREAK_GLASS_EXTERNAL_REF` | 없음 | break-glass 계정의 `users.external_ref` |
| `ASTRA_BREAK_GLASS_PASSWORD_HASH` | 없음 | `{bcrypt}...` 형식의 비밀번호 hash |
| `ASTRA_BREAK_GLASS_TOTP_SECRET` | 없음 | RFC 6238 TOTP secret(Base32) |
| `ASTRA_SESSION_ALLOWED_ORIGINS` | 없음 | 상태를 바꾸는 요청에 허용하는 Origin(쉼표 구분). 비우면 요청 자신의 origin만 허용한다 |
| `ASTRA_CLOUDFRONT_BASE_URL` | 없음 | 서비스 도메인(https). signed cookie 정책의 resource 기준 |
| `ASTRA_CLOUDFRONT_KEY_PAIR_ID` | 없음 | CloudFront key group의 public key ID. 없으면 signed cookie를 발급하지 않는다 |
| `ASTRA_CLOUDFRONT_PRIVATE_KEY` | 없음 | PKCS#8 PEM RSA 개인 key |
| `ASTRA_DELIVERY_ORIGIN_ENABLED` | `false` | CloudFront 없이 앱이 `/doc`, `/vod`를 직접 응답한다. CloudFront 서명과 함께 켤 수 없다 |
| `ASTRA_DELIVERY_ORIGIN_SIGNING_KEY` | 없음 | 위 방식의 cookie 서명 key(Base64, 32 byte 이상). 비밀값이다 |

브라우저는 먼저 `GET /api/auth/csrf`로 `XSRF-TOKEN` 쿠키를 받고, 상태를 바꾸는 요청마다 그 값을 `X-XSRF-TOKEN` header로 보낸다.

### 멱등 원장 설정

자료 등록과 요약 생성 요청은 `Idempotency-Key` header(`[A-Za-z0-9._-]` 1~100자)를 받는다. 같은 key의 재전송은 처음 응답을 다시 받는다([ADR-0012](docs/adr/0012-idempotency-ledger-and-audit.md)). 모든 응답의 `X-Request-Id`는 서버가 만든 값이고, 같은 값이 로그 줄, 오류 본문의 `requestId`, 감사 기록에 들어간다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_IDEMPOTENCY_RETENTION` | `24h` | 완료한 key의 receipt를 보존하는 시간 |
| `ASTRA_IDEMPOTENCY_IN_FLIGHT_WAIT` | `3s` | 같은 key를 처리 중일 때 기다리는 시간. 넘으면 409 `idempotency_in_progress` |
| `ASTRA_IDEMPOTENCY_PURGE_INTERVAL` | `15m` | worker가 보존기한이 지난 항목을 지우는 간격 |

### 학교 SSO(OpenID Connect) 설정

학교 IdP가 OpenID Connect를 제공한다는 가정으로 authorization code flow + PKCE를 구현했다([ADR-0017](docs/adr/0017-school-sso-oidc.md)). `ASTRA_OIDC_ISSUER`가 없으면 SSO는 꺼지고 `/api/auth/login`, `/api/auth/callback`은 404다. 앱은 시작할 때 IdP를 부르지 않는다. 형식이 틀린 설정이 있으면 시작하지 않는다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_OIDC_ISSUER` | 없음 | IdP의 issuer. discovery 문서와 ID token의 `iss`가 이 값과 글자 그대로 같아야 한다. https만 허용한다 |
| `ASTRA_OIDC_CLIENT_ID` | 없음 | IdP에 등록한 client ID |
| `ASTRA_OIDC_CLIENT_SECRET` | 없음 | client secret. Secrets Manager에서 넣고 로그에 남기지 않는다. 로그인 전용 쿠키의 암호화 key도 이 값에서 만든다 |
| `ASTRA_OIDC_REDIRECT_URI` | 없음 | IdP에 등록한 callback 주소. 서비스 origin 뒤에 `/api/auth/callback`을 붙인다. 예: `https://lms.example.ac.kr/api/auth/callback`. 등록한 값과 글자 그대로 같아야 한다 |
| `ASTRA_OIDC_USER_CLAIM` | `sub` | `users.external_ref`(학번·교번)와 맞출 ID token claim. userinfo는 부르지 않으므로 ID token에 있어야 한다 |
| `ASTRA_OIDC_SCOPES` | `openid` | 요청할 scope(쉼표 구분). `openid`가 있어야 한다. 학번 claim을 받으려면 IdP가 요구하는 scope를 더한다 |
| `ASTRA_OIDC_ALLOWED_HOSTS` | 없음 | discovery가 issuer와 다른 host의 token endpoint나 JWKS를 가리킬 때 그 https host(쉼표 구분). 그 밖의 host는 부르지 않는다 |
| `ASTRA_OIDC_TIMEOUT` | `10s` | IdP 요청 하나의 전체 제한 시간 |
| `ASTRA_OIDC_CLOCK_SKEW` | `60s` | ID token 시각(`exp`, `iat`, `nbf`) 검사에 허용하는 시계 오차. 5분까지 |

IdP에는 confidential client로 등록하고 ID token 서명 알고리즘은 RS256으로 둔다. 로그인 화면은 `GET /api/auth/methods`로 쓸 수 있는 수단을 받아 `GET /api/auth/login?returnTo=<같은 origin의 경로>`로 이동한다. 실패하면 `/?login=failed`로 돌아온다. `users`에 그 claim 값의 ACTIVE 사용자가 있어야 로그인된다. 사용자를 자동으로 만들지 않는다.

### 재인증과 학습 인증 설정

연락처 변경, 다른 session 모두 끝내기 같은 민감한 명령은 그 session의 최근 재인증을 요구한다. 학교 IdP 계정은 `GET /api/auth/reauth?returnTo=<경로>`로 IdP에서 다시 로그인하고(IdP에 `prompt=login`, `max_age=0`), break-glass 계정은 `POST /api/me/step-up/totp`로 TOTP를 다시 넣는다. 학습 인증 grant는 `POST /api/me/learning-auth/challenges`로 시작해 재인증한 뒤 `…/verify`로 받는다([ADR-0029](docs/adr/0029-step-up-and-learning-auth.md)). IdP는 `max_age` 요청에 `auth_time` claim을 ID token에 넣어야 한다. 로컬 실행(IdP 없음)은 `POST /api/me/step-up/dev`가 재인증을 대신한다.

| 환경 변수 | 기본값 | 내용 |
|---|---|---|
| `ASTRA_STEP_UP_RECENT_AUTH` | `10m` | 재인증 한 번이 민감한 명령을 허용하는 최대 시간(1분~1시간) |
| `ASTRA_STEP_UP_LEARNING_GRANT` | `2h` | 학습 인증 grant의 유효 시간(5분~12시간). 로그인 session의 절대 만료를 넘지 않는다 |
| `ASTRA_STEP_UP_CHALLENGE` | `5m` | 학습 인증 challenge를 확인할 수 있는 시간(1분~15분) |
| `ASTRA_STEP_UP_CHALLENGE_ATTEMPTS` | `5` | challenge 하나의 확인 시도 한도. 넘으면 그 challenge는 잠긴다 |
| `ASTRA_STEP_UP_LOCKOUT_ATTEMPTS` · `ASTRA_STEP_UP_LOCKOUT_WINDOW` | `10` · `15m` | 이 시간 안에 확인 실패 합이 이만큼이면 새 challenge를 만들지 못한다 |

### 컨테이너로 실행

host 한 대에서 api, worker, converter, transcoder를 container로 띄운다. 구성의 이유는 [ADR-0019](docs/adr/0019-container-image-and-single-host-compose.md)에 있다. Docker Engine과 Compose v2.24 이상이 필요하다.

```sh
docker build -t astra-backend:local .     # 이미지 하나. 역할은 command(api | worker | converter | transcoder)로 고른다
cp .env.example .env                       # 값을 채운다. .env는 커밋하지 않는다
docker compose up -d --wait                # 모든 서비스가 healthcheck를 통과할 때까지 기다린다
docker compose down                        # 종료. MySQL 데이터 volume까지 지우려면 --volumes
```

`.env`에는 위 표의 환경 변수와 아래 compose 설정을 넣는다. 전체 목록과 설명은 [.env.example](.env.example)에 있다. api와 worker가 이 파일을 받고, converter, transcoder와 MySQL은 받지 않는다. 쓰지 않는 값은 `KEY=`처럼 빈 값으로 두지 말고 줄을 지운다. `$`가 든 값(bcrypt hash)은 작은따옴표로 감싼다.

| 변수 | 기본값 | 내용 |
|---|---|---|
| `COMPOSE_PROFILES` | 없음 | 함께 띄울 선택 서비스. `mysql`(같은 host의 MySQL 8.4), `scan`(ClamAV). 예: `mysql,scan`. RDS를 쓰면 `mysql`을 빼고 `DB_URL`을 RDS로 바꾼다 |
| `ASTRA_IMAGE` | `astra-backend:local` | 실행할 이미지. ECR 이미지면 전체 이름과 tag |
| `ASTRA_API_BIND` · `ASTRA_API_PORT` | `127.0.0.1` · `8080` | api를 publish할 host 주소와 port. CloudFront VPC origin이 붙는 EC2에서는 `0.0.0.0`으로 두고 security group으로 막는다 |
| `JAVA_OPTS` | 없음 | JVM option. 이미지 기본값(`-XX:MaxRAMPercentage=60` 등) 뒤에 붙는다 |

| 서비스 | 열리는 곳 | 외부 network | 하는 일 |
|---|---|---|---|
| `api` | host의 `ASTRA_API_BIND:ASTRA_API_PORT` → 8080 | 있음 | HTTP API |
| `worker` | 없음(container 안의 127.0.0.1) | 있음 | 요약 작업, 영상 변환 작업 등 배경 작업. converter와 transcoder를 부른다 |
| `converter` | worker만 닿는 내부 network의 8090 | 없음 | PDF → 쪽 이미지·텍스트. 비밀값과 저장소 credential이 없다 |
| `transcoder` | worker만 닿는 다른 내부 network의 8091 | 없음 | 영상 → HLS segment(ffmpeg). 비밀값, 저장소 credential, 암호화 key가 없다. 작업 파일은 volume `transcoder-work`(`/work`)에 요청마다 만들고 지운다. 영상 하나(원본 2 GiB, 결과 4 GiB까지)를 담을 디스크 여유를 둔다 |
| `mysql` (프로필 `mysql`) | api, worker만 닿는 내부 network의 3306 | 없음 | DB. root는 container 안에서만 접속한다 |
| `clamav` (프로필 `scan`) | api, worker만 닿는 내부 network의 3310 | 없음 | 업로드 검사(clamd). `ASTRA_SCAN_CLAMD_HOST=clamav`, `ASTRA_SCAN_CLAMD_PORT=3310`, `ASTRA_SCAN_REQUIRED=true`를 함께 넣는다 |
| `freshclam` (프로필 `scan`) | 없음 | 있음 | ClamAV 서명 갱신. 업로드 파일을 받지 않는다 |

- 상태 확인은 각 container 안의 `127.0.0.1:8081/actuator/health/readiness`(api, worker), `127.0.0.1:8090/health`(converter), `127.0.0.1:8091/health`(transcoder)뿐이다. host와 API port에는 `/actuator`가 없다. 상태는 `docker compose ps`로 본다.
- 로그는 stdout의 JSON(ECS) 한 줄이다. `docker compose logs api`로 본다.
- container는 uid 10001로 실행하고 root filesystem은 읽기 전용이다. 쓰기는 `/tmp`(tmpfs)와 transcoder의 `/work` volume에만 한다. 이미지에는 transcoder가 쓰는 ffmpeg가 들어 있고 다른 역할은 실행하지 않는다.
- 저장소는 S3 API면 된다. AWS S3와 instance role이면 bucket 이름만 넣고, S3 호환 저장소면 `ASTRA_STORAGE_ENDPOINT`, `ASTRA_STORAGE_ACCESS_KEY`, `ASTRA_STORAGE_SECRET_KEY`, `ASTRA_STORAGE_PATH_STYLE=true`를 넣는다. 이 구성은 저장소를 띄우지 않는다. EC2에서 container가 instance role을 쓰려면 instance metadata의 hop limit이 2여야 한다.
- CloudFront 없이 문서와 영상을 보이려면 `ASTRA_DELIVERY_ORIGIN_ENABLED=true`와 `ASTRA_DELIVERY_ORIGIN_SIGNING_KEY`를 넣는다([ADR-0009](docs/adr/0009-origin-delivery-without-cdn.md)).
- `docker compose stop`은 api가 처리 중인 요청을 끝낼 때까지, worker가 실행 중인 요약 작업을 `interrupted`로 닫을 때까지 최대 75초를 기다린다.

[docker/smoke-test.sh](docker/smoke-test.sh)는 띄운 구성의 healthcheck, 공개 port, converter·transcoder의 network 차단과 비밀값 없음, transcoder의 ffmpeg와 작업 volume, 실행 user, 로그 형식, 종료 처리를 확인한다. `.env`의 `COMPOSE_PROFILES`에 `mysql`이 있어야 한다.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml)이 `main` 푸시와 모든 PR에서 세 작업을 실행한다.

| 작업 | 확인하는 것 |
|---|---|
| Build and test | `./gradlew build`. 빌드 전에 `ffmpeg`를 설치한다. 테스트가 MySQL 8.4 컨테이너에 Flyway 마이그레이션을 적용하고 앱이 뜨는지 확인한다. `ModuleStructureTests`가 모듈 사이 의존이 [docs/architecture.md](docs/architecture.md) 4.1절 표를 벗어나지 않는지 확인하고 `build/spring-modulith-docs/`에 C4 component diagram을 쓴다 |
| Schema check and ERD snapshot | 마이그레이션을 MySQL 8.4에 적용한 뒤 `db/tools/check.sql`로 깨진 FK, COMMENT 누락, 고립 테이블을 찾는다. `db/erdcloud/snapshot.json`(ERD A)이 현재 스키마로 다시 만든 결과와 같은지도 확인한다 |
| Container image and compose smoke test | `Dockerfile`로 이미지를 만들고, `compose.yaml`이 참조하는 변수가 `.env.example`에 모두 있는지 본 뒤, 비밀값 없는 `.env`로 구성을 띄워 `docker/smoke-test.sh`를 실행한다. registry에는 올리지 않는다 |

마이그레이션을 추가했다면 같은 PR에서 `python db/tools/erdcloud_snapshot.py`로 스냅샷도 갱신해야 CI가 통과한다. CI는 `reference` 서브모듈을 받지 않는다. 빌드에 필요 없고, 비공개 레포라 기본 토큰으로는 받을 수 없기 때문이다.

## 구현 순서

[reference/backend/90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md)의 P0 항목부터 닫는다.

| 순서 | 변경 | 주로 닿는 영역 |
|---|---|---|
| CHG-01 | 서버 신원과 객체 권한을 하나의 정책으로 | identity, 전 영역 |
| CHG-02 | 과목 선택을 세션 전역 상태와 분리 | catalog, learning |
| CHG-03 | 수강신청을 폼 열기와 분리하고 한 번에 처리 | catalog |
| CHG-04 | 제출·시험·설문의 최종 상태와 첨부를 한 번에 처리 | assessment, files |
| CHG-05 | 출석·마감 시각을 서버 시계 기준으로 | learning, assessment |
| CHG-06 | 파일·변환·AI가 원문 권한을 우회하지 않게 | files |

## 일감 관리 (Jira)

일감은 Jira [STack 스페이스(키 `STAC`)](https://stack-corp.atlassian.net/jira/software/projects/STAC/boards/1)에서 관리한다.

| 에픽 | 내용 |
|---|---|
| STAC-1 개발 기반 준비 | DB 접근 방식, 브랜치 보호, GitHub 연동, 팀원 초대 |
| STAC-6 ~ STAC-63 | `CHG-01` ~ `CHG-12`. 하위 작업은 [90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md)의 체크리스트 항목이고, 에픽 설명에 근거와 완료 판정이 있다 |
| STAC-68 | 기존 클라이언트 결함 수정(SEC-01~07) |
| STAC-76 미결 설계 결정 | [db/README.md](db/README.md) 7절의 결정 10개 |
| STAC-88 발표 데모 | 1차 데모는 프론트엔드에 목업 데이터를 넣어 동작을 보여준다(팀 합의 2026-11-08) |
| STAC-89 캠퍼스 온 정리 | 학교 정책 확인, Spring Boot 4.x 업그레이드, 스키마 COMMENT·ERD B 이름 검토 (STack-Backend) |

후속 결정·작업 일감(STAC-90~100)은 STack-Backend의 GitHub 이슈 #5~#15와 서로 연결돼 있다. 진행 상태는 Jira를 기준으로 본다.

모든 일감에는 우선순위 라벨(`P0`/`P1`/`P2`)이, CHG 일감에는 `CHG-xx` 라벨이 붙어 있다. 영역 라벨 `area-identity`, `area-catalog`, `area-learning`, `area-assessment`, `area-communication`, `area-files`와 여러 영역에 걸친 `area-common`, 클라이언트 결함용 `area-client`로 영역별로 거를 수 있다. 개발 기반 준비(STAC-1~5)와 여러 영역을 묶는 STAC-76 에픽 자체에는 영역 라벨이 없다(STAC-76의 하위 결정 일감에는 있다). 보드 열은 To Do → In Progress → In Review(PR 리뷰 중) → Done이다.

GitHub 연동과 Jira 자동화 규칙 3개로 상태가 자동으로 바뀐다. 브랜치나 PR에 `STAC-<번호>`가 있어야 일감에 연결된다. 연동 대상 레포 목록은 STAC-98에서 정한다. 이 레포를 Jira GitHub 앱에 추가해야 연결된다.

| GitHub 이벤트 | 일감 상태 변화 |
|---|---|
| `STAC-<번호>-…` 브랜치 생성 | To Do → In Progress |
| 해당 일감이 연결된 PR 생성 | Done·In Review가 아니면 → In Review |
| 해당 PR 머지 | Done이 아니면 → Done |

규칙은 Jira 설정 → 전역 자동화에 `[STAC]`로 시작하는 이름으로 있다. Free 플랜은 자동화 실행 횟수에 월 한도가 있다.

## 작업 규칙

- 브랜치 이름은 `STAC-<번호>-<짧은-설명>`, 커밋 메시지와 PR 제목은 `STAC-<번호>`로 시작한다. 예: 브랜치 `STAC-7-session-principal`, 커밋 `STAC-7 세션 주체만으로 actor 판정`.
- 엔드포인트를 구현할 때는 커밋이나 PR 본문에 해당 `EP-xxxx` ID도 적는다.
- Reference에서 `UNKNOWN`이나 `[추정]`인 동작은 구현 전에 결정을 남긴다. 결정은 STAC-76 하위 일감에 기록하고, 결정 없이 원본 동작이라고 가정하지 않는다.
- 원본 캡처, 개인정보, 토큰, 비밀번호는 커밋하지 않는다.
