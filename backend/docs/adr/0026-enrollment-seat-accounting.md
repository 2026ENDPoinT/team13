# ADR-0026 수강신청은 과정 행 잠금 아래에서 좌석 카운터 조건부 갱신과 활성 행 unique 제약으로 판정한다

## 상태
채택 (2026-11-11)

## 맥락
원본의 비정규 수강신청(`non_regular_apply_pop.acl`, EP-0197)은 신청 팝업을 여는 요청이 곧 등록이었다(CHG-03, CAT-01). 승인 대기, 정원 마감, 기간 외 신청, 동시 신청의 서버 동작은 관찰되지 않았다(CAT-04). 서버 계약은 다음을 요구한다.

- 목록, 상세, 신청 미리보기는 등록을 만들지 않는다(SC-04).
- 대상, 기간, 정원, 중복을 한 transaction에서 판정한다. 마지막 자리를 두 명이 다투면 한 명만 성공하고, 같은 학생의 두 요청은 한 건만 만든다(SC-05, `backend/20_catalog.md` §8 C-01~C-06).
- 취소는 행을 지우지 않고 상태만 바꾸며 좌석을 한 번만 돌려준다. 이미 취소했으면 같은 결과다(CAT-05).
- 시각 판정은 DB 시계로 한다(SC-06, CAT-15).

V1 스키마에 필요한 것이 다 있다. `course_enrollments`의 활성 상태 생성 열과 unique 키(`uq_course_enrollments_active`), `holds_seat`, `version`, `course_seat_counters`의 `held_count <= seat_capacity` CHECK, append-only `enrollment_events`(`(enrollment_id, event_seq)` unique), `audit_request_details`, `non_regular_offerings.approval_mode`(AUTO|MANUAL)와 `cancel_allowed`, `cancel_close_at`이다. 대기자 승격 순서, 청강 정원과 청강 신청 기간은 근거가 없어 스키마에도 없다.

## 결정
1. **조회와 명령을 나눈다.** `GET /api/catalog/offerings/{courseId}`가 신청 미리보기(`eligible`, `reasons`)를 표시용으로 돌려주고, 등록은 `POST /api/catalog/offerings/{courseId}/enrollments` 하나로만 일어난다. 명령은 같은 규칙(`OfferingRules`)을 과정 행을 잠근 뒤 다시 판정한다. 좌석을 잡는 명령이므로 `Idempotency-Key`가 필수다.
2. **상태 기계.** 비정규(`NONREGULAR`)와 청강(`AUDIT`)만 이 서비스가 바꾼다. 학사 원장(`REGISTRAR`)은 읽기만 한다.

   | 구분 | 전이 | 좌석 |
   |---|---|---|
   | 비정규, AUTO | 신청 → ENROLLED | 잡는다 |
   | 비정규, MANUAL | 신청 → PENDING | 없음 |
   | 비정규 | PENDING → ENROLLED(담당자 승인) | 잡는다. 정원이 찼으면 409 `seat_full`로 PENDING에 남는다 |
   | 비정규 | PENDING → REJECTED(담당자 반려), PENDING → CANCELLED(학생) | 없음 |
   | 비정규 | ENROLLED → CANCELLED(학생, `cancel_allowed`이고 `now < cancel_close_at`) | 돌려준다 |
   | 청강 | 신청 → REQUESTED → ENROLLED(역할 AUDITOR) 또는 REJECTED, REQUESTED·ENROLLED → CANCELLED | 쓰지 않는다 |

   WAITLISTED는 만들지 않는다. 정원이 찬 자동 승인 과정의 신청은 409 `seat_full`이고 아무것도 남지 않는다. 취소·반려 뒤 다시 신청하면 새 행을 만든다. 이전 행과 그 이벤트는 이력으로 남는다.
