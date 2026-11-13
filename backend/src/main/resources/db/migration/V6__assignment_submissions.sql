-- 40 assessment: 과제 등록과 제출(docs/adr/0015-assignment-submission-model.md)
-- 제출 본문은 일반 텍스트로만 저장한다. body_html은 서버 HTML 정규화(SC-08)를 만들 때까지 쓰지 않는다.

-- 과제 설정 수정의 동시 수정 감지(lost update 방지).
ALTER TABLE assignments
  ADD COLUMN revision_no INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '과제 설정 수정 번호(수정할 때마다 1 증가, 동시 수정 감지)';

-- 임시저장은 제출물 행에 한 벌만 둔다. 제출(버전)로 치지 않는다.
ALTER TABLE assignment_submissions
  ADD COLUMN draft_body_text MEDIUMTEXT NULL COMMENT '임시저장 본문(일반 텍스트, 제출로 치지 않음)',
  ADD COLUMN draft_saved_at DATETIME(3) NULL COMMENT '임시저장 일시';

-- 최종 제출 버전의 일반 텍스트 본문과 제출 명령의 멱등 receipt(SC-05). 공용 멱등 원장이 생기기 전까지 이 유일 제약이 원장이다.
ALTER TABLE assignment_submission_versions
  ADD COLUMN body_text MEDIUMTEXT NULL COMMENT '제출 본문(일반 텍스트)' AFTER body_html,
  ADD COLUMN idempotency_key VARCHAR(100) NULL COMMENT '제출 요청의 Idempotency-Key(제출자 단위 유일)',
  ADD COLUMN request_fingerprint CHAR(64) NULL COMMENT '제출 요청 정규형의 SHA-256(같은 key의 다른 요청 판별)',
  ADD UNIQUE KEY uq_assignment_submission_versions_idempotency (submitted_by_user_id, idempotency_key);

-- 용도 코드 assignment_submission(21자)이 들어가도록 넓힌다.
ALTER TABLE upload_policies
  MODIFY COLUMN policy_code VARCHAR(40) NOT NULL COMMENT '용도 코드(lecture_material, assignment_submission 등. 원본 경로 코드 K002,K004,K006,M001,H002,MY01)';

-- 과제 제출 첨부의 업로드 정책. 확장자마다 내용 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는 넣지 않는다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('assignment_submission', '과제 제출 첨부', 52428800, 5, 'pdf,docx,pptx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg');
