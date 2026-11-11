-- 내정보·개인설정·접속이력, 재인증(step-up), 학습 인증 grant와 적립 경계(SC-06-A, ADR-0029).
-- identity 표는 V1 구조를 그대로 쓰고 값 목록 주석만 넓힌다. learning_sessions에 적립 유효기한과 재인증 대기 상태를 더한다.

ALTER TABLE session_step_ups
  MODIFY COLUMN step_up_purpose VARCHAR(30) NOT NULL COMMENT '목적: RECENT_AUTH(민감한 연산 전 재인증)|LEARNING_AUTH(학습 인증 grant)|CONTACT_VIEW|CONTACT_EDIT|PASSWORD_CHANGE',
  MODIFY COLUMN granted_method VARCHAR(20) NOT NULL COMMENT '통과 수단: SSO(학교 IdP 재로그인)|TOTP(break-glass)|SECONDARY_AUTH(2차 인증 challenge)|DEV(로컬 실행 전용)|PASSWORD',
  MODIFY COLUMN expires_at DATETIME(3) NOT NULL COMMENT '만료 일시. 폐기하면 폐기 시각으로 당긴다';

ALTER TABLE secondary_auth_challenges
  MODIFY COLUMN auth_way TINYINT UNSIGNED NOT NULL COMMENT '수단: 1 앱푸시|2 이메일|3 SMS|4 PUSH|5 앱인증|6 학교 IdP 재인증';

ALTER TABLE user_sessions
  MODIFY COLUMN revoke_reason VARCHAR(30) NULL COMMENT '폐기 사유: LOGOUT|USER_REVOKED(본인이 다른 session 종료)|DEVICE_REVOKED|PASSWORD_CHANGED|EXPIRED|ADMIN';

ALTER TABLE learning_sessions
  MODIFY COLUMN state VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '세션 상태(active|reauth_required|ended|superseded|lease_expired). reauth_required는 열려 있지만 학습 인증이 끝나 적립하지 않는 상태',
  ADD COLUMN credit_authorized_until DATETIME(3) NULL COMMENT '적립 유효기한 min(학습 인증 grant 만료, close_at, 로그인 session 만료). 학습 인증을 요구하지 않는 배포는 NULL' AFTER lease_sec;

ALTER TABLE learning_sessions
  MODIFY COLUMN active_user_key BIGINT UNSIGNED GENERATED ALWAYS AS (IF(state IN ('active', 'reauth_required'), user_id, NULL)) STORED COMMENT '열린 세션(active, reauth_required) 사용자(부분 유일 제약용)';
