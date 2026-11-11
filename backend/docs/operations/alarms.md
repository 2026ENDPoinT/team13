# 알람 정의

운영 알람 목록이다. 지표 이름과 tag는 [ADR-0034](../adr/0034-observability-and-scheduler-lock.md) 1번, 알람을 보내는 길(SNS)은 [infra/README.md](../../infra/README.md) 1절을 따른다. 조건의 수치는 처음 값이다. 학교 도입 뒤 실제 부하를 보고 다시 정한다.

- **인프라 알람**은 Terraform(`infra/terraform/modules/monitoring`)에 이미 있다.
- **앱 지표 알람**은 앱이 지표를 내보내기 시작한 뒤(`ASTRA_OTLP_ENABLED=true`와 수신 주소) Terraform에 더한다. 그 전에는 이 문서가 기준이다. 지표는 api와 worker가 각자 내고 `role` tag로 나뉜다.
- 모든 알람은 해소될 때도 알린다(`ok_actions`).

## 1. 인프라 알람 (Terraform에 있음)

| 알람 | 지표 | 조건 | 왜 | 첫 대응 |
|---|---|---|---|---|
| `app-system-status-check` | `AWS/EC2 StatusCheckFailed_System` | 최댓값 1 이상, 1분 × 2회 | host 하드웨어 장애. EC2 recover가 함께 실행된다 | recover 뒤 `docker compose ps`로 api, worker가 healthy인지 본다. 같은 host에 MySQL을 두었다면 volume 상태를 확인한다 |
| `app-instance-status-check` | `AWS/EC2 StatusCheckFailed_Instance` | 최댓값 1 이상, 1분 × 3회 | OS나 instance 안 network 문제 | SSM으로 접속해 디스크, 메모리, `dmesg`를 본다. 접속이 안 되면 instance를 재시작한다 |
| `fck-nat-in-service` | `AWS/AutoScaling GroupInServiceInstances` | 최솟값 1 미만, 1분 × 5회. 지표가 없어도 경보 | 외부로 나가는 길이 끊겨 배포, SSM, 로그 전송, 학교 IdP·LLM 호출이 멈춘다 | ASG 활동 기록에서 교체 실패 이유를 본다. 로그인은 IdP 호출이 필요하므로 학교 쪽 공지를 준비한다 |
| `rds-cpu` | `AWS/RDS CPUUtilization` | 평균이 설정값 초과, 5분 × 3회 | 느린 query나 부하 급증 | Performance Insights에서 상위 query를 보고 아래 API p95 알람과 같이 본다 |
| `rds-free-storage` | `AWS/RDS FreeStorageSpace` | 최솟값이 설정값 미만, 5분 | 공간이 차면 모든 쓰기가 멈춘다 | 커진 테이블(`audit_events`, `learning_heartbeats` 등)을 확인하고 storage를 늘린다 |
| `rds-connections` | `AWS/RDS DatabaseConnections` | 최댓값이 설정값 초과, 5분 × 2회 | connection이 새거나 pool 설정이 맞지 않는다 | 아래 DB pool 알람과 같이 본다. api, worker의 pool 상한 합(10 + 5)과 비교한다 |
| `cloudfront-5xx-rate` | `AWS/CloudFront 5xxErrorRate` | 평균이 설정값 초과, 5분 × 2회 | 사용자가 보는 오류. origin(api) 장애도 여기 잡힌다 | 아래 API 5xx 알람과 api healthcheck를 본다. api가 멀쩡하면 CloudFront와 VPC origin 설정을 본다 |

## 2. 앱 지표 알람 (지표를 내보낸 뒤 Terraform에 더함)

