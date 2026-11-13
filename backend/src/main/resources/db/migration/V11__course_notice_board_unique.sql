-- 50 communication: 강의실 공지 게시판(K001)은 과목마다 하나다(docs/adr/0013-course-notice-storage.md).
-- 게시판은 첫 공지를 쓸 때 만들므로, 동시에 두 공지를 써도 게시판이 둘 생기지 않게 DB 제약으로 막는다(SC-05).
-- MySQL에는 부분 유일 인덱스가 없어 생성 열과 UNIQUE로 구현한다(course_enrollments.active_scope_key와 같은 방식).
ALTER TABLE boards
  ADD COLUMN course_notice_key TINYINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN board_type = 'K001' THEN 1 ELSE NULL END) STORED COMMENT '과목 공지 게시판 중복 방지용 보조 키(K001이면 1)' AFTER board_type,
  ADD UNIQUE KEY uq_boards_course_notice (course_id, course_notice_key);
