# ADR-0034 지표와 trace는 OTLP로 내보내되 기본은 끄고, 표 전체를 훑는 주기 작업만 DB lease로 묶는다

## 상태
채택 (2026-11-11)

## 맥락
[architecture.md](../architecture.md) 9절은 Micrometer 지표를 OTLP로, trace를 OpenTelemetry로 내보내기로 했고, SC-09는 요청 latency와 오류, 제출 commit 실패, 하트비트 gap, orphan upload, converter 실패, 대기열 backlog, AI token을 지표로 정했다. 지금까지 앱은 요청 로그 한 줄과 health만 냈다.

4.3절은 `@Scheduled` 작업을 ShedLock으로 묶어 인스턴스가 늘어도 한 번만 실행하기로 했고, 4.2절은 commit 뒤 후속 작업을 Spring Modulith JDBC event publication registry로 보장하기로 했다. 실제 worker loop는 저마다 lease와 `SKIP LOCKED`로 worker 둘에서도 안전하게 만들어졌다. 정할 것은 세 가지였다.

- 지표와 trace를 어디로, 언제 내보낼지. 관리 port는 health만 연다([ADR-0019](0019-container-image-and-single-host-compose.md)).
- 주기 작업마다 인스턴스 간 잠금이 필요한지, 필요하면 무엇으로 할지.
- event publication registry를 지금 둘지.

## 결정
1. **지표 이름과 tag**. 앱이 지금 잴 수 있는 것만 Micrometer로 잰다. tag 값은 코드가 정한 이름(연산, 결과 코드, 사유 코드, 단계, 모델 이름)뿐이다. 사용자, 과목, 파일 ID, 실제 경로, query string은 tag에 없다. 형식에 맞지 않는 값은 `other`가 된다(`shared.metrics.MetricTags`). 모든 지표에 `role`(api | worker) tag를 붙인다.

   | 지표 | 내용 |
   |---|---|
   | `http.server.requests{method, uri, status, outcome, exception, code}` | 요청 시간. `uri`는 처리한 경로 pattern, `code`는 problem JSON의 code. 제출·업로드 같은 명령의 실패도 이 지표에서 경로와 code로 본다. p95를 위해 50ms~5s의 7개 구간을 함께 센다 |
   | `astra.learning.heartbeat.gap` | 받아들인 하트비트와 앞 기준 시각 사이의 서버 시각 간격 |
   | `astra.learning.sessions.closed{reason}` | 닫힌 학습 session. button, unload, forced, superseded, lease_expired |
   | `astra.files.gc.backlog{kind}`, `astra.files.gc.processed{step}`, `astra.files.gc.failures{step}` | GC가 처리할 밀린 수(만료 업로드 = orphan upload, 지운 파일, 참조 없는 원본), 처리한 수, 실패한 단계 |
   | `astra.files.scan.backlog`, `astra.files.scan.results{result, reason}` | 검사 대기 수, 판정 수. 검사기 장애는 `result=unavailable` |
   | `astra.files.rendition.conversions{outcome, code}` | PDF 변환 시간과 결과 |
   | `astra.summary.jobs{state}`, `astra.summary.jobs.oldest.age`, `astra.summary.jobs.finished{state, code}` | 요약 대기열 길이와 나이, 끝난 작업 |
   | `astra.llm.calls{model, outcome}`, `astra.llm.tokens{model, type}` | 모델 호출 시간과 결과, token 수. 본문은 남기지 않는다. 비용은 token 수에 단가를 곱해 지표 저장소에서 계산한다 |
   | `astra.idempotency.requests{operation, outcome}`, `astra.idempotency.wait{operation}` | 이미 있는 key를 만난 요청(replayed, conflict, in_progress), 그 key를 확인하고 기다린 시간 |
   | `astra.audit.write.failures{operation}` | transaction 밖에서 빠진 감사 기록([ADR-0012](0012-idempotency-ledger-and-audit.md)의 남은 일) |
   | `astra.ratelimit.rejections{operation}` | 요청 제한으로 거부한 요청 |
   | `hikaricp.connections.*` | DB connection pool 사용(Spring Boot 기본) |

2. **backlog gauge는 worker가 계산해 둔다**. backlog와 대기열 길이는 worker가 `astra.metrics.backlog-interval`(기본 1분)마다 index를 타는 query로 10,000까지만 세고 그 값을 gauge로 낸다(`shared.metrics.SampledGauge`). 지표를 내보낼 때 DB를 읽지 않는다. worker가 멈추면 이 gauge가 끊기고, 그것도 알람 조건이다.

3. **내보내기는 OTLP 하나이고 기본은 끈다**. `spring-boot-starter-opentelemetry`로 지표와 trace를 OTLP로 보낸다. `ASTRA_OTLP_ENABLED=true`와 받을 주소(`OTEL_EXPORTER_OTLP_ENDPOINT`, 또는 `MANAGEMENT_OTLP_METRICS_EXPORT_URL`과 `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`)를 함께 줄 때만 내보낸다. 켰는데 주소가 없으면 시작하지 않는다. 주소가 없을 때 Micrometer가 기본 주소(localhost:4318)로 보내려 하기 때문이다. 로그는 지금처럼 stdout JSON으로만 내고 OTLP로 보내지 않는다. 관리 port에도 API port에도 지표 endpoint(`/actuator/metrics`, `/actuator/prometheus`)를 열지 않는다. 받는 쪽(OpenTelemetry collector나 CloudWatch agent)은 compose 밖에 둔다.

