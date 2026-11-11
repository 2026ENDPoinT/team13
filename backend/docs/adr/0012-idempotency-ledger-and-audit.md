# ADR-0012 멱등 원장, 감사 기록, requestId를 `shared`에 둔다

## 상태
채택 (2026-11-11)

## 맥락
서버 계약 SC-05는 일반 명령의 멱등 원장을 정했다. `(principal, operation, key)`가 unique이고, 지문(body, 대상 scope, If-Match 같은 전제 조건), `reserved/completed` 상태, receipt, 보존기한을 저장한다. 완료한 key를 같은 지문으로 다시 보내면 version과 마감을 다시 검사하지 않고 receipt를 돌려준다. 예약, 업무 write, receipt는 한 번에 commit하고, 실패하면 예약도 남지 않는다.

SC-09는 일반 명령마다 requestId, actor, operation, object scope, 결과 코드, 시각을 감사 기록으로 남기고 본문은 남기지 않도록 정했다.

지금까지 멱등은 업로드만 자체 key(`upload_sessions.client_request_id`)로 지켰다. V1의 `posts`, `messages`도 테이블마다 `client_request_id`를 두었다. 과제 제출, 수강신청처럼 중복이 문제 되는 명령이 곧 생기고, 모두 같은 규칙이 필요하다. 감사 기록과 requestId는 없었다.

## 결정
1. **requestId**: 모든 요청에 서버가 UUID를 만든다. security filter보다 먼저 실행하는 `shared.RequestIdFilter`가 응답 header `X-Request-Id`, 로그 MDC `requestId`, problem JSON의 `requestId`에 같은 값을 넣는다. filter 단계에서 거부한 401, 403도 같다. client가 보낸 `X-Request-Id`는 쓰지 않는다. client가 고른 값을 상관 key로 쓰면 다른 요청의 로그와 감사 기록에 섞어 넣을 수 있기 때문이다.
2. **감사 기록**: `shared.audit.AuditLog`가 `audit_events`에 행위자 사용자와 session, 연산 이름, 대상 종류와 ID, 경로의 과목, 결과 코드, requestId, DB 시각을 쓴다. 이름과 코드는 소문자 식별자만 받는다. 요청 본문, 파일 이름, 자유 텍스트를 넣을 자리가 없다.
   - 부르는 쪽의 transaction 안이면 같은 transaction에 쓴다. 기록에 실패하면 명령도 되돌린다.
   - transaction 밖이면 바로 commit하고, 실패해도 요청은 실패시키지 않고 경고 로그만 남긴다. 내려받기, HLS key처럼 쓰기가 없는 연산이 감사 저장소 장애로 멈추지 않게 하기 위해서다.
   - 지금 남기는 연산: break-glass 로그인 성공과 실패, 로그아웃, 업로드와 업로드 삭제, 원본 내려받기, 강의 자료 등록, 요약 작업 요청·취소·재시도·게시, 영상 재생 허가, HLS key. 거부한 요청(404)은 남기지 않는다. 존재하지 않는 경로를 두드리는 요청이 쓰기 부하가 되지 않게 하기 위해서다.
   - 로그인 실패는 계정을 특정하지 않고 결과 코드만 남긴다. 계정별 실패 이유는 지금처럼 `login_events`에 있다.
3. **멱등 원장**: `shared.idempotency.Idempotency.execute`가 `idempotency_ledger`를 쓴다. 컨트롤러는 권한 판정과 입력 검사를 마친 뒤 `Idempotency-Key` header, 지문을 만들 값, 명령을 넘긴다.
   - 지문은 검사한 본문, 경로의 대상 ID, 전제 조건을 속성 이름 순서로 정렬한 JSON의 SHA-256이다. 원문 byte가 아니라 검사한 값으로 만들어서 공백이나 필드 순서만 다른 재전송은 같은 요청이다.
   - 처음 보는 key는 한 transaction에서 `reserved`로 넣고, 명령을 실행하고, 응답 본문을 receipt로 저장하며 `completed`로 바꾼다. 명령이 실패하면 예약도 rollback된다.
   - 같은 key의 처리 중인 요청은 InnoDB unique 검사에서 그 transaction이 끝나기를 기다린다. 이 구간만 `innodb_lock_wait_timeout`을 줄여 기본 3초까지 기다리고, 넘으면 409 `idempotency_in_progress`다. 앞 요청이 commit되면 receipt를, rollback되면 새 명령으로 실행한다.
   - 같은 key와 같은 지문이면 저장한 receipt를 그대로 돌려준다. 다른 지문이면 409 `idempotency_conflict`다. 권한 판정은 원장보다 먼저 하므로 권한을 잃은 사용자는 receipt도 받지 못한다(404).
   - `reserved`는 commit되지 않으므로 다른 요청에는 보이지 않는다. 예약을 먼저 commit해야 하는 비동기 명령이 생기면 그 명령에서 상태 전이를 더한다.
   - 보존기한은 24시간이다. worker가 15분마다 지난 항목을 `FOR UPDATE SKIP LOCKED`와 READ COMMITTED로 지운다. worker가 둘이어도 같은 행을 두 번 다루지 않는다. 지우기 전에 지난 key가 다시 오면 그 행을 지우고 새 명령으로 실행한다.
   - header는 선택이다. 없으면 원장 없이 한 transaction으로 실행한다. 지금의 frontend가 header를 보내지 않기 때문이다. key를 필수로 할지는 수강신청, 제출 같은 명령을 만들 때 그 명령에서 정한다.
   - 첫 사용처는 `POST /api/courses/{courseId}/materials`와 `POST /api/courses/{courseId}/files/{fileId}/summary-jobs`다. 업로드는 본문을 transaction 밖에서 S3에 먼저 쓰므로 자체 key를 유지한다. key 형식은 업로드와 같다(`[A-Za-z0-9._-]` 1~100자).
4. **목록 query 규칙**(page size 상한, cursor, 정렬 key allowlist, SC-09)은 지금 만들지 않는다. 지금 목록은 요약 작업 20개(고정 상한, ID 내림차순)와 과목 안의 자료처럼 짧고 정렬 매개변수가 없다. 첫 페이지 목록(공지, 게시판)과 함께 `shared`에 만든다.

## 결과와 트레이드오프
- 다음 명령은 `Idempotency.execute` 한 번으로 SC-05의 순서를 따른다. 테이블마다 `client_request_id`를 더하지 않는다.
- receipt는 사용자가 이미 받은 응답 본문이라 자료 제목과 파일 이름이 원장에 24시간 남는다. 같은 사용자의 같은 key로만 읽힌다.
- 같은 key를 기다리는 요청은 그동안 DB connection 하나를 잡는다. 대기 시간을 짧게 둔 이유다.
- transaction 밖의 감사 기록은 장애 때 빠질 수 있다. 지금은 경고 로그로만 안다. 빠진 수를 지표로 내는 것은 9절 관측 작업에서 한다.
- `audit_events`는 지우지 않는다. 보존 기간은 기관 정책을 받아 정한다(SC-07).
- SC-09의 policy version은 남기지 않는다. 권한 정책에 판 번호가 아직 없다.
- HLS key와 재생 허가는 재생마다 행을 쓴다. 사용자 수백 명 규모에서는 문제가 아니고, 부하 시험(11절 3단계)에서 다시 본다.
