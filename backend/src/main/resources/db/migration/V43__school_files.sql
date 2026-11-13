-- 30 learning / 50 files: 학교 e-Class 강의실의 파일(docs/adr/0043-school-content.md).
-- 학교 강의실 파일모음 목록(학교 쪽 상태를 바꾸지 않는 목록 화면)을 그 과목 수강생의 학교 session으로 읽어 둔다. 파일 내용은 학생이
-- STack에서 그 파일을 열 때 그 학생의 학교 session으로 받아 과목 파일(files, 용도 school_file)로 저장하고, 같은 과목의 다른 수강생은
-- 그 사본을 연다. 학교 화면이 원천인 사본이라 학교 목록에서 사라진 행은 지우고 받아 둔 파일의 첨부를 끊는다.

-- 학교에서 받아 둔 파일의 업로드 정책. 누구도 이 용도로 올리지 않는다(files.upload.UploadPurpose.SCHOOL_FILE). 확장자마다 내용
-- 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는 넣지 않는다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('school_file', '학교 e-Class 파일 사본', 52428800, 1,
        'pdf,ppt,pptx,doc,docx,xls,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg');

-- 학교 강의실 파일 하나. 학교 파일 키(FILE_SEQ)로 과목 안에서 하나다.
CREATE TABLE school_files (
  school_file_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학교 파일 ID',
  course_id           BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  file_seq            VARCHAR(40)     NOT NULL COMMENT '학교 파일 키(FILE_SEQ)',
  content_seq         VARCHAR(40)     NOT NULL COMMENT '학교 내용 키(CONTENT_SEQ). 받기 요청에 쓴다',
  school_type         VARCHAR(30)     NULL COMMENT '파일이 붙은 학교 학습활동의 메뉴 ID(lecture_material|report 등)',
  school_item_id      VARCHAR(40)     NULL COMMENT '파일이 붙은 학교 학습활동 번호',
  file_name           VARCHAR(300)    NOT NULL COMMENT '학교 화면의 파일 이름',
  size_bytes          BIGINT UNSIGNED NULL COMMENT '학교 화면 크기 표시의 근사값(byte)',
  sort_order          INT UNSIGNED    NOT NULL COMMENT '학교 화면의 과목 안 순서(0부터)',
  attachment_group_id BIGINT UNSIGNED NULL COMMENT '받아 둔 사본의 첨부 그룹(파일 하나). 받기 전에는 NULL',
  file_id             BIGINT UNSIGNED NULL COMMENT '받아 둔 사본 파일 ID. 받기 전에는 NULL',
  copied_at           DATETIME(3)     NULL COMMENT '사본을 받은 일시',
  listed_at           DATETIME(3)     NOT NULL COMMENT '학교 목록에서 마지막으로 본 일시',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (school_file_id),
  UNIQUE KEY uq_school_files_course_seq (course_id, file_seq),
  UNIQUE KEY uq_school_files_group (attachment_group_id),
  KEY idx_school_files_course_item (course_id, school_type, school_item_id),
  KEY idx_school_files_file (file_id),
  CONSTRAINT fk_school_files_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_school_files_attachment_groups FOREIGN KEY (attachment_group_id)
    REFERENCES attachment_groups (attachment_group_id),
  CONSTRAINT fk_school_files_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 강의실 파일';

-- 과목별 학교 파일모음 목록을 마지막으로 읽은 시각. 목록은 이 간격 안에 다시 읽지 않는다.
CREATE TABLE school_file_lists (
  course_id  BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  listed_at  DATETIME(3)     NOT NULL COMMENT '학교 파일모음 목록을 마지막으로 읽은 일시',
  PRIMARY KEY (course_id),
  CONSTRAINT fk_school_file_lists_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 파일모음 목록 읽기';
