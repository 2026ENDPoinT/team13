# ADR-0022 익명 설문은 참여 원장과 응답 저장부를 잇지 않고, 완료의 멱등은 참여 원장의 key로 지킨다

## 상태
채택 (2026-11-11)

## 맥락
원본 ASTRA의 설문 완료(`survey_qtn_end.acl`, EP-0099)는 응답 본문과 `ud`, `ky`를 함께 보내고, 익명 설문을 어떻게 저장하는지 알 수 없다(ASSESS-08). 서버 계약은 다음을 요구한다.

- 설문 완료는 응답자 unique와 answer set 검증으로 처리하고, 일부만 답한 세트를 completed로 표시하지 않는다(SC-05 표, CHG-04).
- SC-05-A: 참여 원장에는 `(survey, principal, key)`와 참여 revision·최소 결과만 보존한다. actor에 연결된 body fingerprint, answer set ID, 답변 body, 정밀 시각, 추적 ID를 저장하지 않는다. 동일 key는 body가 달라도 최초 참여 결과만 반환하고 답변을 변경하지 않는다.
- SC-09: 익명 설문에는 actor 로그와 answer-set ID·body·hash·연결 가능한 requestId를 함께 남기지 않는다. 참여 감사는 최소 참여 결과만 보존한다.
- 결과는 공개 이후에만, 응답 수 k 미만 셀은 억제한다(EP-0097, ASSESS-07, N-09).

공용 멱등 원장(ADR-0012)은 principal마다 요청 지문(본문의 SHA-256)과 receipt를 24시간 저장한다. 익명 설문의 완료를 여기에 넣으면 답에서 나온 값이 사용자와 이어진다. V1 스키마의 `survey_participations.current_response_id`도 참여에서 응답 세트로 가는 FK라서, 채우면 익명이 화면 표시일 뿐이 된다.

## 결정
1. **두 저장부.** `survey_participations`(참여 원장)는 누가 완료했는지를, `survey_responses`·`survey_answers`·`survey_answer_options`(응답 저장부)는 무엇을 답했는지를 가진다. `current_response_id`는 쓰지 않고 NULL로 둔다. 익명 설문의 응답 세트는 `respondent_user_id`가 NULL이고, 응답 쪽 모든 시각(`submitted_at`, `created_at`)과 참여의 `completed_at`, `created_at`은 DB 시계를 일 단위로 자른 값이다. 응답 쪽 테이블에는 key, requestId, session을 담을 컬럼이 없다. 실명 설문은 `respondent_user_id`에 응답자를 넣고 정밀 시각을 쓴다.
2. **익명 설문의 멱등.** 공용 원장을 쓰지 않는다. `Idempotency-Key`는 필수이고 참여 행의 `completion_key`에 저장한다. 순서: 권한 → 형식 검사 → 참여 행 조회(있으면 key가 같을 때 처음 결과, 다르면 409 `already_completed`. 기간과 답은 다시 보지 않는다) → READ COMMITTED transaction에서 설문 행 `FOR SHARE` → DB 시각 → 응답 방식·공개·기간 판정 → 문항 정의로 답 전체 검사 → 참여 행 → 응답 세트 → 감사 기록 → commit. 동시 요청은 `(survey_id, user_id)` 유일 제약에서 하나만 이기고, 진 요청은 rollback한 뒤 이긴 참여 행의 key로 같은 규칙을 따른다. receipt는 `{surveyId, state, revision}`뿐이다.
3. **실명 설문의 멱등.** 일반 명령처럼 공용 원장(SC-05)을 쓴다. 같은 key에 다른 답이면 409 `idempotency_conflict`, 다른 key로 다시 완료하면 409 `already_completed`다. 지문과 receipt가 사용자와 이어져도 실명 설문은 이미 응답자를 저장하므로 새로 드러나는 것이 없다.
4. **재응답은 없다.** `allow_retake_yn`은 0으로만 쓰고 참여 revision은 1이다. 익명 응답 세트를 교체하려면 응답 세트와 사용자를 잇는 가명 token(HMAC)과 그 key의 별도 권한이 필요하다. SC-05-A가 이를 강한 익명성과 함께 별도 설계 결정으로 남겼으므로 이번에는 만들지 않는다. 그래서 요청에 `expectedParticipationRevision`을 받지 않는다.
5. **감사 기록.** 익명 설문 완료는 `AuditLog.successWithoutRequestId`로 행위자, session, `survey.complete`, 대상 `survey`, 설문 ID, 과목, `ok`만 남긴다. requestId가 없고 응답 세트 ID나 답에서 나온 값을 넣지 않는다. 실명 설문 완료와 등록·수정은 일반 감사 기록이다. 응답 처리 코드는 로그를 남기지 않는다.
6. **문항 교체와 완료의 순서.** 수정은 설문 행을 revision 비교 UPDATE로 배타 잠금하고, 완료는 같은 행을 공유 잠금한다. 완료한 사람이 있으면 문항 교체와 응답 방식 변경은 409 `survey_has_responses`다. 완료 transaction은 잠근 뒤 응답 방식을 다시 읽고, 처음 판정과 다르면 409 `survey_changed`다. 실명으로 받은 응답이 익명 결과에 섞이거나 그 반대가 되지 않는다.
7. **결과 공개와 최소 인원 k = 5.** 판정은 `Survey.withheldReason`, `Survey.suppresses` 하나다.
   - 익명 설문은 담당자에게도 응답 기간이 끝난 뒤에만, 응답자가 5명 이상일 때만 집계를 내보낸다. 답한 사람이 5명 미만인 문항(선택 문항)은 가린다.
   - 응답자(학생, 알림마당 대상자)는 설문이 결과 공개를 켰고 공개 일시가 지났고 마감한 뒤에만 결과를 받으며, 실명 설문이어도 같은 5명 규칙을 따른다.
   - 실명 설문의 담당자는 기간 중에도 집계를 본다. 누가 무엇을 답했는지는 어떤 API로도 내보내지 않는다.
   - 서술형 답변은 행 ID(입력 순서)가 아니라 문구 순서로 쪽을 나눠 내보낸다.
   - k = 5는 V1의 `result_min_cell_k` 기본값과 ASSESS-07의 예시 값이다. 강의실 하나가 20~40명이라 보기별 셀 억제까지 하면 대부분의 결과가 가려진다. 그래서 설문·문항 단위로 5명 이상일 때만 내보낸다. 기간 중 공개를 막은 것은 응답 직후 결과의 차이로 한 사람의 답을 알아내는 것을 막기 위해서다.
