# ADR-0015 과제 최종 제출은 바뀌지 않는 버전이고, 제출 버전 행이 멱등 receipt다

## 상태
채택 (2026-11-11)

## 맥락
원본 ASTRA의 과제 제출(`report_insert.acl`, EP-0077)은 본문 HTML과 파일 번호 목록을 한 번에 보내고, 재시도·다중 탭·마감 판정을 서버가 어떻게 하는지 알 수 없다(ASSESS-02~04, SEC-19). 서버 계약은 다음을 요구한다.

- 최종 제출은 버전과 첨부와 서버 접수 시각을 한 transaction으로 남긴다. 같은 Idempotency-Key는 같은 receipt를 돌려주고, 같은 key에 다른 요청이면 충돌이다(SC-05).
- 첨부는 부모 저장과 같은 transaction에서 확정하고, 검사 대기 파일이 있으면 receipt를 만들지 않는다(SC-05-B).
- 판정은 서버 시계로 `openAt <= now < closeAt`이고 지각은 명시한 정책으로만 허용한다(SC-06).
- 임시저장, 최종 제출, 점수 공개를 `submitted=true` 하나로 겸하지 않는다(SC-06).

공용 멱등 원장(`(principal, operation, key)`, architecture.md 12절)은 아직 `shared`에 없다. 스키마에는 `assignments`, `assignment_submissions`, `assignment_submission_versions`, `grade_items`, `grade_scores`가 이미 있다.

## 결정
1. 과제의 제목, 설명, 주차, 공개, 기간은 learning의 활동(`activity_type = report`)이 가진다. assessment는 `learning.api.ReportActivities`로 같은 transaction에서 활동을 만들고 바꾼다. 제출 형태와 지각 정책은 `assignments`, 배점과 점수 공개는 그 과제의 `grade_items` 한 행이 가진다.
2. 과제 설정 수정은 `assignments.revision_no`를 비교해 바꾼다. 읽은 번호와 다르면 409 `edit_conflict`다.
3. 임시저장은 제출물 행의 `draft_body_text` 한 벌이다. 버전을 만들지 않고, 제출 수와 상태에 들어가지 않는다. 최종 제출에 성공하면 비운다.
4. 최종 제출은 `assignment_submission_versions`에 새 행을 넣는 것이다. 행은 쓴 뒤 바꾸지 않는다. 본문은 일반 텍스트(`body_text`)이고 첨부는 그 버전만의 첨부 그룹이다. 이전 버전의 첨부를 이어받지 않는다. 다시 제출하려면 파일을 다시 올린다.
5. 제출 요청은 `Idempotency-Key` header와 학생이 본 현재 버전 번호(`expectedVersion`)를 반드시 보낸다. 처리 순서는 제출물 행 잠금(`SELECT … FOR UPDATE`) → 같은 key의 버전 조회 → 버전 번호 비교 → DB 시계(`CURRENT_TIMESTAMP(3)`)로 구간 판정 → `Attachments.attach` → 버전 기록 → commit이다.
   - 같은 key와 같은 요청 정규형(과제, expectedVersion, 첨부 순서, 본문의 SHA-256)이면 마감과 버전을 다시 따지지 않고 그 버전을 receipt로 돌려준다.
   - 같은 key에 정규형이 다르면 409 `idempotency_conflict`다. 다른 과제에 같은 key를 써도 다르다.
   - 버전 번호가 다르면 409 `version_conflict`다. 두 탭이 같은 버전에서 제출하면 하나만 접수된다.
6. 공용 원장이 생기기 전까지 receipt는 제출 버전 행이다. `(submitted_by_user_id, idempotency_key)` 유일 제약이 원장 역할을 한다. 실패한 요청은 행을 남기지 않으므로 같은 key로 고쳐 다시 보낼 수 있다.
7. 구간은 `not_open`(openAt 전), `open`(`openAt <= now < closeAt`), `late`(정책이 `allow_until`이고 `closeAt <= now < lateUntil`), `closed`다. 학생은 공개됐고 openAt이 지난 과제만 본다. 접수 시각과 지각 여부는 판정에 쓴 DB 시각으로 버전에 저장한다. `penalty` 정책은 감점 계산이 성적 집계에 속하므로 지금 받지 않는다.
8. 점수와 의견은 `grade_scores`에 둔다. 학생 응답에 넣을지는 `Assignment.scoresReleased(now)` 하나로 정한다. 공개 전에는 점수를 읽지 않고, 응답에 속성 자체가 없다(SEC-14).
9. 제출 첨부의 파일 권한은 assessment의 `AttachmentParentAccess` 구현이 정한다. 읽기는 제출한 학생과 그 과목 담당자만이다. 제출물 파일로는 요약 같은 파생물을 만들지 않으므로 `MANAGE`는 누구에게도 주지 않는다.

## 결과와 트레이드오프
- 응답을 잃은 제출은 같은 key로 다시 보내면 같은 receipt를 받는다. 마감 뒤의 재전송도 처음 접수를 돌려준다.
- 같은 학생의 같은 과제 제출은 제출물 행 잠금으로 차례로 처리된다. 다른 학생끼리는 막지 않는다.
- 이전 버전의 파일을 그대로 다시 제출하려면 다시 올려야 한다. 같은 내용은 blob을 함께 쓰므로 저장 공간은 늘지 않는다. 다시 올리지 않고 이어받는 `keep` 계약(SC-05-B)은 `files.api.Attachments`에 기존 첨부 참조를 더해야 하며 이번에는 만들지 않았다.
- 임시저장은 본문만 남는다. 올려 둔 업로드 목록은 서버에 남기지 않으므로 화면을 새로 열면 다시 골라야 한다.
- 공용 멱등 원장이 생기면 receipt 조회를 원장으로 옮기고 `idempotency_key`, `request_fingerprint` 컬럼을 정리한다.
- 과제 삭제는 활동의 삭제 표시이고 제출물, 버전, 첨부는 남는다([ADR-0037](0037-quiz-regrade-and-assessment-deletion.md) 5번). 학생의 제출 삭제(EP-0075), 팀 과제, 상호평가, 표절검사, 양식 제출, 점수 분포는 이 결정 밖이다.