| 알람 | 지표 | 조건 | 왜 | 첫 대응 |
|---|---|---|---|---|
| API p95 | `http.server.requests`(role=api) | 5분 동안 1초 구간을 넘는 요청이 5% 초과, 3회 연속 | 화면이 느려진다(architecture.md 2절 예산) | 느려진 `uri`를 찾고 같은 시각의 `hikaricp.connections.pending`, RDS CPU를 본다 |
| API 5xx | `http.server.requests`(status=5xx) | 5분 동안 5xx 비율 2% 초과, 또는 10건 이상 | 처리하지 못한 오류. 401·403·404는 세지 않는다 | `exception` tag와 같은 시각의 오류 로그(requestId)를 본다 |
| 제출·업로드 실패 | `http.server.requests`(uri가 과제 제출 `.../submission` 또는 `/api/uploads`, method=POST, status=5xx) | 5분 동안 1건 이상 | 학생이 제출하지 못했을 수 있다(SC-09). 4xx(마감, 형식)는 정상 거부라 세지 않는다 | 해당 requestId로 로그를 찾고, 제출 receipt가 남았는지 확인한다. 저장소(S3) 오류면 storage 상태를 본다 |
| 하트비트 gap | `astra.learning.heartbeat.gap` | 15분 동안 평균이 하트비트 주기의 2배(120초) 초과 | 하트비트가 늦게 도착해 적립이 끊긴다. 네트워크나 api 지연 | API p95와 같이 본다. 특정 시각에 몰리면 학교 network 상태를 확인한다 |
| session 강제 종료 급증 | `astra.learning.sessions.closed`(reason=lease_expired) | 15분 합이 평소의 3배 초과 | 하트비트가 끊겨 학습 session이 닫히고 있다 | 하트비트 gap 알람, api 5xx를 본다 |
| orphan upload | `astra.files.gc.backlog`(kind=expired_uploads) | 30분 동안 100 초과 | GC가 만료 업로드를 닫지 못하고 있다 | `astra.files.gc.failures`와 worker 로그의 `storage gc step failed`를 본다 |
| GC 실패 | `astra.files.gc.failures` | 30분 동안 3건 이상 | 지운 자료의 파생물이나 원본 객체가 남는다 | worker 로그에서 실패한 ID를 보고 S3 권한과 상태를 확인한다 |
| 검사 backlog | `astra.files.scan.backlog` | 30분 동안 50 초과 | 업로드가 quarantined로 묶여 첨부할 수 없다(ADR-0018) | 아래 검사기 장애 알람과 clamav container 상태를 본다 |
| 검사기 장애 | `astra.files.scan.results`(result=unavailable) | 10분 동안 5건 이상 | clamd에 닿지 못한다. 검사를 다시 시도하는 동안 업로드가 묶인다 | `docker compose ps clamav freshclam`, clamd 메모리, 서명 volume을 본다 |
| 문서 변환 실패 | `astra.files.rendition.conversions`(outcome=failed) | 1시간 동안 5건 이상, code가 `unavailable`·`failed`·`storage`면 1건 이상 | 자료 쪽 이미지와 요약이 만들어지지 않는다. `invalid_pdf`, `encrypted` 같은 입력 거부는 많을 때만 본다 | converter container 상태와 worker 로그를 본다 |
| 요약 대기열 | `astra.summary.jobs.oldest.age` | 15분 동안 900초 초과 | 요약 요청이 시작되지 않는다 | worker가 떠 있는지, `astra.summary.jobs`(state=running)가 멈춰 있는지 본다 |
| 요약 실패 | `astra.summary.jobs.finished`(state=failed) | 1시간 동안 5건 이상 | 모델 제공자나 변환 문제 | `code` tag(provider, file, interrupted)와 `astra.llm.calls`의 outcome을 본다 |
| AI token 사용 | `astra.llm.tokens` | 하루 합이 예산(token 수 × 단가)을 넘을 때 | 비용 초과(SC-09) | `kind` tag(page_summary, study_chat)로 어느 쪽인지 본다. 요청한 과목과 작업 수를 summary_jobs, study_chat_exchanges에서 확인한다. 필요하면 `ASTRA_LLM_API_KEY`를 빼 요약 생성을 멈춘다 |
| 학습 대화 대기열 | `astra.studychat.exchanges.oldest.age` | 5분 동안 120초 초과 | 학생 질문에 답이 시작되지 않는다(ADR-0032). 질문은 몇 초 안에 답하는 흐름이다 | worker가 떠 있는지, `astra.studychat.exchanges`(state=running)가 멈춰 있는지, `astra.llm.calls`(kind=study_chat)의 시간을 본다 |
| 학습 대화 실패 | `astra.studychat.exchanges.finished`(state=failed) | 1시간 동안 code가 `provider`·`internal`·`interrupted`인 것이 10건 이상 | 모델 제공자 장애나 worker 중단으로 답을 못 했다. `budget`, `forbidden`, `consent`, `disabled`, `invalid_output`은 정책 거부나 출력 검사라 많을 때만 본다 | `astra.llm.calls`(kind=study_chat)의 outcome과 worker 로그(exchangeId)를 본다 |
| 문서 변환 대기열 | `astra.files.rendition.jobs.oldest.age` | 15분 동안 600초 초과 | 미리보기와 요약 입력이 만들어지지 않는다(ADR-0033) | converter container 상태, `astra.files.rendition.jobs`(state=converting), 위 문서 변환 실패 알람을 본다 |
| 문서 변환 중단 | `astra.files.rendition.interrupted` | 1시간 동안 3건 이상 | 변환하던 worker가 멈추거나 lease 안에 끝내지 못했다. outcome=failed면 시도 수를 다 써 닫혔다 | worker 재시작 기록과 메모리, converter 응답 시간을 본다 |
| 영상 변환 대기열 | `astra.files.video.jobs.oldest.age` | 30분 동안 1800초 초과 | 올린 강의 영상이 재생 가능해지지 않는다(ADR-0016) | worker가 떠 있는지, `astra.files.video.jobs`(state=running)가 멈춰 있는지, transcoder container 상태를 본다 |
| 영상 변환 실패 | `astra.files.video.transcodes`(outcome=failed), `astra.files.video.transcodes.interrupted`(outcome=failed) | 1시간 동안 code가 `unavailable`·`failed`·`timeout`·`internal`이거나 중단으로 닫힌 것이 1건 이상. `invalid_media`, `too_large`, `too_long`은 많을 때만 | 교수자가 올린 영상이 게시되지 않는다 | transcoder container 상태와 worker 로그(jobId)를 보고, outcome=retry가 함께 늘었는지 본다 |
| 파일함 ZIP 대기열 | `astra.files.zip.jobs.oldest.age` | 15분 동안 600초 초과 | 일괄 다운로드가 시작되지 않는다(ADR-0028) | worker가 떠 있는지, `astra.files.zip.jobs`(state=building)가 멈춰 있는지 본다 |
| 파일함 ZIP 실패 | `astra.files.zip.builds`(outcome=failed, code=build_failed 또는 interrupted) | 1시간 동안 3건 이상 | 저장소 오류나 worker 중단으로 ZIP을 만들지 못했다. `item_unavailable`은 사용자가 항목을 지운 정상 거부다 | S3 상태와 worker 로그(jobId)를 본다 |
| 남은 multipart upload | `astra.files.upload.stream.aborted` | 하루 합이 10건 이상 | api process가 영상 업로드 도중 자주 멈춘다. 남은 part는 저장 비용이 든다 | api 재시작 기록과 메모리, 업로드 중 배포가 있었는지 본다 |
| 멱등 대기 초과 | `astra.idempotency.requests`(outcome=in_progress) | 10분 동안 20건 이상 | 같은 명령이 오래 걸려 재전송이 409를 받는다 | 해당 `operation`의 처리 시간과 DB 잠금 대기를 본다 |
| 감사 기록 누락 | `astra.audit.write.failures` | 1건 이상 | transaction 밖 감사 기록이 빠졌다(ADR-0012) | DB 상태를 보고, 빠진 시각의 요청 로그(requestId)를 보존한다 |
| 요청 제한 급증 | `astra.ratelimit.rejections` | 10분 동안 `operation`별 합이 평소의 5배 초과 | 자동화된 요청이나 화면의 반복 호출 | 연산별로 보고, 화면 버그인지 남용인지 로그로 가린다 |
| DB pool 대기 | `hikaricp.connections.pending` | 5분 동안 최댓값 1 이상이 3회 연속 | connection을 기다리는 요청이 생겼다. 5초를 넘으면 실패한다 | 느린 query, 오래 잡은 transaction(멱등 대기 등)을 본다 |
| worker 지표 끊김 | `astra.summary.jobs`(role=worker) | 10분 동안 값이 없음 | worker가 멈추면 backlog gauge가 끊긴다. 요약, 검사, GC가 모두 멈춘다 | `docker compose ps worker`, worker healthcheck, 마지막 로그를 본다 |

## 3. 아직 없는 알람

| 대상 | 이유 |
|---|---|
| 미완료 이벤트 backlog | event publication registry를 두지 않았다(ADR-0034 6번) |
| 외부 uptime check | 9절. 수신할 곳(Slack 등)과 함께 정한다 |
