-- 20 catalog: 과제 알림의 연결 대상, 과목 없는 전체 알림, 비정규 과목분류 표시명(docs/adr/0036-notification-screen-target-and-instructor-roster.md).
--
-- 1. 과제 알림(ASSIGNMENT_OPENED, ASSIGNMENT_GRADED)의 대상 ID를 학습활동 ID에서 과제 ID로 바꾼다. 화면은 과제를 과제 ID로 연다.
--    대상 종류는 ASSIGNMENT다. 학습활동 ID는 ACTIVITY 대상에만 남기므로 과제 알림의 activity_id는 비운다.
--    (유형, 대상, 사용자) unique 위에서 값을 옮기므로 한 번에 바꾸면 아직 옮기지 않은 행의 값과 잠시 겹칠 수 있다. 먼저 어떤 ID와도
--    겹치지 않는 범위(1e15 이상)로 옮긴 뒤 내린다. 과제와 학습활동은 1:1(uq_assignments_activity)이라 옮긴 뒤에도 겹치지 않는다.
-- 2. 알림마당 전체 공지(SITE_NOTICE)는 과목 없이 기관 전체의 대상(all, student, staff: survey_targets.target_type과 같은 값)에게
--    보낸다. 사건 행 하나가 대상과 보일 시각을 갖고, worker가 대상 사용자를 사용자 ID 순으로 나눠 보내며 마지막으로 보낸 사용자를
--    남긴다. 과목 사건은 과목과 받는 역할, 과목 없는 사건은 대상 하나만 갖는다(CHECK).
-- 3. 비정규 과정에 과목분류 표시명을 둔다. 필터의 과목분류 선택지(SCH_CATEGORY 옵션 문구)를 코드만으로는 보여 줄 수 없다.

ALTER TABLE notifications
  MODIFY COLUMN notification_type VARCHAR(24) NOT NULL COMMENT '알림 유형: COURSE_NOTICE|SITE_NOTICE|LECTURE_MATERIAL|ASSIGNMENT_OPENED|ASSIGNMENT_GRADED|QUESTION_ANSWERED|MESSAGE_RECEIVED',
  MODIFY COLUMN target_kind VARCHAR(20) NULL COMMENT '연결 대상 종류: ACTIVITY|ASSIGNMENT|ARTICLE|MESSAGE',
  MODIFY COLUMN target_ref_id BIGINT UNSIGNED NULL COMMENT '연결 대상 ID(화면이 대상을 여는 ID: 학습활동, 과제, 게시글, 쪽지. 타 도메인 행 식별자, FK 없음)';

ALTER TABLE notification_events
  MODIFY COLUMN target_ref_id BIGINT UNSIGNED NOT NULL COMMENT '연결 대상 ID(공지 게시글, 학습활동, 과제. 다른 모듈의 행이라 값으로만 저장, FK 없음)',
  MODIFY COLUMN course_id BIGINT UNSIGNED NULL COMMENT '받는 사람을 정하는 과목 ID. 과목 없는 전체 알림이면 NULL',
  MODIFY COLUMN audience_role VARCHAR(12) NULL COMMENT '과목 알림의 받는 사람 과목 내 역할: STUDENT|INSTRUCTOR. 전체 알림이면 NULL',
  ADD COLUMN site_audience VARCHAR(8) NULL COMMENT '전체 알림의 받는 대상: all|student|staff(survey_targets.target_type과 같은 값). 과목 알림이면 NULL' AFTER audience_role,
  ADD COLUMN delivered_user_id BIGINT UNSIGNED NULL COMMENT '전체 알림을 나눠 보낸 마지막 사용자 ID(다음 묶음은 그다음부터. 처음이면 NULL, 값으로만 저장)' AFTER delivered_at,
  ADD CONSTRAINT ck_notification_events_scope CHECK ((course_id IS NOT NULL AND audience_role IS NOT NULL AND site_audience IS NULL)
      OR (course_id IS NULL AND audience_role IS NULL AND site_audience IN ('all', 'student', 'staff')));

UPDATE notifications n JOIN assignments a ON a.activity_id = n.target_ref_id
   SET n.target_ref_id = a.assignment_id + 1000000000000000, n.target_kind = 'ASSIGNMENT', n.activity_id = NULL
 WHERE n.notification_type IN ('ASSIGNMENT_OPENED', 'ASSIGNMENT_GRADED') AND n.target_kind = 'ACTIVITY';
UPDATE notifications SET target_ref_id = target_ref_id - 1000000000000000
 WHERE notification_type IN ('ASSIGNMENT_OPENED', 'ASSIGNMENT_GRADED') AND target_ref_id >= 1000000000000000;

UPDATE notification_events e JOIN assignments a ON a.activity_id = e.target_ref_id
   SET e.target_ref_id = a.assignment_id + 1000000000000000, e.activity_id = NULL
 WHERE e.notification_type IN ('ASSIGNMENT_OPENED', 'ASSIGNMENT_GRADED') AND e.activity_id IS NOT NULL;
UPDATE notification_events SET target_ref_id = target_ref_id - 1000000000000000
 WHERE notification_type IN ('ASSIGNMENT_OPENED', 'ASSIGNMENT_GRADED') AND target_ref_id >= 1000000000000000;

ALTER TABLE non_regular_offerings
  ADD COLUMN category_name VARCHAR(100) NULL COMMENT '과목분류 표시명(SCH_CATEGORY 옵션 문구, 예: 건설시스템공학과. 없으면 화면은 코드를 보인다)' AFTER category_code;
