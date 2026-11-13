-- 30 learning: 학교 e-Class 과제의 학생 본인 제출 파일(docs/adr/0043-school-content.md).
-- 학생이 STack에서 학교 과제를 열면 그 학생의 학교 session으로 과제 상세, 과제 첨부, 그 학생 본인이 낸 파일 목록을 읽는다. 과제
-- 첨부는 같은 과목 수강생이 함께 보는 학교 파일이고, 본인이 낸 파일(school_type = 'report_submission')은 그 학생에게만 보인다.
-- 주인이 있는 행은 목록에도, 파일 열기에도, 받아 둔 사본의 첨부 권한에도 그 학생에게만 나온다. 학교 파일모음 목록으로 지우지 않고
-- 그 학생이 그 과제를 다시 열 때 맞춘다.

ALTER TABLE school_files
  ADD COLUMN owner_user_id BIGINT UNSIGNED NULL COMMENT '그 학생에게만 보이는 파일(본인 제출 파일)의 학생 사용자 ID. 과목 공용이면 NULL'
    AFTER school_item_id,
  ADD KEY idx_school_files_owner (owner_user_id),
  ADD CONSTRAINT fk_school_files_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id);
