# ADR-0039 일시는 기관 시간대의 벽시계로 두고, DB session과 JVM을 그 시간대에 고정한 뒤 시작할 때 확인한다

## 상태
채택 (2026-11-12)

## 맥락
마감 판정은 DB 시계(`SELECT CURRENT_TIMESTAMP` → `LocalDateTime`)로 한다(SC-06). API가 받고 돌려주는 일시(`openAt`, `closeAt`, `lateUntil`, `serverTime` 등)는 offset 없는 `LocalDateTime`이고 `DATETIME` 열에 들어간다. 그런데 시간대를 정한 곳이 하나도 없었다. JDBC URL, Hikari, JVM, 이미지, compose, Terraform(RDS) 어디에도 없다. MySQL 공식 이미지와 RDS의 기본 시간대는 UTC다.

프론트엔드와 백엔드를 합쳐 로컬에서 실행했을 때 실제 시각은 01:27 KST였고, `serverTime`과 표본 마감은 전날 16:27, 화면은 '오후 4:27'이었다. 운영에서는 교수자가 한국 벽시계로 넣은 마감을 UTC 벽시계와 비교하게 된다. 열림, 마감, 지각, 결과 공개, 출석, 퀴즈 마감 판정이 모두 9시간 어긋난다. `DATE` 경계(캘린더의 오늘, 익명 설문 감사 기록의 일 단위 시각, LLM 하루 예산, 로그인 기록 7일)는 09:00 KST에 넘어간다.

`Instant` 필드(생성 시각, session 인증 시각 등)는 `getTimestamp(...).toInstant()`로 읽는다. Connector/J의 연결 시간대(기본은 JVM 기본 시간대)와 DB session 시간대가 같을 때만 참 시각이다. JVM이 KST인 개발 PC와 UTC DB에서는 9시간 틀리고, 그 값과 `Instant.now()`를 비교하는 session 만료 판정도 틀린다.

제품은 1개 기관, 한 시간대다([architecture.md](../architecture.md) 1절).

### 대안
| | (a) 기관 벽시계 | (b) UTC 저장, offset 있는 API 필드 |
|---|---|---|
| 모델 | `DATETIME`, `LocalDateTime`, `LocalDate`, `LocalTime`은 모두 기관 시간대의 벽시계다. DB session과 JVM 기본 시간대를 그 시간대로 고정한다. `Instant`는 절대 시각이다 | `DATETIME`은 UTC 벽시계다. API 필드는 `OffsetDateTime`/`Instant`로 바꾸고 경계에서 변환한다 |
| 코드 | 판정과 SQL(`CURRENT_TIMESTAMP`, `CURRENT_DATE`, `INTERVAL`, `TIMESTAMPDIFF`, `DAYOFWEEK`)이 고치지 않아도 맞는다. 설정 한 곳, 시작 확인 하나, 요청 JSON 검사 하나를 더한다 | `src/main` 107개 파일의 `LocalDateTime` 824곳을 바꾼다. 날짜 판정(캘린더의 오늘, 일 단위 감사, LLM 하루 예산, 시간표 요일, 수업 시각 `LocalTime`)은 여전히 기관 시간대가 필요해 SQL마다 `CONVERT_TZ`를 넣거나 Java에서 계산한다. 시간대 개념이 없어지지 않고 모든 query로 퍼진다 |
| API 계약 | 문자열 형식이 그대로다. 기관 시간대를 알리는 필드 하나(`timeZone`)를 더한다 | 모든 일시 필드의 형식이 바뀐다 |
| 프론트엔드 | `datetime-local` 입력과 표시가 이미 벽시계 문자열이라 한국 브라우저는 그대로 맞다. 한국 밖 브라우저의 보정만 남는다 | 모든 화면의 파싱, 입력 변환, D-day 계산을 고친다 |
| 표본·기존 데이터 | 표본은 DB 시계 SQL로 넣으므로 다시 넣으면 맞다. 운영 데이터는 아직 없다 | 같다 |
| 얻는 것 | 1개 기관에 필요한 것 전부 | 여러 시간대 기관, 서머타임. 제품 전제에 없다 |

(a)를 안전하지 않게 만드는 근거는 찾지 못했다. 서머타임이 없는 시간대에서는 벽시계 시각과 절대 시각이 1:1이다.

