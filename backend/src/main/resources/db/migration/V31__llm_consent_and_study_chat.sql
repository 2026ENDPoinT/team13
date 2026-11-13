-- 70 summary: 외부 LLM 처리 동의, 학습 대화, LLM 사용량(docs/adr/0032-llm-consent-and-study-chat.md)
-- 동의 행은 무엇에 동의했는지(제공자 profile과 고지 판)를 그대로 남긴다. 철회해도 행을 지우지 않는다.
-- 학습 대화의 질문과 답 본문은 object storage(content bucket, 파일 파생물)에만 두고 이 테이블에는 상태와 사용량만 둔다(ADR-0007).
-- 과목 ID는 courses를 FK로 가리킨다. summary 모듈 코드는 courses를 읽지 않고, 과목 담당 여부는 summary.api.CourseStaff로 묻는다.

-- ---------------------------------------------------------------
-- 1. 요약 작업의 과목
-- ---------------------------------------------------------------
ALTER TABLE summary_jobs
  ADD COLUMN course_id BIGINT UNSIGNED NULL COMMENT '요청 경로의 과목 ID(동의 확인과 사용량 합계 범위, V31 이전 작업은 NULL)' AFTER file_version_no,
  MODIFY COLUMN error_code VARCHAR(30) NULL COMMENT '실패·취소 코드: interrupted|not_configured|file|provider|invalid_output|budget|input|internal|consent|disabled',
  ADD KEY idx_summary_jobs_course_requested_state (course_id, requested_by_user_id, job_state),
  ADD CONSTRAINT fk_summary_jobs_courses FOREIGN KEY (course_id) REFERENCES courses (course_id);

