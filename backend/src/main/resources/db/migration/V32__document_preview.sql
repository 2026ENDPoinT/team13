-- 60 files: 문서 미리보기 변환 대기열과 첫 쪽 썸네일(docs/adr/0033-document-preview-and-office-conversion.md)
-- rendition은 지금처럼 conversion_jobs 행 하나다. api가 requested로 넣고 worker가 lease를 잡아 converting → ready | failed로 바꾼다.
-- converter에 닿지 못한 시도와 lease가 끝난 시도는 시도 수가 남았으면 requested로 돌아간다.
-- 같은 파일 버전의 대기·변환 중·완료 rendition은 하나만 둔다(live_slot 유일 키). 실패한 행과 GC가 닫은(expired) 행은 몇 개여도 된다.

ALTER TABLE conversion_jobs
  ADD COLUMN attempt_count    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '변환 시도 수' AFTER error_code,
  ADD COLUMN next_attempt_at  DATETIME(3)  NULL COMMENT '다음 변환 시도 일시(빈 값=바로)' AFTER attempt_count,
  ADD COLUMN lease_owner      VARCHAR(100) NULL COMMENT '변환 중인 worker 식별자' AFTER next_attempt_at,
  ADD COLUMN lease_expires_at DATETIME(3)  NULL COMMENT '변환 lease 만료 일시' AFTER lease_owner,
  ADD COLUMN live_slot        VARCHAR(64)
      GENERATED ALWAYS AS (CASE WHEN conversion_state IN ('requested', 'converting', 'ready')
                                THEN CONCAT(file_id, ':', file_version_no, ':', conv_type) END) VIRTUAL
      COMMENT '대기·변환 중·완료 rendition의 파일 버전 식별값(유일). 실패·만료 행은 빈 값' AFTER lease_expires_at,
  ADD UNIQUE KEY uq_conversion_jobs_live_slot (live_slot),
  ADD KEY idx_conversion_jobs_queue (conversion_state, next_attempt_at);

-- 원본 ASTRA의 콘텐츠 썸네일(cid, id, 썸네일 파일)과 문서 첫 쪽 썸네일(rendition)을 같은 테이블에 둔다. 한 행은 둘 중 하나다.
-- 문서 썸네일 객체는 content bucket의 renditions/{key}/thumbnails/에 있어 rendition과 함께 지워진다.
ALTER TABLE file_thumbnails
  MODIFY COLUMN content_catalog_id BIGINT UNSIGNED NULL COMMENT '콘텐츠 카탈로그 번호(cid). 문서 썸네일은 빈 값',
  MODIFY COLUMN content_item_key   CHAR(20)        NULL COMMENT '콘텐츠 항목 키(id). 문서 썸네일은 빈 값',
  MODIFY COLUMN file_id            BIGINT UNSIGNED NULL COMMENT '썸네일 파일 ID(콘텐츠 썸네일). 문서 썸네일은 빈 값',
  ADD COLUMN conversion_job_id BIGINT UNSIGNED NULL COMMENT '문서 썸네일을 만든 rendition(변환 작업) ID' AFTER file_id,
  ADD COLUMN storage_key       VARCHAR(400)    NULL COMMENT '문서 썸네일 저장 키(content bucket, 서버 생성)' AFTER conversion_job_id,
  ADD COLUMN content_type      VARCHAR(50)     NULL COMMENT '저장할 때 정한 썸네일 MIME 타입' AFTER storage_key,
  ADD COLUMN size_bytes        INT UNSIGNED    NULL COMMENT '문서 썸네일 바이트 크기' AFTER content_type,
  ADD UNIQUE KEY uq_file_thumbnails_conversion_variant (conversion_job_id, variant),
  ADD CONSTRAINT fk_file_thumbnails_conversion_jobs FOREIGN KEY (conversion_job_id) REFERENCES conversion_jobs (conversion_job_id),
  ADD CONSTRAINT ck_file_thumbnails_source CHECK (
      (conversion_job_id IS NOT NULL AND storage_key IS NOT NULL AND content_type IS NOT NULL AND size_bytes IS NOT NULL
       AND content_catalog_id IS NULL AND content_item_key IS NULL AND file_id IS NULL)
      OR (conversion_job_id IS NULL AND storage_key IS NULL AND content_catalog_id IS NOT NULL
       AND content_item_key IS NOT NULL AND file_id IS NOT NULL));
