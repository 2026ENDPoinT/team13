-- 50 communication, 60 files: 알림마당 운영자, 알림마당 공지, 과목 밖 첨부(알림마당 공지·자료·1:1 문의·쪽지), 댓글 수정(docs/adr/0035-community-operators-and-outside-course-files.md).

-- 알림마당 운영자. 자료실 자료를 올리고 1:1 문의를 보고 답하는 사람이다. users.user_role(학사 원천의 기관 역할)과 관계없이 이 표의
-- 행으로만 정한다. 이 행을 만드는 API는 없다. 관리 화면이 생기기 전까지 운영 절차(runbook)로만 넣고 해제한다. 요청마다 이 표를 읽고
-- session에 담아 두지 않으므로 해제하면 다음 요청부터 권한이 없다.
CREATE TABLE community_operators (
  user_id         BIGINT UNSIGNED NOT NULL COMMENT '운영자 사용자 ID',
  grant_reference VARCHAR(100)    NOT NULL COMMENT '지정 근거(결재 문서 번호 등, 운영 절차가 적는다)',
  granted_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '지정 일시',
  revoked_at      DATETIME(3)     NULL     COMMENT '해제 일시(해제했으면 권한 없음)',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at      DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (user_id),
  CONSTRAINT fk_community_operators_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='알림마당 운영자';

-- 알림마당 공지(NOTICE), 자료실(MATERIAL), 1:1 문의(H002)는 과목이 없는 전역 게시판 하나씩이다. 첫 글을 쓸 때 만들므로 동시에 써도 둘이
-- 생기지 않게 DB 제약으로 막는다(SC-05). 과목 게시판의 course_board_key(V23)와 같은 방식이다. NOTICE는 board_type의 새 값이다.
-- board_type은 생성 열 둘이 참조하므로 열 주석을 고치지 않고 이 생성 열 주석에 적는다.
ALTER TABLE boards
  ADD COLUMN community_board_key VARCHAR(20) GENERATED ALWAYS AS (CASE WHEN course_id IS NULL AND board_type IN ('H002', 'MATERIAL', 'NOTICE') THEN board_type ELSE NULL END) STORED COMMENT '알림마당 전역 게시판 중복 방지용 보조 키(board_type H002|MATERIAL|NOTICE(알림마당 공지)이면 유형 코드)' AFTER course_board_key,
  ADD UNIQUE KEY uq_boards_community_board (community_board_key);

-- 열린게시판 댓글 수정(EP-0026)의 낙관적 잠금. 수정할 때마다 1씩 오른다.
ALTER TABLE comments
  ADD COLUMN version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '수정 버전(낙관적 잠금)' AFTER is_anonymous;

-- 과목 밖 업로드 용도. 업로드와 파일의 course_id가 NULL이고 과목 경로로는 열리지 않는다. 일반 업로드처럼 이름·확장자·내용 검사와
-- 악성코드 검사(ADR-0018)를 거친다. 확장자마다 내용 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는
-- 넣지 않는다. 1:1 문의와 쪽지의 1개 상한 10MB는 원본 화면 값이다(화면 32 file_limit_size, 화면 07 주석 기본값).
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('community_notice', '알림마당 공지 첨부', 52428800, 10, 'pdf,docx,pptx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg'),
       ('community_material', '알림마당 자료실 첨부', 52428800, 10, 'pdf,docx,pptx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg'),
       ('inquiry_attachment', '1:1 문의 첨부', 10485760, 5, 'pdf,docx,pptx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg'),
       ('message_attachment', '쪽지 첨부', 10485760, 5, 'pdf,docx,pptx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg');
