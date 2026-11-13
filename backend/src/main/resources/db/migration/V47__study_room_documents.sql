-- 60 files: 학습실의 내 문서(PDF)를 녹음과 같이 서버에 둔다(docs/adr/0040-study-room-sync.md 14번).
-- 문서는 소유자 한 사람의 것이다. 과목에 게시하지 않고 담당자, 운영자, 요약·학습 대화, 외부 모델 어디에도 가지 않는다.
-- 사용량은 녹음과 함께 study_room_quotas 한 행으로 센다. 잠금 순서는 study_room_quotas → study_room_folders →
-- study_room_recordings → study_room_documents → study_room_placements → file_blobs → files → upload_sessions다.

-- 내 문서 업로드 정책. 녹음처럼 과목 없이(files.course_id NULL) 저장하고 첨부하지 않으므로 과목 파일 경로로는 열리지 않는다.
-- 크기 상한은 악성코드 검사기가 받는 크기(64 MB) 아래로 둔다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('study_document', '학습실 내 문서', 52428800, 1, 'pdf');

ALTER TABLE study_room_quotas
  MODIFY COLUMN used_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '녹음과 문서 파일이 차지하는 바이트',
  ADD COLUMN document_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '문서 수' AFTER recording_count;

-- 학생이 올린 자기 PDF. 바이트는 files와 file_blobs(content bucket)에 있고, 이 행은 제목과 과목을 둔다.
CREATE TABLE study_room_documents (
  study_room_document_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습실 문서 ID',
  owner_user_id          BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  course_id              BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  file_id                BIGINT UNSIGNED NOT NULL COMMENT 'PDF 파일 ID',
  document_title         VARCHAR(300)    NOT NULL COMMENT '문서 제목',
  document_version       INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '이름 바꾸기·지우기 낙관적 버전',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at             DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (study_room_document_id),
  UNIQUE KEY uq_study_room_documents_file (file_id),
  UNIQUE KEY uq_study_room_documents_owner_document (owner_user_id, study_room_document_id),
  KEY idx_study_room_documents_owner_course (owner_user_id, course_id, created_at),
  KEY idx_study_room_documents_course (course_id),
  CONSTRAINT fk_study_room_documents_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_study_room_documents_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_study_room_documents_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 문서';

-- 배치는 강의자료 파일, 녹음, 문서 가운데 하나를 둔다. 문서도 (소유자, ID) 복합 FK로 가리켜 남의 문서를 두는 행은 DB가 받지 않는다.
ALTER TABLE study_room_placements DROP CHECK ck_study_room_placements_item;

ALTER TABLE study_room_placements
  MODIFY COLUMN file_id BIGINT UNSIGNED NULL COMMENT '과목 강의자료 파일 ID(녹음·문서면 NULL)',
  MODIFY COLUMN study_room_recording_id BIGINT UNSIGNED NULL COMMENT '녹음 ID(강의자료 파일·문서면 NULL)',
  ADD COLUMN study_room_document_id BIGINT UNSIGNED NULL COMMENT '문서 ID(강의자료 파일·녹음이면 NULL)' AFTER study_room_recording_id,
  ADD UNIQUE KEY uq_study_room_placements_document (study_room_document_id),
  ADD KEY idx_study_room_placements_owner_document (owner_user_id, study_room_document_id),
  ADD CONSTRAINT ck_study_room_placements_item
    CHECK ((file_id IS NOT NULL) + (study_room_recording_id IS NOT NULL) + (study_room_document_id IS NOT NULL) = 1),
  ADD CONSTRAINT fk_study_room_placements_study_room_documents FOREIGN KEY (owner_user_id, study_room_document_id)
    REFERENCES study_room_documents (owner_user_id, study_room_document_id);
