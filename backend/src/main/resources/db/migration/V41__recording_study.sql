-- 60 files: 학습실 녹음의 전사·요약 작업과 결과(docs/adr/0041-recording-transcription-and-summary.md).
-- 녹음을 올리는 transaction에서 작업 행을 queued로 만들고, worker가 검사를 마친 녹음만 외부 음성 인식(Deepgram)과 요약 모델로 처리한다.
-- 결과는 녹음 소유자만 읽고, 녹음을 지우는 transaction에서 함께 지운다. 잠금 순서는 study_room_quotas → study_room_recordings →
-- study_room_recording_jobs → study_room_transcription_usage다(worker는 jobs → usage만 잠근다).

-- 녹음 하나의 작업 하나(전사 또는 요약). 실패 문장은 저장하지 않고 failure_code를 읽을 때 정해 둔 문장으로 바꾼다.
CREATE TABLE study_room_recording_jobs (
  study_room_recording_job_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '녹음 작업 ID',
  owner_user_id               BIGINT UNSIGNED NOT NULL COMMENT '녹음 소유 사용자 ID',
  study_room_recording_id     BIGINT UNSIGNED NOT NULL COMMENT '녹음 ID',
  job_kind                    VARCHAR(16)     NOT NULL COMMENT '작업 종류: transcription|summary',
  job_state                   VARCHAR(16)     NOT NULL DEFAULT 'queued' COMMENT '상태: queued|processing|ready|failed',
  failure_code                VARCHAR(32)     NULL     COMMENT '실패 또는 마지막 재시도 사유 코드(화면 문장은 코드로 정한다)',
  result_json                 MEDIUMTEXT      NULL     COMMENT 'ready 결과(프론트 계약 형식 JSON: 전사 segments·text 또는 요약 overview·sections·note)',
  attempt_count               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '실행을 시작한 횟수',
  next_attempt_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '다음 실행을 시작할 수 있는 일시',
  lease_owner                 VARCHAR(128)    NULL     COMMENT '실행 중인 worker',
  lease_expires_at            DATETIME(3)     NULL     COMMENT '실행 lease 만료 일시',
  reserved_seconds            INT UNSIGNED    NULL     COMMENT '전사 실행 동안 사용량에 잡아 둔 오디오 초',
  reserved_on                 DATE            NULL     COMMENT '사용량을 잡아 둔 날짜(study_room_transcription_usage.usage_date)',
  audio_seconds               INT UNSIGNED    NULL     COMMENT '제공자가 처리한 오디오 길이(초, 올림)',
  created_at                  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  started_at                  DATETIME(3)     NULL     COMMENT '마지막 실행 시작 일시',
  finished_at                 DATETIME(3)     NULL     COMMENT '끝난(ready, failed) 일시',
  updated_at                  DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (study_room_recording_job_id),
  UNIQUE KEY uq_study_room_recording_jobs_recording_kind (study_room_recording_id, job_kind),
  KEY idx_study_room_recording_jobs_claim (job_kind, job_state, next_attempt_at),
  KEY idx_study_room_recording_jobs_lease (job_state, lease_expires_at),
  KEY idx_study_room_recording_jobs_owner_recording (owner_user_id, study_room_recording_id),
  CONSTRAINT ck_study_room_recording_jobs_kind CHECK (job_kind IN ('transcription', 'summary')),
  CONSTRAINT ck_study_room_recording_jobs_state CHECK (job_state IN ('queued', 'processing', 'ready', 'failed')),
  CONSTRAINT fk_study_room_recording_jobs_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_study_room_recording_jobs_study_room_recordings FOREIGN KEY (owner_user_id, study_room_recording_id) REFERENCES study_room_recordings (owner_user_id, study_room_recording_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 녹음 전사·요약 작업';

-- 사용자별 하루 전사 오디오 사용량. 녹음을 지워도 줄지 않아 지우고 다시 올려 한도를 피하지 못한다. 날짜는 DB 날짜(기관 시간대)다.
CREATE TABLE study_room_transcription_usage (
  owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  usage_date    DATE            NOT NULL COMMENT '날짜',
  audio_seconds INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '전사했거나 전사 중인 오디오 초',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at    DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (owner_user_id, usage_date),
  CONSTRAINT fk_study_room_transcription_usage_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습실 녹음 전사 사용량';
