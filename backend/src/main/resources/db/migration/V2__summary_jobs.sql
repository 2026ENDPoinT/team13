-- 70 summary: 강의 자료의 쪽별 요약(docs/adr/0006-summary-module.md)
-- 요약 본문과 중간 결과는 object storage에만 두고 이 테이블에는 상태와 사용량만 둔다(docs/adr/0007-confidential-content-placement.md).

-- ---------------------------------------------------------------
-- 1. 요약 작업
-- ---------------------------------------------------------------
CREATE TABLE summary_jobs (
  summary_job_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '요약 작업 ID',
  file_id              BIGINT UNSIGNED NOT NULL COMMENT '요약 대상 파일 ID',
  file_version_no      INT UNSIGNED    NOT NULL COMMENT '요약 대상 파일 버전',
  requested_by_user_id BIGINT UNSIGNED NULL     COMMENT '작업을 요청한 사용자 ID',
  retry_of_job_id      BIGINT UNSIGNED NULL     COMMENT '재시도한 원래 작업 ID',
  job_state            VARCHAR(12)     NOT NULL DEFAULT 'queued' COMMENT '작업 상태: queued|running|completed|failed|cancelled',
  job_stage            VARCHAR(12)     NOT NULL DEFAULT 'queued' COMMENT '진행 단계: queued|pages|story|validate|done',
  pages_done           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '해설을 끝낸 쪽 수',
  pages_total          INT UNSIGNED    NULL     COMMENT '전체 쪽 수',
  model_name           VARCHAR(100)    NULL     COMMENT '생성 모델 이름',
  prompt_version       VARCHAR(30)     NULL     COMMENT '프롬프트 판',
  call_count           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '모델 호출 수',
  input_tokens         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '입력 token 수',
  output_tokens        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '출력 token 수',
  error_code           VARCHAR(30)     NULL     COMMENT '실패 코드: interrupted|not_configured|file|provider|invalid_output|budget|input|internal',
  error_message        VARCHAR(500)    NULL     COMMENT '실패 사유(비밀값과 자료 본문 제외)',
  cancel_requested_at  DATETIME(3)     NULL     COMMENT '취소 요청 일시',
  lease_owner          VARCHAR(100)    NULL     COMMENT '실행 중인 worker 식별자',
  lease_expires_at     DATETIME(3)     NULL     COMMENT '실행 lease 만료 일시',
  started_at           DATETIME(3)     NULL     COMMENT '실행 시작 일시',
  finished_at          DATETIME(3)     NULL     COMMENT '종료 일시',
  active_file_key      BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN job_state IN ('queued', 'running') THEN file_id ELSE NULL END) STORED COMMENT '진행 중 작업 파일 키(파일당 하나만 진행)',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '요청 일시',
  updated_at           DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (summary_job_id),
  UNIQUE KEY uq_summary_jobs_active_file_key (active_file_key),
  KEY idx_summary_jobs_file_created (file_id, created_at),
  KEY idx_summary_jobs_state_lease (job_state, lease_expires_at),
  KEY idx_summary_jobs_requested_by (requested_by_user_id),
  KEY idx_summary_jobs_retry_of (retry_of_job_id),
  CONSTRAINT fk_summary_jobs_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_summary_jobs_users FOREIGN KEY (requested_by_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_summary_jobs_summary_jobs FOREIGN KEY (retry_of_job_id) REFERENCES summary_jobs (summary_job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='요약 작업';

-- ---------------------------------------------------------------
-- 2. 요약 게시
-- ---------------------------------------------------------------
CREATE TABLE summary_publications (
  summary_publication_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '요약 게시 ID',
  file_id                BIGINT UNSIGNED NOT NULL COMMENT '파일 ID',
  summary_job_id         BIGINT UNSIGNED NOT NULL COMMENT '게시한 요약 작업 ID',
  published_by_user_id   BIGINT UNSIGNED NULL     COMMENT '게시한 사용자 ID',
  published_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '게시 일시',
  updated_at             DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (summary_publication_id),
  UNIQUE KEY uq_summary_publications_file (file_id),
  KEY idx_summary_publications_job (summary_job_id),
  KEY idx_summary_publications_published_by (published_by_user_id),
  CONSTRAINT fk_summary_publications_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_summary_publications_summary_jobs FOREIGN KEY (summary_job_id) REFERENCES summary_jobs (summary_job_id),
  CONSTRAINT fk_summary_publications_users FOREIGN KEY (published_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='요약 게시';
