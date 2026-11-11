-- 60 files: 개인 파일함, 휴지통, 파일함 파일 다시 쓰기, ZIP 일괄 다운로드(docs/adr/0028-filebox.md)
-- 파일함 항목은 소유자 한 사람의 것이다. 한 사용자의 트리 변경(만들기, 이름 바꾸기, 이동, 휴지통, 복원, 영구 삭제, 올리기, ZIP 요청)은
-- filebox_quotas의 그 사용자 행을 먼저 잠그고 차례로 처리한다. 잠금 순서는 filebox_quotas → filebox_items → file_blobs → files →
-- upload_sessions다.

-- 사용자별 파일함 사용량과 용량 한도. 용량·항목 수 검사와 증감을 이 행을 잠근 뒤 같은 transaction에서 하므로 동시에 올려도 한도를 넘지 않는다.
CREATE TABLE filebox_quotas (
  owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  quota_bytes   BIGINT UNSIGNED NULL     COMMENT '이 사용자의 용량 한도 바이트(빈 값=설정 astra.filebox.quota)',
  used_bytes    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '파일함 파일이 차지하는 바이트(활성+휴지통, 영구 삭제하면 줄어든다)',
  item_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '파일함 항목 수(폴더 포함, 활성+휴지통)',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at    DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (owner_user_id),
  CONSTRAINT fk_filebox_quotas_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일함 사용량';

-- 휴지통: 사용자가 고른 항목(휴지통 root)과 그 아래 활성 항목을 함께 trashed로 바꾸고 root를 기록한다. 복원은 같은 root로 들어간 항목만
-- 되돌린다. 연결 활동 수는 다시 쓴 파일(files.source_filebox_item_id)에서 계산하므로 캐시 column을 지운다.
ALTER TABLE filebox_items
  DROP COLUMN reference_count,
  ADD COLUMN trash_root_id BIGINT UNSIGNED NULL COMMENT '휴지통 root 항목 ID(함께 휴지통에 넣은 묶음, 사용자가 고른 항목이면 자기 ID)' AFTER purged_at,
  ADD KEY idx_filebox_items_trash_root (trash_root_id),
  ADD CONSTRAINT fk_filebox_items_trash_root_filebox_items FOREIGN KEY (trash_root_id) REFERENCES filebox_items (filebox_item_id);

-- 파일함 파일을 과목 업로드로 다시 쓰면 같은 내용 객체를 참조하는 새 파일을 만든다. 그 파일이 어느 항목에서 왔는지 남겨 연결 활동을 보여 준다.
ALTER TABLE files
  ADD COLUMN source_filebox_item_id BIGINT UNSIGNED NULL COMMENT '파일함에서 다시 쓴 파일이면 그 파일함 항목 ID' AFTER source_menu_code,
  ADD KEY idx_files_source_filebox_item (source_filebox_item_id),
  ADD CONSTRAINT fk_files_source_filebox_item_filebox_items FOREIGN KEY (source_filebox_item_id) REFERENCES filebox_items (filebox_item_id);

-- ZIP 생성물은 files 행이 아니라 content bucket의 zips/{작업 ID}/ 아래 private 객체로 두고 TTL이 지나면 지운다.
ALTER TABLE zip_jobs DROP FOREIGN KEY fk_zip_jobs_files;

ALTER TABLE zip_jobs
  DROP COLUMN result_file_id,
  MODIFY COLUMN failure_reason VARCHAR(200) NULL COMMENT '실패 사유 코드(item_unavailable, build_failed)',
  MODIFY COLUMN expires_at DATETIME(3) NULL COMMENT '생성물 만료 일시(ready, failed 뒤 TTL)',
  ADD COLUMN attempt_count      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '생성 시도 수' AFTER zip_state,
  ADD COLUMN lease_owner        VARCHAR(100)    NULL     COMMENT '생성 중인 worker 식별자' AFTER attempt_count,
  ADD COLUMN lease_expires_at   DATETIME(3)     NULL     COMMENT '생성 lease 만료 일시' AFTER lease_owner,
  ADD COLUMN result_storage_key VARCHAR(400)    NULL     COMMENT '생성물 저장 키(content bucket zips/{작업 ID}/...)' AFTER lease_expires_at,
  ADD COLUMN result_size_bytes  BIGINT UNSIGNED NULL     COMMENT '생성물 바이트 크기' AFTER result_storage_key,
  ADD COLUMN completed_at       DATETIME(3)     NULL     COMMENT '생성 완료 일시' AFTER result_size_bytes,
  ADD COLUMN purged_at          DATETIME(3)     NULL     COMMENT '만료 뒤 저장소 객체를 지운 일시' AFTER expires_at;

-- 파일함 업로드 정책. 확장자마다 내용 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는 넣지 않는다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('filebox', '개인 파일함', 52428800, NULL, 'pdf,pptx,docx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg');
