-- 50 communication: 강의실 질의응답(K002)과 열린게시판(K004)은 공지 게시판처럼 과목마다 하나다(docs/adr/0024-course-qna-and-open-board.md).
-- 게시판은 첫 글을 쓸 때 만들므로, 동시에 두 글을 써도 게시판이 둘 생기지 않게 DB 제약으로 막는다(SC-05).
-- 공지 게시판의 course_notice_key(V11)와 같은 방식이다. 열린게시판을 과목에 여러 개 두게 되면 K004를 이 조건에서 뺀다.
ALTER TABLE boards
  ADD COLUMN course_board_key VARCHAR(20) GENERATED ALWAYS AS (CASE WHEN board_type IN ('K002', 'K004') THEN board_type ELSE NULL END) STORED COMMENT '과목 질의응답·열린게시판 중복 방지용 보조 키(K002|K004이면 유형 코드)' AFTER course_notice_key,
  ADD UNIQUE KEY uq_boards_course_board (course_id, course_board_key);

-- 게시글 첨부의 업로드 정책. 과목 구성원(담당자와 수강생)이 질문·답글·열린게시판 글에 붙인다.
-- 확장자마다 내용 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는 넣지 않는다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('post_attachment', '게시글 첨부', 52428800, 5, 'pdf,docx,pptx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg');