-- ---------------------------------------------------------------
-- 2. 외부 LLM 처리 동의
-- ---------------------------------------------------------------
CREATE TABLE llm_consents (
  llm_consent_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '외부 LLM 처리 동의 ID',
  course_id             BIGINT UNSIGNED NOT NULL COMMENT '동의 범위 과목 ID',
  instructor_user_id    BIGINT UNSIGNED NOT NULL COMMENT '동의한 교수자 사용자 ID',
  processing_scope      VARCHAR(20)     NOT NULL COMMENT '처리 범위: page_summary(쪽별 요약 생성)|study_chat(학습 대화)',
  notice_version        VARCHAR(40)     NOT NULL COMMENT '동의할 때 보여 준 고지 문구 판',
  profile_sha256        CHAR(64)        NOT NULL COMMENT '동의할 때의 제공자 profile 지문(SHA-256). 배포 profile이 바뀌면 동의가 효력을 잃는다',
  provider_name         VARCHAR(100)    NOT NULL COMMENT '제공자 이름(동의 당시 값)',
  provider_operator     VARCHAR(200)    NOT NULL COMMENT '제공자 운영 주체(동의 당시 값)',
  processing_region     VARCHAR(40)     NOT NULL COMMENT '처리·저장 지역(동의 당시 값, 예: CN, KR)',
  retention_days        INT UNSIGNED    NULL     COMMENT '제공자 보관 기간 일수(동의 당시 값, 0이면 보관 안 함, NULL이면 제공자가 밝히지 않음)',
  training_use          TINYINT(1)      NOT NULL COMMENT '제공자가 입력을 학습·개선에 쓸 수 있는지(동의 당시 값)',
  cross_border_transfer TINYINT(1)      NOT NULL COMMENT '국외 이전 여부(동의 당시 값)',
  risk_acknowledged     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '국외 이전·학습 이용을 따로 확인했는지',
  granted_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '동의 일시',
  revoked_at            DATETIME(3)     NULL     COMMENT '철회 일시(다른 profile로 다시 동의해 대체된 때도 기록)',
  active_key            TINYINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN revoked_at IS NULL THEN 1 ELSE NULL END) STORED COMMENT '유효한 동의 키(과목·교수자·범위마다 하나만 유효)',
  PRIMARY KEY (llm_consent_id),
  UNIQUE KEY uq_llm_consents_active (course_id, instructor_user_id, processing_scope, active_key),
  KEY idx_llm_consents_instructor (instructor_user_id),
  CONSTRAINT fk_llm_consents_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_llm_consents_users FOREIGN KEY (instructor_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='외부 LLM 처리 동의';

-- ---------------------------------------------------------------
-- 3. 학습 대화
-- ---------------------------------------------------------------
CREATE TABLE study_chats (
  study_chat_id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습 대화 ID',
  owner_user_id    BIGINT UNSIGNED NOT NULL COMMENT '대화 소유 사용자 ID(소유자만 읽고 지운다)',
  course_id        BIGINT UNSIGNED NOT NULL COMMENT '대화를 연 경로의 과목 ID',
  file_id          BIGINT UNSIGNED NOT NULL COMMENT '대화 근거 파일 ID(검색 범위는 이 파일 하나)',
  exchange_count   INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '질문 수',
  last_activity_at DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '마지막 질문 일시(보존 기간 기준)',
  deleted_at       DATETIME(3)     NULL     COMMENT '소유자 삭제 일시. 본문 객체를 지운 뒤 행도 지운다',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (study_chat_id),
  KEY idx_study_chats_owner_file (owner_user_id, file_id, study_chat_id),
  KEY idx_study_chats_course (course_id),
  KEY idx_study_chats_file (file_id),
  KEY idx_study_chats_deleted (deleted_at),
  KEY idx_study_chats_last_activity (last_activity_at),
  CONSTRAINT fk_study_chats_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_study_chats_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_study_chats_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습 대화';

CREATE TABLE study_chat_exchanges (
  study_chat_exchange_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습 대화 질문·답 ID',
  study_chat_id          BIGINT UNSIGNED NOT NULL COMMENT '학습 대화 ID',
  exchange_state         VARCHAR(12)     NOT NULL DEFAULT 'queued' COMMENT '상태: queued|running|answered|failed|cancelled',
  error_code             VARCHAR(30)     NULL     COMMENT '실패·취소 코드: consent|disabled|forbidden|unavailable|not_configured|budget|provider|invalid_output|interrupted|deleted|internal',
  call_count             INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '모델 호출 수',
  tool_call_count        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '실행한 도구 호출 수',
  input_tokens           BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '입력 token 수',
  output_tokens          BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '출력 token 수',
  lease_owner            VARCHAR(100)    NULL     COMMENT '실행 중인 worker 식별자',
  lease_expires_at       DATETIME(3)     NULL     COMMENT '실행 lease 만료 일시',
  started_at             DATETIME(3)     NULL     COMMENT '실행 시작 일시',
  finished_at            DATETIME(3)     NULL     COMMENT '종료 일시',
  active_chat_key        BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN exchange_state IN ('queued', 'running') THEN study_chat_id ELSE NULL END) STORED COMMENT '답을 기다리는 질문 키(대화마다 하나만)',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '질문 일시',
  PRIMARY KEY (study_chat_exchange_id),
  UNIQUE KEY uq_study_chat_exchanges_active_chat_key (active_chat_key),
  KEY idx_study_chat_exchanges_chat (study_chat_id, study_chat_exchange_id),
  KEY idx_study_chat_exchanges_state (exchange_state, study_chat_exchange_id),
  CONSTRAINT fk_study_chat_exchanges_study_chats FOREIGN KEY (study_chat_id) REFERENCES study_chats (study_chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습 대화 질문·답';

-- ---------------------------------------------------------------
-- 4. LLM 사용량
-- ---------------------------------------------------------------
CREATE TABLE llm_usage_daily (
  course_id        BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  usage_date       DATE            NOT NULL COMMENT '사용 날짜(DB 시계)',
  processing_scope VARCHAR(20)     NOT NULL COMMENT '처리 범위: page_summary|study_chat',
  request_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '끝난 요약 작업 또는 질문 수',
  call_count       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '모델 호출 수',
  input_tokens     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '입력 token 수',
  output_tokens    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '출력 token 수',
  updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '마지막 반영 일시',
  PRIMARY KEY (course_id, usage_date, processing_scope),
  CONSTRAINT fk_llm_usage_daily_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목별 일일 LLM 사용량(본문·사용자 없음)';