8. **알림마당 설문.** 학습활동이 없는 설문은 `survey_targets`로 대상을 정한다. `all`(모두), `student`(session 역할이 학생), `staff`(교수, 조교, 직원), `grade`(그 학년 학생, `identity.api.UserGrades`)만 맞고, 대상 행이 없으면 아무에게도 보이지 않는다. break-glass 계정은 대상이 아니다. 만드는 API는 없다.
9. **응답 저장부의 행 ID는 임의 값이다**(보안 검토 M4, V34). `survey_responses`, `survey_answers`, `survey_answer_options`의 PK는 AUTO_INCREMENT가 아니고, 응용이 `SecureRandom`으로 만든 63bit 양의 정수다. 순번이면 참여 원장의 ID 순서(사용자가 있다)나 감사 기록 순서와 응답 세트, 답변, 고른 보기의 ID 순서를 맞춰 누가 무엇을 답했는지 알 수 있기 때문이다. 하위 행의 순번도 같은 순서를 드러내므로 셋 모두 바꾼다. 타입이 `BIGINT UNSIGNED` 그대로라 FK와 결과 집계 query, index는 바뀌지 않는다. 실명 설문도 같은 방식을 쓴다. 익명 설문 완료의 감사 기록은 requestId 없이, 발생 일시를 DB 시계의 일 단위로 자른 값으로 남긴다(`AuditLog.successWithoutRequestId`).
   - 기각: 응답 세트를 완료 transaction에서 떼어 임시 보관소에 두고 worker가 모아 순서를 섞어 넣는 방식. 답이 worker가 돌 때까지 결과에 들어가지 않고, 완료 응답 뒤에 일부만 저장되는 실패 경로와 보관소의 정리가 생긴다. 보관소 행 자체에도 순서가 남는다. 같은 transaction에 넣고 키만 임의 값으로 하면 SC-05의 원자성(참여와 응답이 함께 commit)이 그대로다.
   - 기각: `BINARY(16)` UUID 키. 63bit 임의 정수로 충분히 겹치지 않고(겹치면 PK 제약으로 그 완료만 rollback), 컬럼 타입과 FK를 바꾸지 않아 기존 query와 index 크기가 그대로다.

## 결과와 트레이드오프
- 남는 재식별 위험은 SC-05-A의 문장 그대로다. "응답 저장부의 가명 token/HMAC과 접근제어는 별도 권한이며, 키 보유 운영자에 의한 재식별 가능성 때문에 완전한 unlinkability를 보장하지 않는다. 이 제안의 '익명'은 교수자에게 비식별인 범위다. 강한 익명성이 필요하면 재응답·본인 확인 기능과 함께 별도 설계 결정을 받아야 한다." 저장된 행만 읽는 사람(DB 조회 권한, 논리 백업 보유자)은 9번 뒤로 행 ID, 시각, requestId 어느 것으로도 참여와 응답을 맞추지 못한다. 다음은 남고, 이 결정의 범위 밖이다.
  - binlog, redo·undo log, general log, slow log, performance_schema 같은 DB 기록이나 data file을 볼 수 있는 사람(물리 접근, 물리 백업)은 참여 행과 응답 행을 같은 transaction으로, 기록된 순서대로 본다. 이 경로를 막으려면 응답을 다른 저장소와 다른 권한으로 옮기는 별도 설계가 필요하다.
  - 응답자가 적을 때 같은 날 완료한 사람이 한 명뿐이면 일 단위 시각으로 맞춰진다.
  - k−1명이 서로의 답을 맞춰 보면 나머지 한 사람의 답을 알 수 있다. k를 키우는 것 말고는 막지 못한다.
- 익명 설문의 같은 key 재전송은 보존기한 없이 처음 결과를 돌려받는다. 실명 설문은 공용 원장의 보존기한(24시간)이 지나면 같은 key도 409 `already_completed`다.
- 답을 고치거나 다시 응답할 수 없다. 잘못 답한 학생을 위한 재응답은 4번의 별도 결정이 필요하다.
- 기간 중 결과 확인(익명)과 보기별 응답자 명단은 제공하지 않는다. 담당자가 익명 설문의 진행 상황으로 알 수 있는 것은 완료한 사람 수뿐이다.
- 할 일의 완료 판정은 참여 원장(`survey_participations`의 완료)만 읽는다. 익명 설문도 완료한 사람의 할 일에서 빠지지만, 응답 저장부는 읽지 않으므로 이 판정이 응답과 사용자를 잇지 않는다.
- 분기(꼬리) 문항, 쪽 나누기, 기타 입력 보기(`allow_etc_input_yn`), 알림마당 설문 작성, 결과 내보내기 파일은 이 결정 밖이다.