## 결정
1. **모델은 (a)다.** `DATETIME` 열과 offset 없는 일시는 기관 시간대의 벽시계다. 설정은 `astra.time.zone`(환경 변수 `ASTRA_TIME_ZONE`) 하나이고 기본은 `Asia/Seoul`이다(`shared.time.InstitutionTime`). `Instant`는 절대 시각이고 JSON에서는 `Z`가 붙은 UTC 문자열이다. 판정의 '지금'은 계속 DB 시계다.
2. **지원하는 시간대는 지금부터 offset이 바뀌지 않는 시간대다.** `Asia/Seoul`, `Asia/Tokyo`, `UTC` 같은 IANA 이름과 `+09:00` 같은 고정 offset을 받는다. 서머타임이 있는 시간대(`Europe/Berlin` 등)는 시작하지 않는다. 벽시계 `DATETIME`은 가을의 한 시간이 겹치고 봄의 한 시간이 비어 순서와 길이를 정할 수 없고, session에 이름 시간대를 보내려면 DB에 시간대 table이 있어야 하기 때문이다. DB에는 이름이 아니라 지금 offset을 보낸다. 그래서 시간대 table이 없는 MySQL에서도 같다.
3. **DB session과 driver는 Connector/J 연결 속성으로 고정한다.** `connectionTimeZone=GMT+09:00`과 `forceConnectionTimeZoneToSession=true`를 Hikari의 `data-source-properties`로 넣는다. pool의 연결마다 `SET SESSION time_zone='+09:00'`이 실행되어 `CURRENT_TIMESTAMP`, `CURRENT_DATE`, `DATETIME` 기본값이 기관 벽시계가 된다. driver의 `Timestamp` 변환도 같은 offset을 쓴다.
   - JDBC URL 속성으로 넣지 않았다. 운영 URL은 환경 변수(`DB_URL`)로 받고, 테스트와 로컬 실행의 URL은 Testcontainers가 만든다. URL에 넣으면 고칠 곳이 여럿이 되고 하나를 빠뜨려도 모른다.
   - Hikari `connection-init-sql`을 쓰지 않았다. session만 바꾸고 driver의 변환 시간대는 JVM 기본값으로 남는다.
4. **JVM 기본 시간대는 Spring이 설정을 읽은 직후에 바꾼다.** `shared.time.InstitutionTimeEnvironment`(`EnvironmentPostProcessor`, `META-INF/spring.factories`)가 bean과 로그 설정이 생기기 전에 JVM 기본 시간대와 위의 연결 속성을 정한다. api, worker, 로컬 실행(`TestStackBackendApplication`), 모든 테스트 context가 `SpringApplication`으로 뜨므로 이 한 곳이 모두에 적용된다. 이미지 entrypoint는 `-Duser.timezone=${ASTRA_TIME_ZONE}`으로 JVM을 처음부터 그 시간대로 띄운다. converter와 transcoder의 main은 시각을 읽지 않는다(제한 시간의 `System.nanoTime`뿐).
5. **시작할 때 세 값이 같은지 본다.** `shared.time.InstitutionTimeCheck`가 pool 연결의 `@@session.time_zone`과 `TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())`, JVM 기본 시간대의 규칙, 설정한 시간대를 비교한다. 하나라도 다르면 시작하지 않고, 세 값을 오류 문장에 남긴다. 맞으면 같은 세 값을 INFO 로그 한 줄로 남긴다. 비밀값은 없다. web server와 worker 일정은 모든 bean이 생긴 뒤에 시작하므로 이 확인보다 먼저 요청이나 작업을 받지 않는다. URL의 `connectionTimeZone`이 다르거나(URL 속성이 이긴다) JVM option이 바뀌어도 여기서 멈춘다.
6. **JSON 계약.** `LocalDateTime` 필드는 offset 없는 벽시계 문자열(`2026-11-12T10:00:00`)로 주고받는다. 요청의 `LocalDateTime`에 `Z`가 붙으면 400이다. Jackson은 기본으로 `Z`를 말없이 떼어 UTC 시각을 기관 시각으로 읽는다. `+09:00` 같은 offset은 원래부터 400이다. 기관 시간대는 `GET /api/auth/methods`의 `timeZone`(IANA ID) 한 곳에서 알린다. 프론트엔드가 앱을 띄울 때 늘 부르고 로그인이 필요 없다.
7. **배포.** compose의 `mysql` 서비스(`TZ`)와 RDS parameter group(`time_zone`)도 같은 이름으로 둔다. Terraform의 `institution_time_zone` 하나가 `ASTRA_TIME_ZONE`과 parameter를 함께 정한다. 앱은 이 값에 기대지 않는다. 운영자가 mysql client로 본 `NOW()`가 저장된 값과 같은 벽시계가 되게 하는 용도다.

