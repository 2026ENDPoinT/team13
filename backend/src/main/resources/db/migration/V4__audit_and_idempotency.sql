-- shared: 감사 기록(SC-09)과 일반 명령의 멱등 원장(SC-05). 결정은 docs/adr/0012-idempotency-ledger-and-audit.md에 있다.
-- 두 테이블 모두 요청 본문, 파일 이름, 자유 텍스트를 따로 저장하지 않는다. 멱등 원장의 receipt는 그 사용자가 이미 받은 응답 그대로이고 보존기한이 지나면 지운다.

-- ---------------------------------------------------------------
-- 1. 감사 기록
-- ---------------------------------------------------------------
CREATE TABLE audit_events (
  audit_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '감사 기록 ID',
  actor_user_id  BIGINT UNSIGNED NULL     COMMENT '행위자 사용자 ID(로그인 실패처럼 정할 수 없으면 NULL)',
  session_id     BIGINT UNSIGNED NULL     COMMENT '행위자 세션 ID',
  operation      VARCHAR(64)     NOT NULL COMMENT '연산 이름(예: material.create)',
  object_type    VARCHAR(40)     NULL     COMMENT '대상 종류(예: learning_activity, file)',
  object_id      BIGINT UNSIGNED NULL     COMMENT '대상 ID',
  course_id      BIGINT UNSIGNED NULL     COMMENT '과목 범위 ID(경로의 과목, 값으로만 저장)',
  result_code    VARCHAR(40)     NOT NULL COMMENT '결과 코드: ok 또는 거부 코드(예: rejected, locked)',
  request_id     CHAR(36)        NULL     COMMENT '요청 ID(응답 header X-Request-Id, worker 작업이면 NULL)',
  occurred_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '발생 일시(DB 시계)',
  PRIMARY KEY (audit_event_id),
  KEY idx_audit_events_actor_occurred (actor_user_id, occurred_at),
  KEY idx_audit_events_session (session_id),
  KEY idx_audit_events_object (object_type, object_id),
  KEY idx_audit_events_course_occurred (course_id, occurred_at),
  KEY idx_audit_events_request (request_id),
  CONSTRAINT fk_audit_events_users FOREIGN KEY (actor_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_audit_events_user_sessions FOREIGN KEY (session_id) REFERENCES user_sessions (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='감사 기록';

-- ---------------------------------------------------------------
-- 2. 멱등 원장
-- ---------------------------------------------------------------
CREATE TABLE idempotency_ledger (
  ledger_entry_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '멱등 원장 항목 ID',
  principal_user_id   BIGINT UNSIGNED NOT NULL COMMENT '요청한 사용자 ID(session principal)',
  operation           VARCHAR(64)     NOT NULL COMMENT '연산 이름(예: material.create)',
  idempotency_key     VARCHAR(100)    NOT NULL COMMENT '멱등 키(Idempotency-Key header, [A-Za-z0-9._-] 1~100자)',
  request_fingerprint CHAR(64)        NOT NULL COMMENT '요청 지문(대상 범위·본문·전제 조건의 정규 JSON SHA-256)',
  entry_state         VARCHAR(12)     NOT NULL DEFAULT 'reserved' COMMENT '상태: reserved(예약한 transaction 안에서만 보임)|completed',
  receipt_json        MEDIUMTEXT      NULL     COMMENT '완료 receipt(처음 돌려준 응답 본문 JSON)',
  expires_at          DATETIME(3)     NOT NULL COMMENT '보존기한. 지나면 worker가 지운다',
  completed_at        DATETIME(3)     NULL     COMMENT '완료 일시',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '예약 일시',
  PRIMARY KEY (ledger_entry_id),
  UNIQUE KEY uq_idempotency_ledger_principal_operation_key (principal_user_id, operation, idempotency_key),
  KEY idx_idempotency_ledger_expires_at (expires_at),
  CONSTRAINT fk_idempotency_ledger_users FOREIGN KEY (principal_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='멱등 원장';
