-- 강의 자료 수정·삭제와 저장소 정리(docs/adr/0011-material-deletion.md)
-- 활동은 지우지 않고 삭제 표시를 남긴다. 진도, 열람 이력, 학습 session, 성적 항목이 활동을 가리키므로 학습 기록을 보존한다(SC-07).
-- 파일은 삭제 표시(files.state = 'deleted') 뒤 worker가 파생물을 정리하고, 참조가 없는 원본 객체를 지운다(SC-05 파일 삭제).

ALTER TABLE learning_activities
  ADD COLUMN row_version INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '수정 낙관적 버전(If-Match로 확인)' AFTER sort_order,
  ADD COLUMN deleted_at  DATETIME(3)     NULL COMMENT '삭제 일시(삭제 표시. 학습 기록 보존)' AFTER updated_at,
  ADD COLUMN deleted_by  BIGINT UNSIGNED NULL COMMENT '삭제한 사용자 ID' AFTER deleted_at,
  ADD KEY idx_learning_activities_deleted_by (deleted_by),
  ADD CONSTRAINT fk_learning_activities_deleted_by_users FOREIGN KEY (deleted_by) REFERENCES users (user_id);

ALTER TABLE files
  ADD COLUMN derivatives_purged_at DATETIME(3) NULL COMMENT '삭제한 파일의 파생물·rendition 정리 일시' AFTER deleted_at,
  ADD KEY idx_files_state_derivatives_purged (state, derivatives_purged_at);

ALTER TABLE file_blobs
  ADD KEY idx_file_blobs_reference_purged (reference_count, purged_at);
