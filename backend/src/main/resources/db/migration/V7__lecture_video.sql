-- 강의 영상: 업로드 정책, 영상 asset과 HLS 변환 작업 원장, 온라인강의 콘텐츠와 영상의 연결(docs/adr/0016-video-upload-and-transcoder.md).
-- HLS 결과와 key는 object storage에만 두고 이 테이블에는 상태와 경로만 둔다(docs/adr/0007-confidential-content-placement.md).

-- ---------------------------------------------------------------
-- 1. 업로드 정책
-- ---------------------------------------------------------------
-- 영상은 본문을 그대로 object storage로 흘려 보내는 업로드(POST /api/uploads/stream)로만 받는다. 크기 상한은 2 GiB이고 이 행에서 바꾼다.
-- 확장자마다 내용 형식을 함께 검사하므로(files.upload.ContentInspector) 검사 규칙이 없는 확장자는 넣지 않는다.
INSERT INTO upload_policies (policy_code, policy_name, max_file_bytes, max_file_count, allowed_extensions)
VALUES ('lecture_video', '강의 영상', 2147483648, 1, 'mp4,mov,webm,mkv');

-- ---------------------------------------------------------------
-- 2. 영상 asset
-- ---------------------------------------------------------------
CREATE TABLE video_assets (
  video_asset_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '영상 asset ID',
  file_id        BIGINT UNSIGNED NOT NULL COMMENT '원본 영상 파일 ID',
  asset_key      VARCHAR(64)     NOT NULL COMMENT 'asset 키(delivery bucket vod/{asset_key}/, content bucket vod-keys/{asset_key}.key)',
  key_uri        VARCHAR(200)    NOT NULL COMMENT 'HLS key URI(재생 목록에 쓰는 백엔드 key API 경로)',
  asset_state    VARCHAR(12)     NOT NULL DEFAULT 'processing' COMMENT '상태: processing|ready|failed|removed',
  playlist_path  VARCHAR(200)    NULL     COMMENT '재생 목록 경로(vod/{asset_key}/index.m3u8)',
  duration_sec   INT UNSIGNED    NULL     COMMENT '재생 길이(초)',
  segment_count  INT UNSIGNED    NULL     COMMENT 'segment 수',
  output_bytes   BIGINT UNSIGNED NULL     COMMENT '게시한 HLS 결과 크기(바이트)',
  purged_at      DATETIME(3)     NULL     COMMENT 'HLS 결과와 key를 지운 일시',
  created_at     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at     DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (video_asset_id),
  UNIQUE KEY uq_video_assets_file (file_id),
  UNIQUE KEY uq_video_assets_asset_key (asset_key),
  KEY idx_video_assets_state (asset_state, purged_at),
  CONSTRAINT fk_video_assets_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='영상 asset';

-- ---------------------------------------------------------------
-- 3. HLS 변환 작업
-- ---------------------------------------------------------------
CREATE TABLE video_transcode_jobs (
  video_transcode_job_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '영상 변환 작업 ID',
  video_asset_id         BIGINT UNSIGNED NOT NULL COMMENT '영상 asset ID',
  job_state              VARCHAR(12)     NOT NULL DEFAULT 'queued' COMMENT '작업 상태: queued|running|succeeded|failed|cancelled',
  attempt_count          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '실행을 시작한 횟수',
  next_attempt_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '다음 실행 가능 일시(재시도 대기)',
  error_code             VARCHAR(30)     NULL     COMMENT '마지막 실패 코드: invalid_media|too_large|too_long|timeout|unavailable|failed|interrupted|internal',
  cancel_requested_at    DATETIME(3)     NULL     COMMENT '취소 요청 일시',
  lease_owner            VARCHAR(100)    NULL     COMMENT '실행 중인 worker 식별자',
  lease_expires_at       DATETIME(3)     NULL     COMMENT '실행 lease 만료 일시',
  started_at             DATETIME(3)     NULL     COMMENT '마지막 실행 시작 일시',
  finished_at            DATETIME(3)     NULL     COMMENT '종료 일시',
  active_asset_key       BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN job_state IN ('queued', 'running') THEN video_asset_id ELSE NULL END) STORED COMMENT '진행 중 작업 asset 키(asset당 하나만 진행)',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '요청 일시',
  updated_at             DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (video_transcode_job_id),
  UNIQUE KEY uq_video_transcode_jobs_active_asset_key (active_asset_key),
  KEY idx_video_transcode_jobs_asset (video_asset_id),
  KEY idx_video_transcode_jobs_state_next (job_state, next_attempt_at),
  KEY idx_video_transcode_jobs_state_lease (job_state, lease_expires_at),
  CONSTRAINT fk_video_transcode_jobs_video_assets FOREIGN KEY (video_asset_id) REFERENCES video_assets (video_asset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='영상 변환 작업';

-- ---------------------------------------------------------------
-- 4. 온라인강의 콘텐츠와 영상의 연결
-- ---------------------------------------------------------------
-- content_path, duration_sec은 변환이 끝났을 때 files가 learning의 port(VideoAssetListener)로 채운다.
-- 콘텐츠는 활동과 같이 지우지 않고 삭제 표시만 한다(ADR-0011). 학습 session, 진도, 적립 원장이 콘텐츠를 가리킨다.
ALTER TABLE online_lecture_items
  ADD COLUMN attachment_group_id BIGINT UNSIGNED NULL COMMENT '영상 첨부 그룹 ID(files.attachment_groups)' AFTER note,
  ADD COLUMN video_asset_id      BIGINT UNSIGNED NULL COMMENT '영상 asset ID(files.video_assets)' AFTER attachment_group_id,
  ADD COLUMN video_state         VARCHAR(12)     NULL COMMENT '영상 처리 상태(직접 등록한 경로는 NULL): processing|ready|failed|removed' AFTER video_asset_id,
  ADD COLUMN deleted_at          DATETIME(3)     NULL COMMENT '삭제 일시(삭제 표시. 학습 기록 보존)' AFTER updated_at,
  ADD UNIQUE KEY uq_online_lecture_items_video_asset (video_asset_id),
  ADD KEY idx_online_lecture_items_attachment_group (attachment_group_id),
  ADD CONSTRAINT fk_online_lecture_items_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id),
  ADD CONSTRAINT fk_online_lecture_items_video_assets FOREIGN KEY (video_asset_id) REFERENCES video_assets (video_asset_id);

-- ---------------------------------------------------------------
-- 5. 검사를 면제한 영상 원본의 blob 상태
-- ---------------------------------------------------------------
-- lecture_video 업로드는 악성코드 검사를 면제한다(ADR-0016 12번). 검사기가 있을 때 그 내용은 unscanned이고 clean이 아니다.
-- 다른 용도의 업로드가 같은 내용을 참조하면 quarantined로 바뀌어 검사를 받는다.
ALTER TABLE file_blobs
  MODIFY COLUMN blob_state VARCHAR(12) NOT NULL DEFAULT 'quarantined' COMMENT '검사 상태(quarantined|clean|rejected|unscanned: 검사를 면제한 영상 원본)';