## 결과와 트레이드오프
- 시각을 읽고 쓰는 곳을 모두 다시 보았다. 고친 곳은 설정과 경계뿐이고 판정 코드는 그대로 맞다.

  | 사용처 | 위치 | 판단 |
  |---|---|---|
  | DB 시계 `SELECT CURRENT_TIMESTAMP` → `LocalDateTime` | `LearningClock`, `QuizClock`, `AssignmentAccess`, `SurveyAccess`, `CatalogQueries`, `NotificationRecords`, `MessageRepository`, `NoticeRepository`, `CalendarService`, `HomeService`, `LearningAuth`, `TimetableController` | session 고정으로 기관 벽시계다. 그대로 |
  | SQL의 시각 연산 | 멱등 원장 만료, 업로드·ZIP·변환·검사·요약·학습 대화 lease, `scheduler_locks`, 알림 예약, session `last_seen_at` | 같은 session 시계와 같은 시간대의 열을 비교한다. 그대로 |
  | `CURRENT_DATE`와 날짜 자르기 | 감사 기록의 일 단위 시각(`AuditLog`), 익명 설문의 참여·응답 시각(`SurveyService`), LLM 하루 예산(`UsageLedger`), 캘린더·시간표의 오늘 | 기관 자정에 넘어간다 |
  | `TIMESTAMPDIFF` 나이 지표 | `FilesBacklog`, `SummaryBacklog`, `StudyChatBacklog` | 같은 시간대 두 값의 차다. 그대로 |
  | `DATETIME` → `Instant` | `getTimestamp().toInstant()`(`SessionStore`, `LearningController`, `FileboxRecords`, `ZipJobRecords`, `UploadRecords`, `SummaryJobRepository`, `StudyChatRepository`, `ConsentRepository`), `Timestamp.valueOf(LocalDateTime).toInstant()`(`FileboxRecords.instant`) | driver 연결 시간대 = JVM 기본 = 기관 offset이고 시작 확인이 보장한다. 참 시각이다 |
  | `Instant.now()` | `SessionAuthenticationFilter`(위 `Instant`와 비교), `DeliveryCookieService`, `FilePreviewController`, `OriginDeliveryController`(cookie 만료 epoch 초), `Totp`(epoch 초), `ObjectStreams`(S3 `LastModified`와 비교) | 시간대와 관계없다 |
  | OIDC | `Clock.systemUTC()`, `iat`·`exp`·`auth_time`, 로그인 쿠키 만료 | epoch 초끼리 비교한다. `LocalDateTime`을 거치지 않는다 |
  | 목록 cursor | 파일함 날짜 정렬(`ItemSort`)과 다른 keyset cursor | `DATETIME` 값을 벽시계 문자열 그대로 담아 같은 열과 비교한다. 시간대와 관계없다 |
  | 표본(`local/*Seed`) | `CURRENT_TIMESTAMP`·`CURRENT_DATE`·`DAYOFWEEK` 연산, 고정 수업 시각 | DB 시계 연산이라 기관 벽시계로 들어간다. 그대로 |

- 서머타임 시간대는 쓸 수 없다. 쓰려면 (b)로 옮겨야 한다.
- 대한민국이 offset을 바꾸면(tzdata 갱신) JDK를 올려 다시 시작해야 하고, 바뀌기 전후 값의 해석은 그때 정한다. `Asia/Seoul`의 1988년 이전 offset(서머타임, +08:30)은 DB 변환이 지금 offset을 써서 JVM 규칙과 다를 수 있다. 그 시기의 `DATETIME` 값은 없다.
- 프론트엔드가 할 일: offset 없는 문자열을 `timeZone`의 벽시계로 읽는다. `new Date("2026-11-12T10:00:00")`은 브라우저 시간대로 읽으므로 한국 밖 브라우저에서 틀린다. 그런 브라우저에서는 `timeZone`으로 변환하거나 시간대를 함께 표시한다. 입력은 `datetime-local` 값을 그대로 보내고 `toISOString()`은 보내지 않는다(400). `Instant` 필드(`Z`)는 `Date`로 읽으면 된다.
- 이미 UTC 벽시계로 쌓인 DB가 있으면 `DATETIME` 열을 offset만큼 옮기는 일회성 SQL이 필요하다. 도입 전이라 운영 데이터는 없고 로컬 DB와 표본은 다시 만들면 맞으므로 마이그레이션은 두지 않았다.
- `InstitutionTimeTests`가 UTC 그대로인 MySQL container로 확인한다. `serverTime`이 기관 시각과 같고, 기관 시각으로 1분 뒤 마감은 열려 있고 1분 전 마감은 닫혀 있으며, `Z`가 붙은 마감은 400이고, `Instant` 생성 시각이 참 시각이며 저장 값이 그 기관 벽시계이고, 캘린더의 오늘과 `CURRENT_DATE`가 기관 자정에 바뀌고, 시간대 속성이 없는 연결과 다른 JVM 시간대를 시작 확인이 거부한다.