3. **좌석 회계.** 불변식은 `held_count = ENROLLED이고 holds_seat = 1인 행 수`다. 좌석은 ENROLLED로 들어갈 때만 잡고 그 행이 ENROLLED에서 나갈 때만 돌려준다. 같은 transaction에서 행의 `holds_seat`와 카운터를 함께 바꾼다. 승인 과정은 신청이 아니라 승인 시점에 좌석을 잡는다. 신청 시점에 잡으면 반려를 기다리는 신청이 자리를 묶어, 정원이 남았는데도 다른 학생이 신청하지 못한다.
4. **동시성.** 잠금 순서는 비정규 과정 행(`non_regular_offerings`, `FOR UPDATE OF o`) → 수강 행 → 좌석 카운터이고 모두 기본 키로 잠근다. 같은 과정의 신청, 승인, 취소는 과정 행에서 한 줄로 선다.
   - 좌석은 `UPDATE course_seat_counters SET held_count = held_count + 1 WHERE course_id = ? AND (seat_capacity IS NULL OR held_count < seat_capacity)`로 잡는다. 0행이면 정원 마감이다. CHECK 제약이 마지막 방어선이다.
   - 중복 신청은 먼저 읽어 보지 않고 insert의 unique 위반(`DuplicateKeyException`)을 409 `already_applied`로 바꾼다.
   - 카운터가 없는 과정은 `INSERT … ON DUPLICATE KEY UPDATE`로 정원 없는 카운터를 만든 뒤 갱신한다. 없는 행을 잠금 읽기로 찾으면 gap 잠금끼리 교착한다.
   - 승인과 학생 취소가 겹치면 수강 행 잠금 뒤 상태와 `version`을 비교해 하나만 성공한다.
   - 교착이나 잠금 대기 시간 초과로 되돌아간 명령은 남긴 것이 없으므로 컨트롤러가 3번까지 처음부터 다시 실행한다.
5. **이벤트 순번.** `enrollment_events.event_seq`는 그 이벤트가 만든 수강 행의 `version`이다. version은 행 잠금 아래에서만 오르므로 순번이 겹치지 않고, `MAX(event_seq)`를 읽지 않아 이벤트 인덱스에 범위 잠금을 걸지 않는다. 청강 사유 수정은 이벤트 없이 version만 올리므로 순번이 건너뛸 수 있다.
6. **멱등.** 신청은 공용 원장(ADR-0012)을 쓴다. 같은 key는 처음 receipt(같은 `enrollmentId`)를, 다른 key는 409 `already_applied`를 받는다. 취소는 상태로 멱등이다. 이미 CANCELLED면 아무것도 쓰지 않고 같은 결과를 돌려준다. 승인·반려는 담당자가 읽은 `version`을 비교하고, key를 보내면 원장도 거친다.
7. **대상과 권한.** 신청대상 코드는 `users.user_role`에서 정한다: PROFESSOR 1, STAFF 2, STUDENT 3, TA 5. identity가 졸업생(4)과 대학원생(6)을 구분하지 않으므로 그 코드만 가진 과정은 아무도 신청하지 못한다. 신청대상이 없는 과정도 아무도 신청하지 못한다(기본 거부). 그 과목의 담당자는 신청하지 못한다(409 `own_course`). 승인·반려는 `course_instructors`의 PROFESSOR와 MANAGER만 한다. 조교에게 맡기는 위임 모델이 없어 TA는 하지 못한다(§3.3). 폐강한 과목에는 처리할 사람이 없어 승인·반려와 신청 목록도 404다. 과목 구성원 판정(`MembershipRule`)과 같다.
8. **시각.** 판정 시각은 명령 transaction 안에서 읽은 `CURRENT_TIMESTAMP(3)`이다. 접수는 `openAt <= now < closeAt`, 취소 마감은 `now < cancelCloseAt`이다.

검토한 대안:
- 정원을 `COUNT(*)`로 세고 insert: 같은 snapshot을 본 두 transaction이 함께 마지막 자리를 가져간다.
- SERIALIZABLE 격리: 읽은 범위 전체에 공유 잠금이 걸려 다른 과정의 신청과도 교착한다.
- 승인 과정도 신청 시점에 좌석 점유: 3번의 이유로 쓰지 않았다.

## 결과와 트레이드오프
- 같은 과정의 신청은 과정 행에서 순서대로 처리된다. 한 과정에 수백 명이 몰려도 transaction이 짧아 문제가 없다. 수천 명이 몇 초 안에 몰리면 카운터 분할을 다시 본다.
- 승인 과정에는 정원보다 많은 PENDING이 쌓일 수 있다. 담당자는 정원이 찬 뒤 승인하면 409 `seat_full`을 받는다.
- 대기자 명단과 자동 승격이 없다. 정책이 정해지면 WAITLISTED 순번 컬럼과 취소 transaction 안의 승격을 더한다.
- 카운터와 원장의 대사 job(§4.4, C-22)은 아직 없다. 불변식은 모든 쓰기가 한 곳(`EnrollmentService`)을 지나는 것으로 지킨다.
- 강의소개(`introduction_html`)는 서버 HTML 정규화가 없어 내보내지 않는다.
