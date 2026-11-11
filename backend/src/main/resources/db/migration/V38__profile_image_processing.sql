-- 아바타 사진(EP-0273~EP-0278, ADR-0038). 올린 원본은 검사와 재인코딩을 기다리는 동안 TEMP이고, worker가 lease로 잡아 악성코드
-- 검사와 격리한 converter의 재인코딩(256px JPEG)을 거친 뒤 ACTIVE가 된다. 실패하면 FAILED와 코드다. 원본은 내보내지 않고 처리
-- 뒤 지운다. DELETED와 FAILED 행의 객체는 files worker가 지우고 purged_at을 남긴다.

ALTER TABLE profile_images
  MODIFY COLUMN storage_key VARCHAR(512) NOT NULL COMMENT '재인코딩한 사진의 비공개 저장 경로(content bucket, 웹 루트 밖). 올릴 때 정하고 ACTIVE부터 객체가 있다',
  MODIFY COLUMN image_status VARCHAR(10) NOT NULL DEFAULT 'TEMP' COMMENT '상태: TEMP(검사·재인코딩 대기)|ACTIVE|FAILED|DELETED',
  ADD COLUMN source_key VARCHAR(512) NULL COMMENT '올린 원본의 비공개 저장 경로. 내보내지 않고 처리 뒤 지운다. 지웠으면 NULL' AFTER storage_key,
  ADD COLUMN attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '처리 시도 수' AFTER image_status,
  ADD COLUMN next_attempt_at DATETIME(3) NULL COMMENT '다시 처리할 수 있는 일시(검사기·converter 장애 뒤)' AFTER attempt_count,
  ADD COLUMN lease_owner VARCHAR(64) NULL COMMENT '처리 중인 worker' AFTER next_attempt_at,
  ADD COLUMN lease_expires_at DATETIME(3) NULL COMMENT '처리 lease 만료 일시' AFTER lease_owner,
  ADD COLUMN error_code VARCHAR(30) NULL COMMENT '실패 코드: not_image|too_large|rejected|unavailable|failed' AFTER lease_expires_at,
  ADD COLUMN purged_at DATETIME(3) NULL COMMENT 'DELETED·FAILED 행의 객체를 지운 일시' AFTER deleted_at,
  ADD KEY idx_profile_images_status_next (image_status, next_attempt_at),
  ADD KEY idx_profile_images_purged (purged_at, image_status);