4. **trace에는 서버가 정한 값만 남긴다**. 표본 비율은 `ASTRA_TRACING_SAMPLING_PROBABILITY`(기본 0.1)다. client가 보낸 `traceparent`, `b3`, `baggage`는 읽지 않는다. 요청마다 서버가 trace를 새로 시작하고, 상관 key는 서버가 만든 requestId다. HTTP span에는 method, 경로 pattern, 상태, outcome, 예외 class, problem code와 `request.id`만 남는다. Spring의 기본 값이던 실제 경로(`http.url`)는 뺐다. 요청·응답 본문, query string, cookie, header는 남기지 않는다. JDBC와 외부 HTTP 호출은 span을 만들지 않는다.

5. **주기 작업 잠금은 겹친 실행이 실제로 같은 일을 두 번 하는 곳에만 건다**.

   | 작업 | 결정 | 이유 |
   |---|---|---|
   | 저장소 GC(`files.StorageGc`) | DB lease로 묶음 | 만료 업로드와 지운 파일 후보를 잠그지 않고 읽는다. worker 둘이 같은 후보를 함께 다루고, 지운 파일의 S3 prefix 목록 조회와 삭제를 겹쳐 실행한다 |
   | 학습 session lease 만료(`learning.LearningSessionSweeper`) | DB lease로 묶음 | 활성 session 전체에서 후보를 잠그지 않고 읽고, 후보마다 UPDATE를 보낸다. 겹치면 같은 후보마다 UPDATE가 두 번 간다 |
   | 요약 작업 poll, 업로드 검사 poll | 묶지 않음 | 작업을 `SKIP LOCKED`와 lease로 하나씩 잡는다. worker 둘이 나눠 처리하는 것이 목적이다 |
   | 요약 lease 만료 처리 | 묶지 않음 | 조건부 UPDATE 한 문장이다. 겹치면 뒤 문장이 0행을 바꾼다 |
   | 멱등 원장 정리 | 묶지 않음 | `SKIP LOCKED` 묶음 삭제라 worker 둘이 서로 다른 행을 지운다 |
   | 검사 거부 객체 정리(검사 poll 안) | 묶지 않음 | 감염 판정이 난 내용만 대상이라 드물다. 겹쳐도 없는 객체 삭제와 0행 UPDATE다 |
   | backlog gauge 계산 | 묶지 않음 | 읽기만 하고, 두 worker가 같은 값을 낸다 |

   잠금은 `shared.schedule.SchedulerLock`이 `scheduler_locks`(V33)의 이름별 행 하나로 한다. 잡기는 lease가 끝난 행만 바꾸는 compare-and-set, 놓기는 이번 실행의 token이 맞을 때만 lease를 지금으로 줄이는 UPDATE다. 둘 다 READ COMMITTED의 짧은 transaction이고 DB 시계만 쓴다. 실행하던 process가 죽으면 lease 끝(GC 10분, session 만료 5분) 뒤에 다른 worker가 잡는다. 잠금이 막는 것은 겹친 실행이다. 연달아 실행하는 것은 막지 않는다. 앞 실행이 상태를 바꿔 두어 뒤 실행은 할 일이 없기 때문이다. 그래서 ShedLock의 lockAtLeastFor에 해당하는 값은 두지 않는다.

   ShedLock 7.x는 Spring Boot 4를 지원한다(공식 호환표). 그래도 쓰지 않았다. annotation 방식은 package-private worker class를 AOP proxy로 감싸고, 프로그램 방식은 위의 두 문장 정도를 위해 의존성을 하나 더한다.

6. **event publication registry는 지금 두지 않는다**. commit 뒤 후속 작업을 보장해야 하는 경로는 모두 같은 transaction에 쓰는 원장 행으로 되어 있다. 업로드 확정 뒤 검사는 `file_blobs.blob_state = 'quarantined'`, 요약 요청은 `summary_jobs`의 `queued` 행이고, worker가 그 행을 `SKIP LOCKED`로 잡는다. 강의 자료 등록 뒤 알림은 아직 없다. registry가 필요한 경로(commit 뒤 외부 API 호출을 api process가 바로 해야 하는 경우, 예: 영상 transcode 작업 지시)가 생기면 그 변경에서 테이블과 재전송 작업을 더하고, 재전송 작업은 위의 잠금으로 묶는다.

## 결과와 트레이드오프
- 알람은 [docs/operations/alarms.md](../operations/alarms.md)에 지표, 조건, 첫 대응으로 정리했다. 앱 지표 알람은 내보내기를 켜고 CloudWatch에 지표가 쌓인 뒤 Terraform에 더한다. instance role에 지표·trace 쓰기 권한도 그때 더한다([infra/README.md](../../infra/README.md) 2절).
- `scheduler_locks`는 업무 테이블을 가리키지 않아 FK가 없다. `db/tools/check.sql`의 isolated_table 검사에 예외로 이름을 적었다.
- 잠근 작업이 lease보다 오래 걸리면 다른 worker가 겹쳐 실행할 수 있다. 두 작업 모두 행 단위 상태 전이라 결과는 그대로이고 중복 작업만 생긴다.
- 실행하던 worker가 죽으면 GC와 session 만료가 lease 끝까지 미뤄진다. session은 하트비트와 종료 요청이 스스로 lease를 확인하므로 적립에는 영향이 없다.
- HTTP 요청 지표의 시계열 수는 경로 pattern × method × 상태 × code × 7개 구간이다. 사용자 수와 상관없지만 경로가 늘면 같이 는다. 비용이 문제가 되면 구간을 줄인다.
- Spring Security도 filter chain과 인가 지표(`spring.security.*`)를 낸다. 사용자 이름은 들어가지 않아 그대로 둔다.
- trace 표본 비율의 기준과 trace 저장소(X-Ray 또는 collector 뒤의 다른 저장소)는 내보내기를 켤 때 정한다.
