-- ASTRA(e-Class ILOS 재구현) MySQL 8 기준 스키마. 명세: reference/*.md, reference/backend/*.md. 도메인별 근거: reference/db/design/*.md
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ===== 10_identity =====
CREATE TABLE users (
  user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '사용자 ID',
  external_ref VARCHAR(64) NOT NULL COMMENT '학사 식별자(학번·교번)',
  user_name VARCHAR(100) NOT NULL COMMENT '이름',
  user_role VARCHAR(20) NOT NULL COMMENT '역할: STUDENT|PROFESSOR|TA|STAFF',
  department_code VARCHAR(20) NULL COMMENT '학과·소속 코드',
  department_name VARCHAR(200) NULL COMMENT '학과·소속명',
  grade SMALLINT UNSIGNED NULL COMMENT '학년(학생만, 1~6)',
  account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '계정 상태: ACTIVE|SUSPENDED|WITHDRAWN',
  legacy_user_key VARCHAR(64) NULL COMMENT 'ILOS 원본 사용자 키(추적용)',
  last_synced_at DATETIME(3) NULL COMMENT '학사정보 최종 동기화 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (user_id),
  UNIQUE KEY uq_users_external_ref (external_ref),
  KEY idx_users_role (user_role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사용자(학생·교수·조교·직원)';

CREATE TABLE user_credentials (
  credential_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '자격증명 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  password_hash VARCHAR(255) NULL COMMENT '비밀번호 해시(학교 통합인증 계정이면 NULL)',
  hash_algorithm VARCHAR(30) NULL COMMENT '해시 알고리즘(예: ARGON2ID)',
  password_changed_at DATETIME(3) NULL COMMENT '비밀번호 최종 변경 일시',
  failed_attempt_count SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '연속 인증 실패 횟수',
  locked_until DATETIME(3) NULL COMMENT '인증 잠금 해제 예정 일시',
  must_change_password TINYINT(1) NOT NULL DEFAULT 0 COMMENT '다음 로그인 시 변경 필요 여부',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (credential_id),
  UNIQUE KEY uq_user_credentials_user_id (user_id),
  CONSTRAINT fk_user_credentials_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='로그인 자격증명';

CREATE TABLE user_contacts (
  contact_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '연락처 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  phone_number VARCHAR(32) NULL COMMENT '휴대전화번호 원문',
  email_address VARCHAR(255) NULL COMMENT '이메일 주소 원문',
  contact_source VARCHAR(20) NOT NULL DEFAULT 'ACADEMIC' COMMENT '연락처 원천: ACADEMIC|LOCAL',
  synced_at DATETIME(3) NULL COMMENT '학사시스템 동기화 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (contact_id),
  UNIQUE KEY uq_user_contacts_user_id (user_id),
  CONSTRAINT fk_user_contacts_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사용자 연락처';

CREATE TABLE profile_images (
  profile_image_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '프로필 이미지 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  storage_key VARCHAR(512) NOT NULL COMMENT '비공개 저장 경로(웹 루트 밖)',
  mime_type VARCHAR(100) NOT NULL DEFAULT 'image/png' COMMENT 'MIME 유형',
  file_size BIGINT UNSIGNED NULL COMMENT '파일 크기(바이트)',
  image_width SMALLINT UNSIGNED NULL COMMENT '이미지 가로(픽셀)',
  image_height SMALLINT UNSIGNED NULL COMMENT '이미지 세로(픽셀)',
  content_sha256 CHAR(64) NULL COMMENT '파일 SHA-256',
  crop_zoom SMALLINT UNSIGNED NOT NULL DEFAULT 100 COMMENT '자르기 확대율(10~500%)',
  crop_pos_x INT NOT NULL DEFAULT 0 COMMENT '자르기 가로 위치(픽셀)',
  crop_pos_y INT NOT NULL DEFAULT 0 COMMENT '자르기 세로 위치(픽셀)',
  image_status VARCHAR(10) NOT NULL DEFAULT 'TEMP' COMMENT '상태: TEMP|ACTIVE|DELETED',
  expires_at DATETIME(3) NULL COMMENT '임시 이미지 만료 일시',
  deleted_at DATETIME(3) NULL COMMENT '삭제 표시 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (profile_image_id),
  KEY idx_profile_images_user_status (user_id, image_status),
  CONSTRAINT fk_profile_images_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='프로필 이미지';

CREATE TABLE user_profiles (
  profile_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '프로필 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  avatar_type VARCHAR(10) NOT NULL DEFAULT 'COLOR' COMMENT '아바타 유형: COLOR(색상형)|IMAGE(이미지형)',
  avatar_color VARCHAR(32) NULL COMMENT '아바타 색상 키: tomato|tangerine|pumpkin|mango|banana|chick|avocado|pistachio|basil|eucalyptus|sage|peacock|cobalt|blueberry|lavender|light-purple|amethyst|grape|purple|cherry-blossoms|light-pink|cocoa|graphite|birch-tree',
  short_name VARCHAR(16) NULL COMMENT '아바타 약칭(그래핌 2자 이내)',
  profile_image_id BIGINT UNSIGNED NULL COMMENT '현재 적용된 프로필 이미지 ID',
  profile_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (profile_id),
  UNIQUE KEY uq_user_profiles_user_id (user_id),
  KEY idx_user_profiles_profile_image_id (profile_image_id),
  CONSTRAINT fk_user_profiles_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_user_profiles_profile_images FOREIGN KEY (profile_image_id) REFERENCES profile_images (profile_image_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사용자 프로필(아바타)';

CREATE TABLE user_devices (
  device_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '기기 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  device_uuid CHAR(36) NOT NULL COMMENT '외부 노출 기기 식별자(UUID)',
  device_name VARCHAR(100) NULL COMMENT '접속환경 표시명(PC 또는 제조사_모델코드)',
  client_type VARCHAR(10) NOT NULL COMMENT '접속 구분: BROWSER|APP',
  os_name VARCHAR(50) NULL COMMENT '운영체제',
  model_code VARCHAR(100) NULL COMMENT '기기 모델 코드',
  app_version VARCHAR(30) NULL COMMENT '앱 버전',
  push_token_hash VARCHAR(128) NULL COMMENT '푸시 토큰 해시',
  is_certified TINYINT(1) NOT NULL DEFAULT 0 COMMENT '기기인증 여부(Y/-)',
  certified_at DATETIME(3) NULL COMMENT '기기인증 완료 일시',
  registered_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '기기 등록 일시',
  last_access_at DATETIME(3) NULL COMMENT '최종 접속 일시',
  revoked_at DATETIME(3) NULL COMMENT '폐기 일시(삭제 대신 소프트 폐기)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (device_id),
  UNIQUE KEY uq_user_devices_device_uuid (device_uuid),
  KEY idx_user_devices_user_last_access (user_id, last_access_at),
  CONSTRAINT fk_user_devices_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='등록 기기';

CREATE TABLE user_sessions (
  session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '세션 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  device_id BIGINT UNSIGNED NULL COMMENT '기기 ID(브라우저 세션은 NULL 가능)',
  session_token_hash CHAR(64) NOT NULL COMMENT '세션 토큰 해시(불투명 난수)',
  session_epoch CHAR(32) NOT NULL COMMENT '탭 간 세션 교체 감지용 세대 값',
  ip_address VARCHAR(45) NULL COMMENT '세션 시작 IP',
  user_agent VARCHAR(255) NULL COMMENT '브라우저 사용자 에이전트',
  authenticated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '최초 인증 일시',
  last_seen_at DATETIME(3) NULL COMMENT '최종 활동 일시',
  revoked_at DATETIME(3) NULL COMMENT '세션 폐기 일시',
  revoke_reason VARCHAR(30) NULL COMMENT '폐기 사유: LOGOUT|DEVICE_REVOKED|PASSWORD_CHANGED|EXPIRED|ADMIN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (session_id),
  UNIQUE KEY uq_user_sessions_session_token_hash (session_token_hash),
  KEY idx_user_sessions_user_revoked (user_id, revoked_at),
  KEY idx_user_sessions_device_id (device_id),
  CONSTRAINT fk_user_sessions_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_user_sessions_user_devices FOREIGN KEY (device_id) REFERENCES user_devices (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='로그인 세션';

CREATE TABLE session_step_ups (
  step_up_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '재인증 부여 ID',
  session_id BIGINT UNSIGNED NOT NULL COMMENT '세션 ID',
  step_up_purpose VARCHAR(30) NOT NULL COMMENT '목적: CONTACT_VIEW|CONTACT_EDIT|PASSWORD_CHANGE|LEARNING_AUTH',
  granted_method VARCHAR(20) NOT NULL COMMENT '통과 수단: PASSWORD|SSO|SECONDARY_AUTH',
  granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '부여 일시',
  expires_at DATETIME(3) NOT NULL COMMENT '만료 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (step_up_id),
  UNIQUE KEY uq_session_step_ups_session_purpose (session_id, step_up_purpose),
  CONSTRAINT fk_session_step_ups_user_sessions FOREIGN KEY (session_id) REFERENCES user_sessions (session_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='세션 재인증 부여';

CREATE TABLE secondary_auth_challenges (
  challenge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '2차 인증 시도 ID',
  auth_seq VARCHAR(64) NOT NULL COMMENT '클라이언트에 노출되는 인증 시퀀스(AUTH_SEQ)',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  session_id BIGINT UNSIGNED NULL COMMENT '요청 세션 ID',
  device_id BIGINT UNSIGNED NULL COMMENT '인증 대상 기기 ID(수단 1·4·5)',
  auth_div SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '인증 구분(AUTH_DIV, 1=온라인학습)',
  auth_way TINYINT UNSIGNED NOT NULL COMMENT '수단: 1 앱푸시|2 이메일|3 SMS|4 PUSH|5 앱인증',
  destination_type VARCHAR(10) NOT NULL COMMENT '대상 유형: DEVICE|EMAIL|PHONE|USER',
  destination_label VARCHAR(120) NULL COMMENT '마스킹된 대상 표시값',
  code_hash VARCHAR(255) NULL COMMENT '인증코드 해시',
  request_code VARCHAR(20) NULL COMMENT '앱 인증 표시용 요청 코드',
  attempt_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '확인 시도 횟수',
  max_attempts TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '허용 시도 횟수',
  expires_at DATETIME(3) NOT NULL COMMENT '만료 일시(서버 시각 기준)',
  challenge_status VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT '상태: PENDING|CONFIRMED|EXPIRED|CANCELLED|LOCKED',
  confirmed_at DATETIME(3) NULL COMMENT '인증 완료 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (challenge_id),
  UNIQUE KEY uq_secondary_auth_challenges_auth_seq (auth_seq),
  KEY idx_secondary_auth_challenges_user_status (user_id, challenge_status),
  KEY idx_secondary_auth_challenges_session_id (session_id),
  KEY idx_secondary_auth_challenges_device_id (device_id),
  CONSTRAINT fk_secondary_auth_challenges_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_secondary_auth_challenges_user_sessions FOREIGN KEY (session_id) REFERENCES user_sessions (session_id),
  CONSTRAINT fk_secondary_auth_challenges_user_devices FOREIGN KEY (device_id) REFERENCES user_devices (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='2차 본인인증 시도';

CREATE TABLE login_events (
  login_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '로그인 기록 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  session_id BIGINT UNSIGNED NULL COMMENT '세션 ID',
  device_id BIGINT UNSIGNED NULL COMMENT '기기 ID',
  ip_address VARCHAR(45) NULL COMMENT '접속 IP',
  client_environment VARCHAR(10) NOT NULL COMMENT '앱 구분: PC|MOBILE',
  login_result VARCHAR(10) NOT NULL DEFAULT 'SUCCESS' COMMENT '결과: SUCCESS|FAILURE',
  failure_reason VARCHAR(50) NULL COMMENT '실패 사유',
  logged_in_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '접속 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (login_event_id),
  KEY idx_login_events_user_logged_in_at (user_id, logged_in_at),
  KEY idx_login_events_session_id (session_id),
  KEY idx_login_events_device_id (device_id),
  CONSTRAINT fk_login_events_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_login_events_user_sessions FOREIGN KEY (session_id) REFERENCES user_sessions (session_id),
  CONSTRAINT fk_login_events_user_devices FOREIGN KEY (device_id) REFERENCES user_devices (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='로그인 기록';

CREATE TABLE user_preferences (
  preference_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '개인설정 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  language_code VARCHAR(5) NOT NULL DEFAULT 'ko' COMMENT '언어: ko|en',
  time_format VARCHAR(2) NOT NULL DEFAULT '12' COMMENT '시간 표기: 12|24',
  theme_mode VARCHAR(10) NOT NULL DEFAULT 'system' COMMENT '화면 모드: system|light|dark',
  dnd_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '푸시 알림 에티켓(방해 금지) 사용 여부',
  dnd_start_time TIME NULL COMMENT '에티켓 시작 시각',
  dnd_end_time TIME NULL COMMENT '에티켓 종료 시각',
  home_aside_visible TINYINT(1) NOT NULL DEFAULT 1 COMMENT '홈 우측 패널 표시 여부(MAIN_ASIDE_VIEW_YN)',
  first_main VARCHAR(4) NULL COMMENT '홈 첫 화면 구분(FIRST_MAIN)',
  preference_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (preference_id),
  UNIQUE KEY uq_user_preferences_user_id (user_id),
  CONSTRAINT fk_user_preferences_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='개인설정';

CREATE TABLE password_change_logs (
  password_change_log_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '비밀번호 변경 이력 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  change_source VARCHAR(10) NOT NULL COMMENT '변경 경로: SELF|SSO|ADMIN',
  change_result VARCHAR(10) NOT NULL DEFAULT 'SUCCESS' COMMENT '결과: SUCCESS|FAILURE',
  request_ip VARCHAR(45) NULL COMMENT '요청 IP',
  changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '변경 시도 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (password_change_log_id),
  KEY idx_password_change_logs_user_changed_at (user_id, changed_at),
  CONSTRAINT fk_password_change_logs_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='비밀번호 변경 이력';

CREATE TABLE academic_sync_logs (
  academic_sync_log_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학사 동기화 이력 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  sync_result VARCHAR(10) NOT NULL COMMENT '결과: SUCCESS|FAILURE',
  result_message VARCHAR(255) NULL COMMENT '처리 결과 메시지',
  synced_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '동기화 실행 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (academic_sync_log_id),
  KEY idx_academic_sync_logs_user_synced_at (user_id, synced_at),
  CONSTRAINT fk_academic_sync_logs_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학사 동기화 이력';


-- ===== 20_catalog =====
CREATE TABLE terms (
  term_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학기 ID',
  institution_code VARCHAR(20) NOT NULL DEFAULT 'SEOULTECH' COMMENT '기관 코드',
  term_year SMALLINT UNSIGNED NOT NULL COMMENT '학년도(예: 2026)',
  term_code VARCHAR(4) NOT NULL COMMENT '학기 코드: 1|2|3|4|5|F3 (3=2학기, 5=디스커버리학기, 4=겨울학기, F3=2학기(미래융합))',
  term_name VARCHAR(60) NOT NULL COMMENT '학기 표시명(예: 2026년 2학기)',
  term_type VARCHAR(12) NOT NULL DEFAULT 'REGULAR' COMMENT '학기 구분: REGULAR|SPECIAL|NONREGULAR',
  start_date DATE NULL COMMENT '학기 시작일',
  end_date DATE NULL COMMENT '학기 종료일',
  is_current TINYINT(1) NOT NULL DEFAULT 0 COMMENT '현재 학기 여부',
  sort_order SMALLINT NOT NULL DEFAULT 0 COMMENT '정렬 순서(최근 학기 우선, YearInfo 배열 순서)',
  legacy_year_term VARCHAR(12) NULL COMMENT 'ILOS 학기 키(예: 2026^3)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (term_id),
  UNIQUE KEY uq_terms_year_code (institution_code, term_year, term_code),
  KEY idx_terms_current (is_current, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학기';

CREATE TABLE courses (
  course_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '과목(개설 강좌) ID',
  term_id BIGINT UNSIGNED NULL COMMENT '학기 ID(비정규 과정은 NULL)',
  course_type VARCHAR(10) NOT NULL COMMENT '과목 구분: 정규|비정규',
  course_name VARCHAR(300) NOT NULL COMMENT '과목명',
  course_code VARCHAR(20) NULL COMMENT '학수번호·과정번호(LSSN_CD, 예: 109834, 641)',
  section_no VARCHAR(10) NULL COMMENT '분반·차수(SUBJT_NO, 예: 21001, 01)',
  org_sect VARCHAR(2) NULL COMMENT '대학구분: A 대학|G 대학원|F 미래융합대학',
  ledger_year SMALLINT UNSIGNED NULL COMMENT '학사 학년도(LEDG_YEAR)',
  ledger_session VARCHAR(6) NULL COMMENT '학사 학기 코드(LEDG_SESSN, 예: 3, F3, B)',
  subject_no VARCHAR(20) NULL COMMENT '교과목 번호(SUBJT_NO 원본 값)',
  legacy_kjkey VARCHAR(40) NULL COMMENT 'ILOS 과목 키(KJKEY, 예: A2026310983421001, 비2026B64101)',
  completion_type VARCHAR(30) NULL COMMENT '이수구분(예: 전공선택/전선)',
  credit DECIMAL(3,1) NULL COMMENT '학점',
  grade_year TINYINT UNSIGNED NULL COMMENT '대상 학년',
  department_code VARCHAR(20) NULL COMMENT '개설 학과·부서 코드',
  department_name VARCHAR(200) NULL COMMENT '개설 학과·부서명',
  lecture_type VARCHAR(20) NULL COMMENT '수업형태: 이클래스|이러닝수업|블렌디드|플립',
  audit_allowed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '청강신청 가능 여부(SCH_OPEN_YN=Y)',
  cover_image_url VARCHAR(512) NULL COMMENT '표지 이미지 경로(업로드 표지 또는 기본 표지)',
  cover_template VARCHAR(40) NULL COMMENT '기본 표지 템플릿 키(dynamic_N, gradient_N)',
  is_merged_class TINYINT(1) NOT NULL DEFAULT 0 COMMENT '합반 과목 여부',
  course_status VARCHAR(12) NOT NULL DEFAULT 'OPEN' COMMENT '개설 상태: OPEN|CLOSED|CANCELLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_id),
  UNIQUE KEY uq_courses_legacy_kjkey (legacy_kjkey),
  UNIQUE KEY uq_courses_academic_key (term_id, org_sect, course_code, section_no),
  KEY idx_courses_term_type (term_id, course_type),
  KEY idx_courses_type_status (course_type, course_status),
  KEY idx_courses_department (department_code),
  KEY idx_courses_code (course_code),
  CONSTRAINT fk_courses_terms FOREIGN KEY (term_id) REFERENCES terms (term_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목(개설 강좌)';

CREATE TABLE course_instructors (
  course_instructor_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '과목 담당자 ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID(교수·조교·담당 직원)',
  instructor_role VARCHAR(12) NOT NULL COMMENT '담당 역할: PROFESSOR|TA|MANAGER(비정규 담당자)',
  is_primary TINYINT(1) NOT NULL DEFAULT 0 COMMENT '대표 담당 여부',
  sort_order TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '표시 순서',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_instructor_id),
  UNIQUE KEY uq_course_instructors_course_user_role (course_id, user_id, instructor_role),
  KEY idx_course_instructors_user (user_id),
  CONSTRAINT fk_course_instructors_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_course_instructors_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목 담당자';

CREATE TABLE course_schedules (
  course_schedule_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '수업 시간 슬롯 ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  day_of_week TINYINT UNSIGNED NOT NULL COMMENT '요일: 0 일요일 ~ 6 토요일',
  period_start TINYINT UNSIGNED NULL COMMENT '시작 교시(예: 6)',
  period_end TINYINT UNSIGNED NULL COMMENT '종료 교시(예: 8)',
  start_time TIME NULL COMMENT '수업 시작 시각',
  end_time TIME NULL COMMENT '수업 종료 시각',
  room_name VARCHAR(100) NULL COMMENT '강의실명(미등록 시 NULL, 화면 표기는 "-")',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_schedule_id),
  KEY idx_course_schedules_course_day (course_id, day_of_week, start_time),
  CONSTRAINT fk_course_schedules_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='수업 시간 슬롯';

CREATE TABLE course_merged_groups (
  merged_group_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '합반 그룹 ID',
  term_id BIGINT UNSIGNED NULL COMMENT '학기 ID',
  group_name VARCHAR(200) NULL COMMENT '합반 그룹명(예: 대표 과목명)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (merged_group_id),
  KEY idx_course_merged_groups_term (term_id),
  CONSTRAINT fk_course_merged_groups_terms FOREIGN KEY (term_id) REFERENCES terms (term_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='합반 그룹';

CREATE TABLE course_merged_group_members (
  merged_group_member_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '합반 구성 분반 ID',
  merged_group_id BIGINT UNSIGNED NOT NULL COMMENT '합반 그룹 ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  is_primary TINYINT(1) NOT NULL DEFAULT 0 COMMENT '대표 분반 여부(habban_main_dv=1)',
  primary_scope_key TINYINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN is_primary = 1 THEN 1 ELSE NULL END) STORED COMMENT '그룹당 대표 분반 1개 보장용 보조 키',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (merged_group_member_id),
  UNIQUE KEY uq_course_merged_group_members_course (course_id),
  UNIQUE KEY uq_course_merged_group_members_primary (merged_group_id, primary_scope_key),
  KEY idx_course_merged_group_members_group (merged_group_id),
  CONSTRAINT fk_course_merged_group_members_groups FOREIGN KEY (merged_group_id) REFERENCES course_merged_groups (merged_group_id),
  CONSTRAINT fk_course_merged_group_members_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='합반 구성 분반';

CREATE TABLE course_enrollments (
  enrollment_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '수강(등록) ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  enrollment_kind VARCHAR(12) NOT NULL COMMENT '등록 구분: REGISTRAR(정규수강)|AUDIT(청강)|NONREGULAR(비정규)',
  enrollment_role VARCHAR(12) NOT NULL DEFAULT 'STUDENT' COMMENT '과목 내 역할: STUDENT|AUDITOR|PROFESSOR|TA',
  enrollment_status VARCHAR(12) NOT NULL COMMENT '상태: REQUESTED(AUDIT 신청)|PENDING(비정규 승인대기)|WAITLISTED|ENROLLED|REJECTED|CANCELLED (정규는 ENROLLED|CANCELLED)',
  holds_seat TINYINT(1) NOT NULL DEFAULT 0 COMMENT '좌석 점유 여부(취소 시 반환 판단)',
  applied_at DATETIME(3) NULL COMMENT '신청 일시',
  decided_at DATETIME(3) NULL COMMENT '승인·반려 처리 일시',
  cancelled_at DATETIME(3) NULL COMMENT '취소 일시',
  source VARCHAR(12) NOT NULL DEFAULT 'USER' COMMENT '등록 원천: REGISTRAR|USER|SYSTEM',
  external_sync_at DATETIME(3) NULL COMMENT '학사 동기화 일시',
  active_scope_key TINYINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN enrollment_status IN ('REQUESTED','PENDING','WAITLISTED','ENROLLED') THEN 1 ELSE NULL END) STORED COMMENT '활성 상태 중복 방지용 보조 키',
  version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '낙관적 잠금 버전',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (enrollment_id),
  UNIQUE KEY uq_course_enrollments_active (course_id, user_id, enrollment_kind, active_scope_key),
  KEY idx_course_enrollments_user_status (user_id, enrollment_status),
  KEY idx_course_enrollments_course_status (course_id, enrollment_status),
  KEY idx_course_enrollments_user_kind (user_id, enrollment_kind),
  CONSTRAINT fk_course_enrollments_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_course_enrollments_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='수강(등록) 원장';

CREATE TABLE enrollment_events (
  enrollment_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '수강 상태 이벤트 ID',
  enrollment_id BIGINT UNSIGNED NOT NULL COMMENT '수강(등록) ID',
  event_seq SMALLINT UNSIGNED NOT NULL COMMENT '등록별 이벤트 순번',
  event_type VARCHAR(20) NOT NULL COMMENT '이벤트: APPLY|APPROVE|REJECT|CANCEL|REAPPLY|SYNC',
  from_status VARCHAR(12) NULL COMMENT '이전 상태',
  to_status VARCHAR(12) NOT NULL COMMENT '이후 상태',
  actor_user_id BIGINT UNSIGNED NULL COMMENT '처리 주체 사용자 ID(시스템 처리면 NULL)',
  event_source VARCHAR(12) NOT NULL DEFAULT 'USER' COMMENT '원천: USER|PROFESSOR|STAFF|SYSTEM|REGISTRAR',
  reason VARCHAR(500) NULL COMMENT '처리 사유·메시지',
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '발생 일시(서버 시각)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (enrollment_event_id),
  UNIQUE KEY uq_enrollment_events_seq (enrollment_id, event_seq),
  KEY idx_enrollment_events_occurred (occurred_at),
  KEY idx_enrollment_events_actor (actor_user_id),
  CONSTRAINT fk_enrollment_events_course_enrollments FOREIGN KEY (enrollment_id) REFERENCES course_enrollments (enrollment_id),
  CONSTRAINT fk_enrollment_events_users FOREIGN KEY (actor_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='수강 상태 이벤트';

CREATE TABLE audit_request_details (
  enrollment_id BIGINT UNSIGNED NOT NULL COMMENT '수강(청강 신청) ID',
  request_reason VARCHAR(1000) NOT NULL COMMENT '청강 사유(APPLY_REASON)',
  reason_updated_at DATETIME(3) NULL COMMENT '사유 최종 수정 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (enrollment_id),
  CONSTRAINT fk_audit_request_details_course_enrollments FOREIGN KEY (enrollment_id) REFERENCES course_enrollments (enrollment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='청강 신청 상세';

CREATE TABLE non_regular_offerings (
  offering_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '비정규 과정 설정 ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  nonregular_category VARCHAR(2) NOT NULL COMMENT '비정규 분류: B 비교과|C 기초학력|K K-digital',
  category_code VARCHAR(20) NULL COMMENT '과목분류 코드(학과·부서, SCH_CATEGORY)',
  lecture_type VARCHAR(20) NULL COMMENT '수업형태: 이클래스|이러닝수업|블렌디드|플립',
  approval_mode VARCHAR(10) NOT NULL DEFAULT 'AUTO' COMMENT '승인 방식: AUTO(신청 즉시 승인)|MANUAL(승인 대기)',
  round_year SMALLINT UNSIGNED NOT NULL COMMENT '차수 연도(수강과목 연도 그룹)',
  round_no SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '차수 번호',
  round_label VARCHAR(40) NULL COMMENT '차수 표시명(예: [2026년 1차수])',
  application_open_at DATETIME(3) NOT NULL COMMENT '수강신청 시작 일시',
  application_close_at DATETIME(3) NOT NULL COMMENT '수강신청 종료 일시',
  cancel_close_at DATETIME(3) NULL COMMENT '수강취소 마감 일시',
  learning_start_date DATE NULL COMMENT '학습기간 시작일',
  learning_end_date DATE NULL COMMENT '학습기간 종료일',
  introduction_html MEDIUMTEXT NULL COMMENT '강의소개·강의계획서(에디터 본문)',
  cancel_allowed TINYINT(1) NOT NULL DEFAULT 1 COMMENT '학생 수강취소 허용 여부',
  offering_status VARCHAR(12) NOT NULL DEFAULT 'OPEN' COMMENT '과정 상태: OPEN|CLOSED',
  legacy_course_no VARCHAR(20) NULL COMMENT 'ILOS 과정번호-차수(예: 641-01)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (offering_id),
  UNIQUE KEY uq_non_regular_offerings_course (course_id),
  KEY idx_non_regular_offerings_round (round_year, round_no),
  KEY idx_non_regular_offerings_period (application_open_at, application_close_at),
  KEY idx_non_regular_offerings_category (nonregular_category, category_code),
  CONSTRAINT fk_non_regular_offerings_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='비정규 과정 설정';

CREATE TABLE non_regular_targets (
  non_regular_target_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '비정규 신청대상 ID',
  offering_id BIGINT UNSIGNED NOT NULL COMMENT '비정규 과정 설정 ID',
  audience_code VARCHAR(2) NOT NULL COMMENT '신청대상 코드: 1 교수|2 교직원|3 학생|4 졸업생|5 조교|6 대학원생',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (non_regular_target_id),
  UNIQUE KEY uq_non_regular_targets_offering_audience (offering_id, audience_code),
  CONSTRAINT fk_non_regular_targets_non_regular_offerings FOREIGN KEY (offering_id) REFERENCES non_regular_offerings (offering_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='비정규 신청대상';

CREATE TABLE course_seat_counters (
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  seat_capacity INT UNSIGNED NULL COMMENT '정원(NULL=제한없음)',
  held_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '점유 좌석 수',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_id),
  CONSTRAINT chk_course_seat_counters_held CHECK (seat_capacity IS NULL OR held_count <= seat_capacity),
  CONSTRAINT fk_course_seat_counters_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='정원·좌석 카운터';

CREATE TABLE academic_calendar_events (
  academic_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학사일정 ID',
  term_id BIGINT UNSIGNED NULL COMMENT '학기 ID',
  event_title VARCHAR(300) NOT NULL COMMENT '일정명(예: 2026학년도 2학기 수강철회기간)',
  event_type VARCHAR(20) NOT NULL DEFAULT 'ACADEMIC' COMMENT '일정 구분: ACADEMIC|REGISTRATION|EXAM|HOLIDAY',
  start_date DATE NOT NULL COMMENT '시작일',
  end_date DATE NOT NULL COMMENT '종료일(당일 일정은 시작일과 동일)',
  is_public TINYINT(1) NOT NULL DEFAULT 1 COMMENT '캘린더 공개 여부',
  legacy_schedule_key VARCHAR(40) NULL COMMENT 'ILOS 일정 키(추적용)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (academic_event_id),
  KEY idx_academic_calendar_events_period (start_date, end_date),
  KEY idx_academic_calendar_events_term (term_id),
  CONSTRAINT fk_academic_calendar_events_terms FOREIGN KEY (term_id) REFERENCES terms (term_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학사일정';

CREATE TABLE notifications (
  notification_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '알림 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '수신 사용자 ID',
  notification_type VARCHAR(24) NOT NULL COMMENT '알림 유형: COURSE_NOTICE|COMMUNITY_NOTICE|ONLINE_LECTURE|LECTURE_MATERIAL|SURVEY|MESSAGE|ABSENCE',
  course_id BIGINT UNSIGNED NULL COMMENT '관련 과목 ID(전체 공지·쪽지 알림은 NULL)',
  activity_id BIGINT UNSIGNED NULL COMMENT '관련 학습활동 ID(learning_activities, 통합 시 ALTER로 FK 추가)',
  target_kind VARCHAR(20) NULL COMMENT '연결 대상 종류: ACTIVITY|ARTICLE|MESSAGE|ATTENDANCE',
  target_ref_id BIGINT UNSIGNED NULL COMMENT '연결 대상 ID(타 도메인 행 식별자, FK 없음)',
  message_text VARCHAR(500) NOT NULL COMMENT '알림 문장(서버 생성)',
  is_canceled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '취소된 알림 여부',
  read_at DATETIME(3) NULL COMMENT '읽은 일시(미읽음이면 NULL)',
  expires_at DATETIME(3) NULL COMMENT '보존 만료 일시(60일 경과 삭제 기준)',
  legacy_notifier_seq VARCHAR(40) NULL COMMENT 'ILOS 알림 번호(NOTIFIER_SEQ, 추적용)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (notification_id),
  KEY idx_notifications_user_unread (user_id, read_at, created_at),
  KEY idx_notifications_user_created (user_id, created_at),
  KEY idx_notifications_course (course_id),
  KEY idx_notifications_activity (activity_id),
  KEY idx_notifications_expires (expires_at),
  CONSTRAINT fk_notifications_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_notifications_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='알림';


-- ===== 30_learning =====
-- 1. 과목 주차(회차)
CREATE TABLE course_weeks (
  course_week_id  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '주차 ID',
  course_id       BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  week_no         INT UNSIGNED    NOT NULL COMMENT '주차/회차 번호(1부터)',
  week_unit       VARCHAR(10)     NOT NULL DEFAULT '주' COMMENT '표기 단위(주|회차)',
  title           VARCHAR(200)    NULL COMMENT '주차 요약 제목',
  starts_on       DATE            NULL COMMENT '주차 시작일',
  ends_on         DATE            NULL COMMENT '주차 종료일',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at      DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_week_id),
  UNIQUE KEY uq_course_weeks_course_id_week_no (course_id, week_no),
  CONSTRAINT fk_course_weeks_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목 주차';

-- 2. 과목 그룹(그룹활동 팀 / 출석 분반)
CREATE TABLE course_groups (
  course_group_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '과목 그룹 ID',
  course_id       BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  group_type      VARCHAR(20)     NOT NULL DEFAULT '활동' COMMENT '그룹 종류(활동|출석분반)',
  group_name      VARCHAR(100)    NOT NULL COMMENT '그룹명',
  description     TEXT            NULL COMMENT '그룹 설명',
  sort_order      INT             NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_by      BIGINT UNSIGNED NULL COMMENT '개설자 사용자 ID',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at      DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_group_id),
  KEY idx_course_groups_course_id_group_type (course_id, group_type),
  CONSTRAINT fk_course_groups_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_course_groups_users FOREIGN KEY (created_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목 그룹';

-- 3. 과목 그룹 구성원
CREATE TABLE course_group_members (
  course_group_member_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '그룹 구성원 ID',
  course_group_id        BIGINT UNSIGNED NOT NULL COMMENT '과목 그룹 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '구성원 사용자 ID',
  member_role            VARCHAR(20)     NOT NULL DEFAULT '구성원' COMMENT '그룹 역할(리더|구성원)',
  joined_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '참여 일시',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (course_group_member_id),
  UNIQUE KEY uq_course_group_members_course_group_id_user_id (course_group_id, user_id),
  KEY idx_course_group_members_user_id (user_id),
  CONSTRAINT fk_course_group_members_course_groups FOREIGN KEY (course_group_id) REFERENCES course_groups (course_group_id),
  CONSTRAINT fk_course_group_members_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목 그룹 구성원';

-- 4. 출석 차시
CREATE TABLE attendance_sessions (
  attendance_session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '출석 차시 ID',
  course_id             BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  course_week_id        BIGINT UNSIGNED NOT NULL COMMENT '주차 ID',
  course_group_id       BIGINT UNSIGNED NULL COMMENT '출석 대상 분반 그룹 ID(GROUP_ATTEND_DV)',
  session_no            INT UNSIGNED    NOT NULL COMMENT '차시 번호',
  attendance_method     VARCHAR(20)     NOT NULL DEFAULT '일반' COMMENT '출석 방식(스마트|온라인|일반)',
  session_state         VARCHAR(20)     NOT NULL DEFAULT 'planned' COMMENT '차시 상태(planned|open|closed|cancelled)',
  class_date            DATE            NULL COMMENT '수업일',
  class_start_time      TIME            NULL COMMENT '수업 시작 시각',
  class_end_time        TIME            NULL COMMENT '수업 종료 시각',
  makeup_of_session_id  BIGINT UNSIGNED NULL COMMENT '보강 대상 원 차시 ID(휴강 차시)',
  makeup_yn             TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '보강 차시 여부',
  created_by            BIGINT UNSIGNED NULL COMMENT '등록자 사용자 ID',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at            DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (attendance_session_id),
  KEY idx_attendance_sessions_course_week_id_session_no (course_week_id, session_no),
  KEY idx_attendance_sessions_class_date (course_id, class_date),
  CONSTRAINT fk_attendance_sessions_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_attendance_sessions_course_weeks FOREIGN KEY (course_week_id) REFERENCES course_weeks (course_week_id),
  CONSTRAINT fk_attendance_sessions_course_groups FOREIGN KEY (course_group_id) REFERENCES course_groups (course_group_id),
  CONSTRAINT fk_attendance_sessions_users FOREIGN KEY (created_by) REFERENCES users (user_id),
  CONSTRAINT fk_attendance_sessions_attendance_sessions FOREIGN KEY (makeup_of_session_id) REFERENCES attendance_sessions (attendance_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='출석 차시';

-- 5. 학습활동 슈퍼타입
CREATE TABLE learning_activities (
  activity_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습활동 ID',
  course_id             BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  course_week_id        BIGINT UNSIGNED NOT NULL COMMENT '주차 ID',
  activity_type         VARCHAR(30)     NOT NULL COMMENT '활동 유형(lecture_weeks|lecture_material|report|test|survey|discuss|zoom|clicker)',
  title                 VARCHAR(300)    NOT NULL COMMENT '활동 제목',
  description           TEXT            NULL COMMENT '활동 설명',
  open_at               DATETIME        NULL COMMENT '학습/응시 시작 일시',
  close_at              DATETIME        NULL COMMENT '마감 일시',
  attendance_session_id BIGINT UNSIGNED NULL COMMENT '출석부 반영 차시 ID(온라인 학습 연동)',
  offline_yn            TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '오프라인 진행 여부',
  published_yn          TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '공개 여부',
  attachment_group_id   BIGINT UNSIGNED NULL COMMENT '첨부 그룹 ID(files.attachment_groups)',
  sort_order            INT             NOT NULL DEFAULT 0 COMMENT '주차 내 정렬 순서',
  created_by            BIGINT UNSIGNED NULL COMMENT '작성자(교수/조교) 사용자 ID',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at            DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (activity_id),
  KEY idx_learning_activities_course_id_course_week_id (course_id, course_week_id),
  KEY idx_learning_activities_course_id_activity_type (course_id, activity_type),
  KEY idx_learning_activities_close_at (close_at),
  CONSTRAINT fk_learning_activities_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_learning_activities_course_weeks FOREIGN KEY (course_week_id) REFERENCES course_weeks (course_week_id),
  CONSTRAINT fk_learning_activities_attendance_sessions FOREIGN KEY (attendance_session_id) REFERENCES attendance_sessions (attendance_session_id),
  CONSTRAINT fk_learning_activities_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id),
  CONSTRAINT fk_learning_activities_users FOREIGN KEY (created_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습활동';

-- 6. 자료 상세(lecture_material 서브타입)
CREATE TABLE learning_material_details (
  activity_id   BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID(자료)',
  body_html     MEDIUMTEXT      NULL COMMENT '자료 본문(에디터 HTML)',
  published_at  DATETIME        NULL COMMENT '공개 일시',
  view_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '조회수',
  created_at    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at    DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (activity_id),
  CONSTRAINT fk_learning_material_details_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='자료 상세';

-- 7. 온라인강의 콘텐츠(lecture_weeks 서브타입 항목)
CREATE TABLE online_lecture_items (
  online_lecture_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '온라인강의 콘텐츠 ID',
  activity_id            BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID(영상)',
  link_seq               INT UNSIGNED    NOT NULL COMMENT '활동 내 콘텐츠 순번(link_seq)',
  legacy_item_code       VARCHAR(32)     NULL COMMENT 'ILOS 콘텐츠 ID(item_id)',
  title                  VARCHAR(300)    NOT NULL COMMENT '콘텐츠 제목',
  content_kind           VARCHAR(20)     NULL COMMENT '콘텐츠 종류(video|link 등 kind)',
  content_path           VARCHAR(500)    NULL COMMENT '콘텐츠 저장 경로',
  content_cid            VARCHAR(50)     NULL COMMENT '콘텐츠 컨테이너 ID(cid)',
  content_vr             VARCHAR(50)     NULL COMMENT 'VR/화질 구분(vr)',
  duration_sec           INT UNSIGNED    NULL COMMENT '전체 재생 길이(초)',
  required_sec           INT UNSIGNED    NULL COMMENT '출석인정 필요 시간(초)',
  thumbnail_path         VARCHAR(500)    NULL COMMENT '썸네일 경로',
  note                   TEXT            NULL COMMENT '강의내용(콘텐츠 설명)',
  allow_replacement_yn   TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '대체영상 허용 여부',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at             DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (online_lecture_item_id),
  UNIQUE KEY uq_online_lecture_items_activity_id_link_seq (activity_id, link_seq),
  CONSTRAINT fk_online_lecture_items_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='온라인강의 콘텐츠';

-- 8. 대체영상 선택
CREATE TABLE online_lecture_item_replacements (
  item_replacement_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '대체영상 선택 ID',
  user_id                        BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  online_lecture_item_id         BIGINT UNSIGNED NOT NULL COMMENT '원 콘텐츠 ID',
  replace_online_lecture_item_id BIGINT UNSIGNED NOT NULL COMMENT '선택한 대체 콘텐츠 ID',
  created_at                     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at                     DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (item_replacement_id),
  UNIQUE KEY uq_online_lecture_item_replacements_user_id_item_id (user_id, online_lecture_item_id),
  KEY idx_online_lecture_item_replacements_replace_item (replace_online_lecture_item_id),
  CONSTRAINT fk_online_lecture_item_replacements_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_online_lecture_item_replacements_online_lecture_items FOREIGN KEY (online_lecture_item_id) REFERENCES online_lecture_items (online_lecture_item_id),
  CONSTRAINT fk_online_lecture_item_replacements_replace_item FOREIGN KEY (replace_online_lecture_item_id) REFERENCES online_lecture_items (online_lecture_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='대체영상 선택';

-- 9. 콘텐츠 타임스탬프
CREATE TABLE online_lecture_timestamps (
  timestamp_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '타임스탬프 ID',
  online_lecture_item_id BIGINT UNSIGNED NOT NULL COMMENT '온라인강의 콘텐츠 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '작성자 사용자 ID',
  position_sec           INT UNSIGNED    NOT NULL COMMENT '재생 위치(초)',
  note                   VARCHAR(500)    NULL COMMENT '메모',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at             DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (timestamp_id),
  KEY idx_online_lecture_timestamps_item_id_user_id (online_lecture_item_id, user_id),
  CONSTRAINT fk_online_lecture_timestamps_online_lecture_items FOREIGN KEY (online_lecture_item_id) REFERENCES online_lecture_items (online_lecture_item_id),
  CONSTRAINT fk_online_lecture_timestamps_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='콘텐츠 타임스탬프';

-- 10. 활동 학습 진도
CREATE TABLE activity_progress (
  activity_progress_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '활동 진도 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  activity_id          BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID',
  first_viewed_at      DATETIME(3)     NULL COMMENT '최초 열람 일시',
  last_viewed_at       DATETIME(3)     NULL COMMENT '최근 열람 일시',
  view_count           INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '열람 횟수',
  completed_at         DATETIME(3)     NULL COMMENT '완료 처리 일시',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at           DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (activity_progress_id),
  UNIQUE KEY uq_activity_progress_user_id_activity_id (user_id, activity_id),
  KEY idx_activity_progress_activity_id (activity_id),
  CONSTRAINT fk_activity_progress_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_activity_progress_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='활동 학습 진도';

-- 11. 활동 열람 이력
CREATE TABLE activity_view_logs (
  activity_view_log_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '활동 열람 이력 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  activity_id          BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID',
  viewed_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '열람 일시',
  source               VARCHAR(20)     NULL COMMENT '열람 경로(목록|이어보기|직접링크)',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (activity_view_log_id),
  KEY idx_activity_view_logs_user_id_viewed_at (user_id, viewed_at),
  KEY idx_activity_view_logs_activity_id (activity_id),
  CONSTRAINT fk_activity_view_logs_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_activity_view_logs_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='활동 열람 이력';

-- 12. 이어보기 포인터
CREATE TABLE course_continue_pointers (
  continue_pointer_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '이어보기 포인터 ID',
  user_id             BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  course_id           BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  activity_id         BIGINT UNSIGNED NULL COMMENT '마지막 열람 활동 ID',
  menu_code           VARCHAR(30)     NULL COMMENT '마지막 열람 메뉴 ID(lecture_weeks 등)',
  client_seq          BIGINT UNSIGNED NULL COMMENT '클라이언트 단조 순번(순서 역전 방지)',
  recorded_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '기록 일시',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at          DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (continue_pointer_id),
  UNIQUE KEY uq_course_continue_pointers_user_id_course_id (user_id, course_id),
  KEY idx_course_continue_pointers_activity_id (activity_id),
  CONSTRAINT fk_course_continue_pointers_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_course_continue_pointers_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_course_continue_pointers_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='이어보기 포인터';

-- 13. 주차 접힘 설정
CREATE TABLE course_week_folds (
  course_week_fold_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '주차 접힘 설정 ID',
  user_id             BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  course_id           BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  course_week_id      BIGINT UNSIGNED NOT NULL COMMENT '주차 ID',
  closed_yn           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '접힘 여부(CLOSE_YN)',
  client_seq          BIGINT UNSIGNED NULL COMMENT '클라이언트 단조 순번(연속 토글 순서)',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at          DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_week_fold_id),
  UNIQUE KEY uq_course_week_folds_user_id_course_week_id (user_id, course_week_id),
  KEY idx_course_week_folds_course_id (course_id),
  CONSTRAINT fk_course_week_folds_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_course_week_folds_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_course_week_folds_course_weeks FOREIGN KEY (course_week_id) REFERENCES course_weeks (course_week_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='주차 접힘 설정';

-- 14. 온라인학습 세션(이력번호 단위)
CREATE TABLE learning_sessions (
  learning_session_id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학습 세션 ID',
  legacy_his_no          VARCHAR(30)     NULL COMMENT 'ILOS 이력번호(his_no)',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  course_id              BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  activity_id            BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID(영상)',
  online_lecture_item_id BIGINT UNSIGNED NOT NULL COMMENT '온라인강의 콘텐츠 ID',
  state                  VARCHAR(20)     NOT NULL DEFAULT 'active' COMMENT '세션 상태(active|ended|superseded|lease_expired)',
  started_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '시작 일시',
  last_heartbeat_at      DATETIME(3)     NULL COMMENT '마지막 수락 하트비트 일시',
  ended_at               DATETIME(3)     NULL COMMENT '종료 일시',
  end_reason             VARCHAR(20)     NULL COMMENT '종료 사유(button|forced|unload|lease_expired|superseded)',
  heartbeat_interval_sec INT UNSIGNED    NOT NULL DEFAULT 240 COMMENT '하트비트 주기(초)',
  lease_sec              INT UNSIGNED    NULL COMMENT '리스 유효 시간(초)',
  active_user_key        BIGINT UNSIGNED GENERATED ALWAYS AS (IF(state = 'active', user_id, NULL)) STORED COMMENT '활성 세션 사용자(부분 유일 제약용)',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at             DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (learning_session_id),
  UNIQUE KEY uq_learning_sessions_active_user_key (active_user_key),
  KEY idx_learning_sessions_user_id_state (user_id, state),
  KEY idx_learning_sessions_legacy_his_no (legacy_his_no),
  KEY idx_learning_sessions_online_lecture_item_id (online_lecture_item_id),
  CONSTRAINT fk_learning_sessions_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_learning_sessions_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_learning_sessions_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id),
  CONSTRAINT fk_learning_sessions_online_lecture_items FOREIGN KEY (online_lecture_item_id) REFERENCES online_lecture_items (online_lecture_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='온라인학습 세션';

-- 15. 학습 하트비트
CREATE TABLE learning_heartbeats (
  heartbeat_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '하트비트 ID',
  learning_session_id BIGINT UNSIGNED NOT NULL COMMENT '학습 세션 ID',
  seq_no              BIGINT UNSIGNED NOT NULL COMMENT '하트비트 순번(중복 제거)',
  received_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '서버 수신 일시',
  client_interval_sec INT UNSIGNED    NULL COMMENT '클라이언트 보고 주기(INTERVAL_TIME)',
  position_sec        INT UNSIGNED    NULL COMMENT '클라이언트 보고 재생 위치(초)',
  playing_yn          TINYINT(1)      NULL COMMENT '재생 중 여부(클라이언트 신호)',
  credited_sec        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '이 하트비트로 적립된 초',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (heartbeat_id),
  UNIQUE KEY uq_learning_heartbeats_learning_session_id_seq_no (learning_session_id, seq_no),
  CONSTRAINT fk_learning_heartbeats_learning_sessions FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (learning_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습 하트비트';

-- 16. 학습시간 적립 원장
CREATE TABLE learning_credit_ledger (
  ledger_id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '적립 원장 ID',
  learning_session_id BIGINT UNSIGNED NOT NULL COMMENT '학습 세션 ID',
  from_at             DATETIME(3)     NOT NULL COMMENT '적립 구간 시작',
  to_at               DATETIME(3)     NOT NULL COMMENT '적립 구간 종료',
  credited_sec        INT UNSIGNED    NOT NULL COMMENT '적립 초',
  in_period_yn        TINYINT(1)      NOT NULL COMMENT '출석인정기간 내 여부',
  rule_version        VARCHAR(20)     NOT NULL DEFAULT 'v1' COMMENT '적립 규칙 버전',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (ledger_id),
  KEY idx_learning_credit_ledger_learning_session_id_from_at (learning_session_id, from_at),
  CONSTRAINT fk_learning_credit_ledger_learning_sessions FOREIGN KEY (learning_session_id) REFERENCES learning_sessions (learning_session_id),
  CONSTRAINT chk_learning_credit_ledger_period CHECK (to_at > from_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학습시간 적립 원장';

-- 17. 콘텐츠 학습 진도
CREATE TABLE online_lecture_item_progress (
  item_progress_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '콘텐츠 진도 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  online_lecture_item_id BIGINT UNSIGNED NOT NULL COMMENT '온라인강의 콘텐츠 ID',
  credited_in_sec        INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '기간내 적립 초',
  credited_out_sec       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '기간외 적립 초',
  max_position_sec       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '최대 재생 위치(초)',
  last_position_sec      INT UNSIGNED    NULL COMMENT '마지막 재생 위치(이어보기 힌트)',
  last_position_at       DATETIME(3)     NULL COMMENT '마지막 위치 저장 일시',
  decided_status         VARCHAR(20)     NULL COMMENT '판정 상태(met|under_required|unknown)',
  rule_version           VARCHAR(20)     NULL COMMENT '판정 규칙 버전',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at             DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (item_progress_id),
  UNIQUE KEY uq_online_lecture_item_progress_user_id_item_id (user_id, online_lecture_item_id),
  CONSTRAINT fk_online_lecture_item_progress_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_online_lecture_item_progress_online_lecture_items FOREIGN KEY (online_lecture_item_id) REFERENCES online_lecture_items (online_lecture_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='콘텐츠 학습 진도';

-- 18. 출석 기록
CREATE TABLE attendance_records (
  attendance_record_id  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '출석 기록 ID',
  attendance_session_id BIGINT UNSIGNED NOT NULL COMMENT '출석 차시 ID',
  user_id               BIGINT UNSIGNED NOT NULL COMMENT '수강생 사용자 ID',
  status                VARCHAR(20)     NOT NULL DEFAULT '미처리' COMMENT '출석 상태(출석|지각|결석|공결|미처리)',
  source                VARCHAR(20)     NULL COMMENT '산출 방식(smart|online|manual)',
  progress_percent      DECIMAL(5,2)    NULL COMMENT '온라인 학습 진도율(%)',
  decided_at            DATETIME(3)     NULL COMMENT '판정 일시',
  decided_by            BIGINT UNSIGNED NULL COMMENT '판정자 사용자 ID(수기 처리)',
  rule_version          VARCHAR(20)     NULL COMMENT '판정 규칙 버전',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at            DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (attendance_record_id),
  UNIQUE KEY uq_attendance_records_attendance_session_id_user_id (attendance_session_id, user_id),
  KEY idx_attendance_records_user_id (user_id),
  CONSTRAINT fk_attendance_records_attendance_sessions FOREIGN KEY (attendance_session_id) REFERENCES attendance_sessions (attendance_session_id),
  CONSTRAINT fk_attendance_records_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_attendance_records_users_decided_by FOREIGN KEY (decided_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='출석 기록';

-- 19. 스마트출석 회차
CREATE TABLE smart_attendance_rounds (
  smart_attendance_round_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '스마트출석 회차 ID',
  legacy_attend_no          VARCHAR(30)     NULL COMMENT 'ILOS 출석 회차 번호(ATTEND_NO)',
  course_id                 BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  attendance_session_id     BIGINT UNSIGNED NULL COMMENT '대상 출석 차시 ID',
  course_group_id           BIGINT UNSIGNED NULL COMMENT '대상 분반 그룹 ID(GROUP_ATTEND_DV)',
  method                    VARCHAR(10)     NOT NULL COMMENT '인증 방식(pin|qr)',
  pin_length                TINYINT UNSIGNED NULL COMMENT 'PIN 자릿수',
  state                     VARCHAR(20)     NOT NULL DEFAULT 'open' COMMENT '회차 상태(open|closed|cancelled)',
  opened_at                 DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '개시 일시',
  ends_at                   DATETIME(3)     NOT NULL COMMENT '마감 일시',
  opened_by                 BIGINT UNSIGNED NULL COMMENT '개시 교수자 사용자 ID',
  created_at                DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at                DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (smart_attendance_round_id),
  KEY idx_smart_attendance_rounds_course_id_state (course_id, state),
  KEY idx_smart_attendance_rounds_attendance_session_id (attendance_session_id),
  CONSTRAINT fk_smart_attendance_rounds_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_smart_attendance_rounds_attendance_sessions FOREIGN KEY (attendance_session_id) REFERENCES attendance_sessions (attendance_session_id),
  CONSTRAINT fk_smart_attendance_rounds_course_groups FOREIGN KEY (course_group_id) REFERENCES course_groups (course_group_id),
  CONSTRAINT fk_smart_attendance_rounds_users FOREIGN KEY (opened_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='스마트출석 회차';

-- 20. 스마트출석 시도
CREATE TABLE smart_attendance_attempts (
  attempt_id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '스마트출석 시도 ID',
  smart_attendance_round_id BIGINT UNSIGNED NOT NULL COMMENT '스마트출석 회차 ID',
  user_id                   BIGINT UNSIGNED NOT NULL COMMENT '제출 사용자 ID',
  attempted_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '시도 일시',
  result                    VARCHAR(20)     NOT NULL COMMENT '결과(ok|bad_pin|bad_qr|expired|not_target|duplicate)',
  used_jti                  VARCHAR(64)     NULL COMMENT '소비한 단기 증명 식별자(jti)',
  created_at                DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uq_smart_attendance_attempts_used_jti (used_jti),
  KEY idx_smart_attendance_attempts_round_id_user_id (smart_attendance_round_id, user_id),
  CONSTRAINT fk_smart_attendance_attempts_smart_attendance_rounds FOREIGN KEY (smart_attendance_round_id) REFERENCES smart_attendance_rounds (smart_attendance_round_id),
  CONSTRAINT fk_smart_attendance_attempts_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='스마트출석 시도';

-- 21. 강의계획서
CREATE TABLE course_syllabi (
  syllabus_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '강의계획서 ID',
  course_id            BIGINT UNSIGNED NOT NULL COMMENT '과목 ID(catalog.courses)',
  term_label           VARCHAR(50)     NULL COMMENT '년도/학기 표기(2026/2학기)',
  course_no            VARCHAR(30)     NULL COMMENT '교과목번호/강좌번호 표기',
  completion_type      VARCHAR(20)     NULL COMMENT '이수구분(전선 등)',
  credit               DECIMAL(3,1)    NULL COMMENT '학점',
  class_time_room      VARCHAR(200)    NULL COMMENT '강의시간/강의실',
  overview             TEXT            NULL COMMENT '과목개요',
  objectives           TEXT            NULL COMMENT '교육목표',
  outcome_relevance    VARCHAR(10)     NULL COMMENT '학습성과관련도(L1|L2|L3)',
  textbooks            TEXT            NULL COMMENT '교재 및 참고서적',
  equipment            TEXT            NULL COMMENT '활용 기자재',
  grading_policy       TEXT            NULL COMMENT '성적평가방법',
  attachment_group_id  BIGINT UNSIGNED NULL COMMENT '계획서 첨부 그룹 ID(files.attachment_groups)',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at           DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (syllabus_id),
  UNIQUE KEY uq_course_syllabi_course_id (course_id),
  CONSTRAINT fk_course_syllabi_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_course_syllabi_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='강의계획서';

-- 22. 계획서 강사
CREATE TABLE syllabus_instructors (
  syllabus_instructor_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '계획서 강사 ID',
  syllabus_id            BIGINT UNSIGNED NOT NULL COMMENT '강의계획서 ID',
  user_id                BIGINT UNSIGNED NOT NULL COMMENT '교수 사용자 ID',
  department             VARCHAR(100)    NULL COMMENT '소속',
  email                  VARCHAR(200)    NULL COMMENT '이메일',
  office_hours           VARCHAR(200)    NULL COMMENT '면담가능시간',
  phone                  VARCHAR(50)     NULL COMMENT '연락처',
  office                 VARCHAR(200)    NULL COMMENT '연구실',
  homepage               VARCHAR(300)    NULL COMMENT '홈페이지',
  sort_order             INT             NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_at             DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at             DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (syllabus_instructor_id),
  UNIQUE KEY uq_syllabus_instructors_syllabus_id_user_id (syllabus_id, user_id),
  CONSTRAINT fk_syllabus_instructors_course_syllabi FOREIGN KEY (syllabus_id) REFERENCES course_syllabi (syllabus_id),
  CONSTRAINT fk_syllabus_instructors_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='계획서 강사';

-- 23. 계획서 주별계획
CREATE TABLE course_syllabus_weeks (
  syllabus_week_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '계획서 주별계획 ID',
  syllabus_id      BIGINT UNSIGNED NOT NULL COMMENT '강의계획서 ID',
  week_no          INT UNSIGNED    NOT NULL COMMENT '주 번호(1~16)',
  content          TEXT            NULL COMMENT '주별 강의 내용',
  note             VARCHAR(500)    NULL COMMENT '비고',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at       DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (syllabus_week_id),
  UNIQUE KEY uq_course_syllabus_weeks_syllabus_id_week_no (syllabus_id, week_no),
  CONSTRAINT fk_course_syllabus_weeks_course_syllabi FOREIGN KEY (syllabus_id) REFERENCES course_syllabi (syllabus_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='계획서 주별계획';


-- ===== 40_assessment =====
-- =====================================================================
-- assessment 도메인 (MySQL 8 / InnoDB / utf8mb4)
-- =====================================================================

CREATE TABLE grade_settings (
  grade_setting_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '성적 설정 ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  activities_visible_yn TINYINT(1) NOT NULL DEFAULT 1 COMMENT '평가 활동 섹션 공개 여부',
  grade_release_at DATETIME(3) NULL COMMENT '과목 성적 공개 일시(NULL=비공개)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (grade_setting_id),
  UNIQUE KEY uq_grade_settings_course (course_id),
  CONSTRAINT fk_grade_settings_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='성적 설정';

CREATE TABLE grade_items (
  grade_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '성적 항목 ID',
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  activity_id BIGINT UNSIGNED NULL COMMENT '학습활동 ID(NULL=활동 없는 직접 입력 항목)',
  item_name VARCHAR(200) NOT NULL COMMENT '성적 항목명',
  item_type VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '항목 유형(assignment:과제, quiz:퀴즈, manual:직접입력, etc:기타)',
  max_score DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '배점',
  weight DECIMAL(6,2) NULL COMMENT '성적 반영 비율(%) [추정]',
  score_visibility VARCHAR(20) NOT NULL DEFAULT 'hidden' COMMENT '점수 공개 정책(hidden:비공개, after_release:공개일 이후, immediate:즉시)',
  release_at DATETIME(3) NULL COMMENT '점수·평가 공개 일시(NULL=미정)',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_by_user_id BIGINT UNSIGNED NULL COMMENT '등록 교수자 ID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (grade_item_id),
  UNIQUE KEY uq_grade_items_activity (activity_id),
  KEY idx_grade_items_course (course_id, sort_order),
  KEY idx_grade_items_creator (created_by_user_id),
  KEY idx_grade_items_release (release_at),
  CONSTRAINT fk_grade_items_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_grade_items_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id),
  CONSTRAINT fk_grade_items_users FOREIGN KEY (created_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='성적 항목';

CREATE TABLE grade_criteria (
  grade_criterion_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '평가요소 ID',
  grade_item_id BIGINT UNSIGNED NOT NULL COMMENT '성적 항목 ID',
  criterion_name VARCHAR(200) NOT NULL COMMENT '평가요소명',
  max_score DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '평가요소 배점',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (grade_criterion_id),
  KEY idx_grade_criteria_item (grade_item_id, sort_order),
  CONSTRAINT fk_grade_criteria_grade_items FOREIGN KEY (grade_item_id) REFERENCES grade_items (grade_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='평가요소';

CREATE TABLE grade_scores (
  grade_score_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학생 성적 ID',
  grade_item_id BIGINT UNSIGNED NOT NULL COMMENT '성적 항목 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '학생 ID',
  score DECIMAL(6,2) NULL COMMENT '획득 점수(NULL=미채점)',
  is_late TINYINT(1) NOT NULL DEFAULT 0 COMMENT '지각 제출·응시 여부',
  score_comment VARCHAR(1000) NULL COMMENT '채점 의견',
  graded_at DATETIME(3) NULL COMMENT '채점 일시',
  graded_by_user_id BIGINT UNSIGNED NULL COMMENT '채점자 ID',
  released_at DATETIME(3) NULL COMMENT '학생 공개 일시(NULL=미공개)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (grade_score_id),
  UNIQUE KEY uq_grade_scores_item_user (grade_item_id, user_id),
  KEY idx_grade_scores_user (user_id),
  KEY idx_grade_scores_grader (graded_by_user_id),
  CONSTRAINT fk_grade_scores_grade_items FOREIGN KEY (grade_item_id) REFERENCES grade_items (grade_item_id),
  CONSTRAINT fk_grade_scores_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_grade_scores_graded_by FOREIGN KEY (graded_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학생 성적';

CREATE TABLE grade_criterion_scores (
  grade_criterion_score_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '평가요소 점수 ID',
  grade_criterion_id BIGINT UNSIGNED NOT NULL COMMENT '평가요소 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '학생 ID',
  score DECIMAL(6,2) NULL COMMENT '평가요소 획득 점수',
  score_comment VARCHAR(1000) NULL COMMENT '평가요소 의견',
  graded_at DATETIME(3) NULL COMMENT '채점 일시',
  graded_by_user_id BIGINT UNSIGNED NULL COMMENT '채점자 ID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (grade_criterion_score_id),
  UNIQUE KEY uq_grade_criterion_scores_criterion_user (grade_criterion_id, user_id),
  KEY idx_grade_criterion_scores_user (user_id),
  KEY idx_grade_criterion_scores_grader (graded_by_user_id),
  CONSTRAINT fk_grade_criterion_scores_grade_criteria FOREIGN KEY (grade_criterion_id) REFERENCES grade_criteria (grade_criterion_id),
  CONSTRAINT fk_grade_criterion_scores_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_grade_criterion_scores_graded_by FOREIGN KEY (graded_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='평가요소 점수';

CREATE TABLE assignments (
  assignment_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '과제 ID',
  activity_id BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID',
  submit_method VARCHAR(20) NOT NULL DEFAULT 'online' COMMENT '제출방식(online:온라인, offline:오프라인)',
  allow_text_yn TINYINT(1) NOT NULL DEFAULT 1 COMMENT '글 입력 제출 허용 여부(제출형태)',
  allow_file_yn TINYINT(1) NOT NULL DEFAULT 1 COMMENT '파일 첨부 제출 허용 여부(제출형태)',
  team_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '팀 과제 여부(report2)',
  late_policy VARCHAR(20) NOT NULL DEFAULT 'none' COMMENT '지각 정책(none:불허, allow_until:기한까지 허용, penalty:감점 허용)',
  late_until DATETIME(3) NULL COMMENT '지각 제출 허용 종료 일시',
  late_penalty_rate DECIMAL(5,2) NULL COMMENT '지각 감점 비율(%)',
  turnitin_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '표절검사 연동 여부',
  turnitin_key VARCHAR(64) NULL COMMENT '표절검사 연동 키(turnitin_seq)',
  format_template_code VARCHAR(64) NULL COMMENT '양식 제출 양식 코드(FORMATBANK_SEQ) [추정]',
  legacy_rt_seq VARCHAR(32) NULL COMMENT 'ILOS 과제 번호(RT_SEQ)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (assignment_id),
  UNIQUE KEY uq_assignments_activity (activity_id),
  UNIQUE KEY uq_assignments_legacy_rt_seq (legacy_rt_seq),
  CONSTRAINT fk_assignments_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과제';

CREATE TABLE assignment_submissions (
  assignment_submission_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '과제 제출물 ID',
  assignment_id BIGINT UNSIGNED NOT NULL COMMENT '과제 ID',
  owner_type VARCHAR(10) NOT NULL DEFAULT 'user' COMMENT '제출 주체(user:개인, group:팀)',
  user_id BIGINT UNSIGNED NULL COMMENT '개인 제출자 ID(owner_type=user)',
  group_id BIGINT UNSIGNED NULL COMMENT '팀 제출 그룹 ID(owner_type=group)',
  current_version_no INT NOT NULL DEFAULT 0 COMMENT '현재 제출 버전 번호',
  first_submitted_at DATETIME(3) NULL COMMENT '최초 제출 일시',
  submitted_at DATETIME(3) NULL COMMENT '현재 버전 제출 일시',
  is_late TINYINT(1) NOT NULL DEFAULT 0 COMMENT '지각 제출 여부',
  deleted_at DATETIME(3) NULL COMMENT '삭제 일시(제출 삭제, 이력 보존)',
  deleted_by_user_id BIGINT UNSIGNED NULL COMMENT '삭제 요청자 ID',
  team_owner_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(owner_type = 'group', group_id, NULL)) STORED COMMENT '팀 제출 고유성 보조(팀당 1건)',
  legacy_content_seq VARCHAR(32) NULL COMMENT 'ILOS 제출물 번호(CONTENT_SEQ)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (assignment_submission_id),
  UNIQUE KEY uq_assignment_submissions_assignment_user (assignment_id, user_id),
  UNIQUE KEY uq_assignment_submissions_assignment_team (assignment_id, team_owner_id),
  KEY idx_assignment_submissions_assignment_group (assignment_id, group_id),
  UNIQUE KEY uq_assignment_submissions_legacy_content_seq (legacy_content_seq),
  KEY idx_assignment_submissions_user (user_id),
  KEY idx_assignment_submissions_deleter (deleted_by_user_id),
  CONSTRAINT fk_assignment_submissions_assignments FOREIGN KEY (assignment_id) REFERENCES assignments (assignment_id),
  CONSTRAINT fk_assignment_submissions_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_assignment_submissions_course_groups FOREIGN KEY (group_id) REFERENCES course_groups (course_group_id),
  CONSTRAINT fk_assignment_submissions_deleted_by FOREIGN KEY (deleted_by_user_id) REFERENCES users (user_id),
  CONSTRAINT chk_assignment_submissions_owner CHECK (
    (owner_type = 'user' AND user_id IS NOT NULL AND group_id IS NULL) OR
    (owner_type = 'group' AND group_id IS NOT NULL AND user_id IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과제 제출물';

CREATE TABLE assignment_submission_versions (
  assignment_submission_version_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '과제 제출 버전 ID',
  assignment_submission_id BIGINT UNSIGNED NOT NULL COMMENT '제출물 ID',
  version_no INT NOT NULL COMMENT '제출 버전 번호',
  body_html MEDIUMTEXT NULL COMMENT '제출 본문(정제된 HTML)',
  attachment_group_id BIGINT UNSIGNED NULL COMMENT '제출 첨부 묶음 ID',
  submitted_at DATETIME(3) NOT NULL COMMENT '서버 제출 일시',
  submitted_by_user_id BIGINT UNSIGNED NULL COMMENT '실제 저장 요청자 ID(팀 과제 대표 제출)',
  is_late TINYINT(1) NOT NULL DEFAULT 0 COMMENT '지각 제출 여부',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (assignment_submission_version_id),
  UNIQUE KEY uq_assignment_submission_versions_no (assignment_submission_id, version_no),
  KEY idx_assignment_submission_versions_attachment (attachment_group_id),
  KEY idx_assignment_submission_versions_submitter (submitted_by_user_id),
  CONSTRAINT fk_assignment_submission_versions_assignment_submissions FOREIGN KEY (assignment_submission_id) REFERENCES assignment_submissions (assignment_submission_id),
  CONSTRAINT fk_assignment_submission_versions_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id),
  CONSTRAINT fk_assignment_submission_versions_users FOREIGN KEY (submitted_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과제 제출 버전';

CREATE TABLE assignment_peer_reviews (
  peer_review_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '상호평가 ID',
  assignment_id BIGINT UNSIGNED NOT NULL COMMENT '과제 ID',
  reviewer_user_id BIGINT UNSIGNED NOT NULL COMMENT '평가자 ID',
  assignment_submission_id BIGINT UNSIGNED NOT NULL COMMENT '평가 대상 제출물 ID',
  assigned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '배정 일시',
  evaluated_at DATETIME(3) NULL COMMENT '평가 완료 일시',
  score DECIMAL(6,2) NULL COMMENT '상호평가 점수',
  review_comment VARCHAR(1000) NULL COMMENT '상호평가 의견',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (peer_review_id),
  UNIQUE KEY uq_assignment_peer_reviews_target (assignment_id, reviewer_user_id, assignment_submission_id),
  KEY idx_assignment_peer_reviews_reviewer (reviewer_user_id),
  KEY idx_assignment_peer_reviews_submission (assignment_submission_id),
  CONSTRAINT fk_assignment_peer_reviews_assignments FOREIGN KEY (assignment_id) REFERENCES assignments (assignment_id),
  CONSTRAINT fk_assignment_peer_reviews_users FOREIGN KEY (reviewer_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_assignment_peer_reviews_assignment_submissions FOREIGN KEY (assignment_submission_id) REFERENCES assignment_submissions (assignment_submission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='상호평가 배정';

CREATE TABLE quizzes (
  quiz_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '퀴즈 ID',
  activity_id BIGINT UNSIGNED NOT NULL COMMENT '학습활동 ID',
  exam_type VARCHAR(20) NOT NULL DEFAULT 'online' COMMENT '시험형태(online:온라인, offline:오프라인)',
  duration_minutes INT NULL COMMENT '응시 제한 시간(분) [추정]',
  attempt_limit INT NULL COMMENT '허용 응시 횟수(NULL=제한 없음) [추정]',
  shuffle_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '문항 순서 섞기 여부 [추정]',
  answer_release_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '정답·해설 공개 여부 [추정]',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (quiz_id),
  UNIQUE KEY uq_quizzes_activity (activity_id),
  CONSTRAINT fk_quizzes_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='퀴즈';

CREATE TABLE quiz_questions (
  quiz_question_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '퀴즈 문항 ID',
  quiz_id BIGINT UNSIGNED NOT NULL COMMENT '퀴즈 ID',
  question_no INT NOT NULL COMMENT '문항 번호',
  question_type VARCHAR(20) NOT NULL DEFAULT 'choice' COMMENT '문항 유형(choice:객관식, short:단답형, essay:서술형) [추정]',
  question_text TEXT NOT NULL COMMENT '문항 지문',
  score DECIMAL(6,2) NOT NULL DEFAULT 0.00 COMMENT '문항 배점',
  explanation TEXT NULL COMMENT '해설(결과 공개 후 노출) [추정]',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (quiz_question_id),
  UNIQUE KEY uq_quiz_questions_quiz_no (quiz_id, question_no),
  CONSTRAINT fk_quiz_questions_quizzes FOREIGN KEY (quiz_id) REFERENCES quizzes (quiz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='퀴즈 문항';

CREATE TABLE quiz_question_options (
  quiz_question_option_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '퀴즈 보기 ID',
  quiz_question_id BIGINT UNSIGNED NOT NULL COMMENT '퀴즈 문항 ID',
  option_no INT NOT NULL COMMENT '보기 번호',
  option_text VARCHAR(1000) NOT NULL COMMENT '보기 문구',
  is_correct_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '정답 여부',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (quiz_question_option_id),
  UNIQUE KEY uq_quiz_question_options_no (quiz_question_id, option_no),
  CONSTRAINT fk_quiz_question_options_quiz_questions FOREIGN KEY (quiz_question_id) REFERENCES quiz_questions (quiz_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='퀴즈 보기';

CREATE TABLE quiz_attempts (
  quiz_attempt_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '퀴즈 응시 ID',
  quiz_id BIGINT UNSIGNED NOT NULL COMMENT '퀴즈 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '응시 학생 ID',
  attempt_no INT NOT NULL DEFAULT 1 COMMENT '응시 회차',
  status VARCHAR(20) NOT NULL DEFAULT 'in_progress' COMMENT '응시 상태(in_progress:진행중, submitted:제출됨, graded:채점완료, expired:시간만료 자동제출, voided:무효)',
  started_at DATETIME(3) NOT NULL COMMENT '응시 시작 일시',
  deadline_at DATETIME(3) NOT NULL COMMENT '서버 확정 제출 마감 일시',
  submitted_at DATETIME(3) NULL COMMENT '제출 일시',
  total_score DECIMAL(6,2) NULL COMMENT '총점(채점 완료 후 확정)',
  graded_at DATETIME(3) NULL COMMENT '채점 완료 일시',
  session_lock_key VARCHAR(128) NULL COMMENT '응시 세션 잠금 식별자(중복 기기 차단)',
  lock_acquired_at DATETIME(3) NULL COMMENT '세션 잠금 획득 일시',
  active_attempt_user_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(status = 'in_progress', user_id, NULL)) STORED COMMENT '진행 중 응시자(진행 중 1건 유일성 보조)',
  legacy_exam_seq VARCHAR(32) NULL COMMENT 'ILOS 응시 기록 번호(exam_seq)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (quiz_attempt_id),
  UNIQUE KEY uq_quiz_attempts_quiz_user_no (quiz_id, user_id, attempt_no),
  UNIQUE KEY uq_quiz_attempts_active (quiz_id, active_attempt_user_id),
  UNIQUE KEY uq_quiz_attempts_legacy_exam_seq (legacy_exam_seq),
  KEY idx_quiz_attempts_user (user_id),
  CONSTRAINT fk_quiz_attempts_quizzes FOREIGN KEY (quiz_id) REFERENCES quizzes (quiz_id),
  CONSTRAINT fk_quiz_attempts_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='퀴즈 응시';

CREATE TABLE quiz_attempt_answers (
  quiz_attempt_answer_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '퀴즈 답안 ID',
  quiz_attempt_id BIGINT UNSIGNED NOT NULL COMMENT '응시 ID',
  quiz_question_id BIGINT UNSIGNED NOT NULL COMMENT '퀴즈 문항 ID',
  answer_text TEXT NULL COMMENT '서술·단답 답안',
  revision INT NOT NULL DEFAULT 0 COMMENT '답안 개정 번호',
  saved_at DATETIME(3) NULL COMMENT '답안 최종 저장 일시',
  is_correct_yn TINYINT(1) NULL COMMENT '정답 여부(NULL=미채점)',
  awarded_score DECIMAL(6,2) NULL COMMENT '문항 획득 점수',
  grading_status VARCHAR(20) NULL COMMENT '채점 상태(auto:자동, pending_similar:유사답 검사중, done:완료, manual:수동)',
  graded_at DATETIME(3) NULL COMMENT '채점 일시',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (quiz_attempt_answer_id),
  UNIQUE KEY uq_quiz_attempt_answers_attempt_question (quiz_attempt_id, quiz_question_id),
  KEY idx_quiz_attempt_answers_question (quiz_question_id),
  CONSTRAINT fk_quiz_attempt_answers_quiz_attempts FOREIGN KEY (quiz_attempt_id) REFERENCES quiz_attempts (quiz_attempt_id),
  CONSTRAINT fk_quiz_attempt_answers_quiz_questions FOREIGN KEY (quiz_question_id) REFERENCES quiz_questions (quiz_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='퀴즈 답안';

CREATE TABLE quiz_attempt_answer_options (
  quiz_attempt_answer_option_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '퀴즈 답안 보기 ID',
  quiz_attempt_answer_id BIGINT UNSIGNED NOT NULL COMMENT '퀴즈 답안 ID',
  quiz_question_option_id BIGINT UNSIGNED NOT NULL COMMENT '선택한 보기 ID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (quiz_attempt_answer_option_id),
  UNIQUE KEY uq_quiz_attempt_answer_options_answer_option (quiz_attempt_answer_id, quiz_question_option_id),
  KEY idx_quiz_attempt_answer_options_option (quiz_question_option_id),
  CONSTRAINT fk_quiz_attempt_answer_options_quiz_attempt_answers FOREIGN KEY (quiz_attempt_answer_id) REFERENCES quiz_attempt_answers (quiz_attempt_answer_id),
  CONSTRAINT fk_quiz_attempt_answer_options_quiz_question_options FOREIGN KEY (quiz_question_option_id) REFERENCES quiz_question_options (quiz_question_option_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='퀴즈 답안 보기';

CREATE TABLE surveys (
  survey_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 ID',
  activity_id BIGINT UNSIGNED NULL COMMENT '학습활동 ID(NULL=알림마당 전역 설문, 과목은 활동에서 유도)',
  title VARCHAR(300) NOT NULL COMMENT '설문 제목',
  description_html MEDIUMTEXT NULL COMMENT '설문 목적·안내 본문(HTML)',
  response_mode VARCHAR(10) NOT NULL DEFAULT 'anonymous' COMMENT '응답방식(anonymous:익명, named:실명)',
  open_at DATETIME(3) NULL COMMENT '응답 시작 일시(NULL=학습활동 open_at 사용)',
  close_at DATETIME(3) NULL COMMENT '응답 마감 일시(NULL=학습활동 close_at 사용)',
  allow_retake_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '재응답 허용 여부',
  result_visible_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '결과 공개 여부',
  result_release_at DATETIME(3) NULL COMMENT '결과 공개 일시',
  result_min_cell_k INT NOT NULL DEFAULT 5 COMMENT '결과 집계 최소 공개 인원 [추정]',
  created_by_user_id BIGINT UNSIGNED NULL COMMENT '등록자 ID',
  legacy_survey_seq VARCHAR(32) NULL COMMENT 'ILOS 설문 번호(SURVEY_SEQ)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (survey_id),
  UNIQUE KEY uq_surveys_activity (activity_id),
  UNIQUE KEY uq_surveys_legacy_survey_seq (legacy_survey_seq),
  KEY idx_surveys_close (close_at),
  KEY idx_surveys_creator (created_by_user_id),
  KEY idx_surveys_open (open_at),
  CONSTRAINT fk_surveys_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id),
  CONSTRAINT fk_surveys_users FOREIGN KEY (created_by_user_id) REFERENCES users (user_id),
  CONSTRAINT chk_surveys_scope CHECK (
    (activity_id IS NOT NULL AND open_at IS NULL AND close_at IS NULL) OR
    (activity_id IS NULL AND open_at IS NOT NULL AND close_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문';

CREATE TABLE survey_targets (
  survey_target_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 대상 ID',
  survey_id BIGINT UNSIGNED NOT NULL COMMENT '설문 ID',
  target_type VARCHAR(20) NOT NULL COMMENT '대상 구분(all:전체, student:학생, staff:교직원, grade:학년) [추정]',
  target_value VARCHAR(50) NULL COMMENT '대상 값(학년·소속 등) [추정]',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (survey_target_id),
  KEY idx_survey_targets_survey (survey_id, target_type),
  CONSTRAINT fk_survey_targets_surveys FOREIGN KEY (survey_id) REFERENCES surveys (survey_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 대상';

CREATE TABLE survey_questions (
  survey_question_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 문항 ID',
  survey_id BIGINT UNSIGNED NOT NULL COMMENT '설문 ID',
  question_no INT NOT NULL COMMENT '표시 문항 번호',
  page_no INT NOT NULL DEFAULT 1 COMMENT '쪽 번호(쪽 단위 표시·검증) [추정]',
  question_type VARCHAR(20) NOT NULL DEFAULT 'single' COMMENT '문항 유형(single:객관식 단일, multiple:객관식 복수, essay:서술형)',
  question_text TEXT NOT NULL COMMENT '문항 지문',
  is_mandatory_yn TINYINT(1) NOT NULL DEFAULT 1 COMMENT '필수 응답 여부',
  min_select INT NULL COMMENT '최소 선택 수(ans_min)',
  max_select INT NULL COMMENT '최대 선택 수(ans_max)',
  is_follow_up_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '보기 선택에 따른 꼬리 문항 여부',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (survey_question_id),
  UNIQUE KEY uq_survey_questions_no (survey_id, question_no),
  KEY idx_survey_questions_page (survey_id, page_no),
  CONSTRAINT fk_survey_questions_surveys FOREIGN KEY (survey_id) REFERENCES surveys (survey_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 문항';

CREATE TABLE survey_question_options (
  survey_question_option_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 보기 ID',
  survey_question_id BIGINT UNSIGNED NOT NULL COMMENT '설문 문항 ID',
  option_no INT NOT NULL COMMENT '보기 번호(EX_NO)',
  option_text VARCHAR(1000) NOT NULL COMMENT '보기 문구',
  allow_etc_input_yn TINYINT(1) NOT NULL DEFAULT 0 COMMENT '기타 입력 허용 여부(etc_yn)',
  follow_up_question_id BIGINT UNSIGNED NULL COMMENT '이 보기 선택 시 표시할 꼬리 문항 ID',
  sort_order INT NOT NULL DEFAULT 0 COMMENT '정렬 순서',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (survey_question_option_id),
  UNIQUE KEY uq_survey_question_options_no (survey_question_id, option_no),
  KEY idx_survey_question_options_follow_up (follow_up_question_id),
  CONSTRAINT fk_survey_question_options_survey_questions FOREIGN KEY (survey_question_id) REFERENCES survey_questions (survey_question_id),
  CONSTRAINT fk_survey_question_options_follow_up FOREIGN KEY (follow_up_question_id) REFERENCES survey_questions (survey_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 보기';

CREATE TABLE survey_responses (
  survey_response_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 응답 세트 ID',
  survey_id BIGINT UNSIGNED NOT NULL COMMENT '설문 ID',
  revision_no INT NOT NULL DEFAULT 1 COMMENT '응답 세트 차수(재응답 시 증가)',
  respondent_user_id BIGINT UNSIGNED NULL COMMENT '실명 설문 응답자 ID(익명 설문은 NULL)',
  is_current_yn TINYINT(1) NOT NULL DEFAULT 1 COMMENT '현재 유효 응답 세트 여부',
  submitted_at DATETIME(3) NOT NULL COMMENT '제출 일시(익명 설문은 일 단위 절삭 저장)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (survey_response_id),
  KEY idx_survey_responses_survey (survey_id, is_current_yn),
  KEY idx_survey_responses_respondent (respondent_user_id),
  CONSTRAINT fk_survey_responses_surveys FOREIGN KEY (survey_id) REFERENCES surveys (survey_id),
  CONSTRAINT fk_survey_responses_users FOREIGN KEY (respondent_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 응답 세트';

CREATE TABLE survey_participations (
  survey_participation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 참여 ID',
  survey_id BIGINT UNSIGNED NOT NULL COMMENT '설문 ID',
  user_id BIGINT UNSIGNED NOT NULL COMMENT '사용자 ID',
  state VARCHAR(20) NOT NULL DEFAULT 'not_started' COMMENT '참여 상태(not_started:미응답, in_progress:작성중, completed:응답완료)',
  started_at DATETIME(3) NULL COMMENT '최초 응답 시작 일시',
  completed_at DATETIME(3) NULL COMMENT '응답 완료 일시',
  current_response_id BIGINT UNSIGNED NULL COMMENT '현재 응답 세트 ID',
  revision INT NOT NULL DEFAULT 0 COMMENT '재응답 차수',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at DATETIME(3) NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (survey_participation_id),
  UNIQUE KEY uq_survey_participations_survey_user (survey_id, user_id),
  KEY idx_survey_participations_user (user_id),
  KEY idx_survey_participations_response (current_response_id),
  CONSTRAINT fk_survey_participations_surveys FOREIGN KEY (survey_id) REFERENCES surveys (survey_id),
  CONSTRAINT fk_survey_participations_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_survey_participations_survey_responses FOREIGN KEY (current_response_id) REFERENCES survey_responses (survey_response_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 참여';

CREATE TABLE survey_answers (
  survey_answer_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 답변 ID',
  survey_response_id BIGINT UNSIGNED NOT NULL COMMENT '설문 응답 세트 ID',
  survey_question_id BIGINT UNSIGNED NOT NULL COMMENT '설문 문항 ID',
  answer_text VARCHAR(2000) NULL COMMENT '기타·서술형 답변(최대 1800바이트)',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (survey_answer_id),
  UNIQUE KEY uq_survey_answers_response_question (survey_response_id, survey_question_id),
  KEY idx_survey_answers_question (survey_question_id),
  CONSTRAINT fk_survey_answers_survey_responses FOREIGN KEY (survey_response_id) REFERENCES survey_responses (survey_response_id),
  CONSTRAINT fk_survey_answers_survey_questions FOREIGN KEY (survey_question_id) REFERENCES survey_questions (survey_question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 답변';

CREATE TABLE survey_answer_options (
  survey_answer_option_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '설문 답변 보기 ID',
  survey_answer_id BIGINT UNSIGNED NOT NULL COMMENT '설문 답변 ID',
  survey_question_option_id BIGINT UNSIGNED NOT NULL COMMENT '선택한 보기 ID',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (survey_answer_option_id),
  UNIQUE KEY uq_survey_answer_options_answer_option (survey_answer_id, survey_question_option_id),
  KEY idx_survey_answer_options_option (survey_question_option_id),
  CONSTRAINT fk_survey_answer_options_survey_answers FOREIGN KEY (survey_answer_id) REFERENCES survey_answers (survey_answer_id),
  CONSTRAINT fk_survey_answer_options_survey_question_options FOREIGN KEY (survey_question_option_id) REFERENCES survey_question_options (survey_question_option_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='설문 답변 보기';


-- ===== 50_communication =====
-- 1. 게시판
CREATE TABLE boards (
  board_id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '게시판 ID',
  course_id               BIGINT UNSIGNED NULL COMMENT '과목 ID(알림마당 전역 게시판이면 NULL)',
  board_type              VARCHAR(20)     NOT NULL COMMENT '게시판 유형: K001(공지)|K002(질의응답)|K004(열린게시판)|H002(1:1 문의)|MATERIAL(자료실)',
  board_name              VARCHAR(100)    NOT NULL COMMENT '게시판 표시명',
  legacy_artl_grp_id      BIGINT UNSIGNED NULL COMMENT '원본 게시판 그룹 ID(ARTL_GRP_ID)',
  sort_order              INT             NOT NULL DEFAULT 0 COMMENT '좌측 메뉴 노출 순서',
  allows_student_write    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '학생 글쓰기 허용 여부',
  allows_anonymous        TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '익명 작성 허용 여부',
  allows_secret           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '비공개 글 허용 여부',
  allows_comment          TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '댓글 사용 여부',
  uses_faq                TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'FAQ 지정 사용 여부',
  uses_solved_status      TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '해결 상태 사용 여부',
  requires_notice_confirm TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '공지 확인 요구 여부',
  is_active               TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '사용 여부',
  created_at              DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at              DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (board_id),
  UNIQUE KEY uq_boards_legacy_artl_grp_id (legacy_artl_grp_id),
  KEY idx_boards_course_id_board_type (course_id, board_type),
  CONSTRAINT fk_boards_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시판';

-- 2. 게시글
CREATE TABLE posts (
  post_id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '게시글 ID',
  board_id              BIGINT UNSIGNED NOT NULL COMMENT '게시판 ID',
  parent_post_id        BIGINT UNSIGNED NULL COMMENT '부모 게시글 ID(답글·추가 질문)',
  author_id             BIGINT UNSIGNED NOT NULL COMMENT '작성자 사용자 ID',
  post_kind             VARCHAR(20)     NOT NULL DEFAULT 'ARTICLE' COMMENT '글 종류: ARTICLE(일반)|NOTICE(공지)|MATERIAL(자료)|QUESTION(질문)|ANSWER(답글)',
  title                 VARCHAR(500)    NOT NULL COMMENT '제목',
  body_html             MEDIUMTEXT      NULL COMMENT '본문 HTML(서버 sanitize 결과)',
  body_text             MEDIUMTEXT      NULL COMMENT '본문 평문(검색·요약용)',
  excerpt               VARCHAR(1000)   NULL COMMENT '목록 요약문',
  audience              VARCHAR(16)     NOT NULL DEFAULT 'ALL' COMMENT '공지 대상: ALL(전체)|COMMON(공통)|PROFESSOR(교수)|STUDENT(학생)',
  is_pinned             TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '상단 고정 여부',
  is_anonymous          TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '익명 작성 여부(작성자 이름 비노출)',
  is_secret             TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '비공개 여부(작성자·교수자만 본문 열람)',
  is_faq                TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'FAQ 지정 여부',
  pro_status            VARCHAR(1)      NOT NULL DEFAULT 'N' COMMENT '해결 상태: Y(해결)|N(미해결)',
  is_answered           TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '답변 등록 여부(1:1 문의 등)',
  answered_at           DATETIME(3)     NULL COMMENT '최초 답변 일시',
  view_count            INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '조회수',
  like_count            INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '추천 수',
  comment_count         INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '댓글 수',
  answer_count          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '답글 수',
  learning_activity_id  BIGINT UNSIGNED NULL COMMENT '연결된 학습활동 ID',
  legacy_target_menu    VARCHAR(50)     NULL COMMENT '원본 학습활동 메뉴 코드(T_MENU_ID)',
  attachment_group_id   BIGINT UNSIGNED NULL COMMENT '첨부 그룹 ID',
  notice_expire_at      DATETIME(3)     NULL COMMENT '공지 노출 만료 일시(기간제한 없음이면 NULL)',
  published_at          DATETIME(3)     NULL COMMENT '게시 일시(예약 공지 포함)',
  legacy_artl_num       BIGINT UNSIGNED NULL COMMENT '원본 게시글 번호(ARTL_NUM)',
  version               INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '수정 버전(낙관적 잠금)',
  client_request_id     VARCHAR(64)     NULL COMMENT '중복 등록 방지 키',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at            DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  deleted_at            DATETIME(3)     NULL COMMENT '삭제 일시(소프트 삭제)',
  PRIMARY KEY (post_id),
  UNIQUE KEY uq_posts_legacy_artl_num (legacy_artl_num),
  UNIQUE KEY uq_posts_board_client_request (board_id, client_request_id),
  KEY idx_posts_board_pinned_published (board_id, is_pinned, published_at),
  KEY idx_posts_board_created_at (board_id, created_at),
  KEY idx_posts_parent_post_id (parent_post_id),
  KEY idx_posts_author_id_created_at (author_id, created_at),
  KEY idx_posts_learning_activity_id (learning_activity_id),
  CONSTRAINT fk_posts_boards FOREIGN KEY (board_id) REFERENCES boards (board_id),
  CONSTRAINT fk_posts_posts FOREIGN KEY (parent_post_id) REFERENCES posts (post_id),
  CONSTRAINT fk_posts_users FOREIGN KEY (author_id) REFERENCES users (user_id),
  CONSTRAINT fk_posts_learning_activities FOREIGN KEY (learning_activity_id) REFERENCES learning_activities (activity_id),
  CONSTRAINT fk_posts_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시글';

-- 3. 게시글 열람 기록
CREATE TABLE post_read_receipts (
  post_read_receipt_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '게시글 열람 기록 ID',
  post_id              BIGINT UNSIGNED NOT NULL COMMENT '게시글 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '열람 사용자 ID',
  first_read_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '최초 열람 일시',
  last_read_at         DATETIME(3)     NULL COMMENT '최근 열람 일시',
  read_count           INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '열람 횟수',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at           DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (post_read_receipt_id),
  UNIQUE KEY uq_post_read_receipts_post_user (post_id, user_id),
  KEY idx_post_read_receipts_user_first_read (user_id, first_read_at),
  CONSTRAINT fk_post_read_receipts_posts FOREIGN KEY (post_id) REFERENCES posts (post_id),
  CONSTRAINT fk_post_read_receipts_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시글 열람 기록';

-- 4. 공지 확인
CREATE TABLE post_confirmations (
  post_confirmation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '공지 확인 ID',
  post_id              BIGINT UNSIGNED NOT NULL COMMENT '게시글 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '확인 사용자 ID',
  confirmed_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '확인 일시',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (post_confirmation_id),
  UNIQUE KEY uq_post_confirmations_post_user (post_id, user_id),
  KEY idx_post_confirmations_user_id (user_id),
  CONSTRAINT fk_post_confirmations_posts FOREIGN KEY (post_id) REFERENCES posts (post_id),
  CONSTRAINT fk_post_confirmations_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='공지 확인';

-- 5. 게시글 추천
CREATE TABLE post_likes (
  post_like_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '게시글 추천 ID',
  post_id      BIGINT UNSIGNED NOT NULL COMMENT '게시글 ID',
  user_id      BIGINT UNSIGNED NOT NULL COMMENT '추천 사용자 ID',
  created_at   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '추천 일시',
  PRIMARY KEY (post_like_id),
  UNIQUE KEY uq_post_likes_post_user (post_id, user_id),
  KEY idx_post_likes_user_id (user_id),
  CONSTRAINT fk_post_likes_posts FOREIGN KEY (post_id) REFERENCES posts (post_id),
  CONSTRAINT fk_post_likes_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시글 추천';

-- 6. 댓글
CREATE TABLE comments (
  comment_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '댓글 ID',
  post_id           BIGINT UNSIGNED NOT NULL COMMENT '게시글 ID',
  parent_comment_id BIGINT UNSIGNED NULL COMMENT '부모 댓글 ID(댓글 답글)',
  author_id         BIGINT UNSIGNED NOT NULL COMMENT '작성자 사용자 ID',
  body_text         VARCHAR(2000)   NOT NULL COMMENT '댓글 본문(최대 1800byte)',
  is_anonymous      TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '익명 작성 여부',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at        DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  deleted_at        DATETIME(3)     NULL COMMENT '삭제 일시(소프트 삭제)',
  PRIMARY KEY (comment_id),
  KEY idx_comments_post_created_at (post_id, created_at),
  KEY idx_comments_parent_comment_id (parent_comment_id),
  KEY idx_comments_author_id (author_id),
  CONSTRAINT fk_comments_posts FOREIGN KEY (post_id) REFERENCES posts (post_id),
  CONSTRAINT fk_comments_comments FOREIGN KEY (parent_comment_id) REFERENCES comments (comment_id),
  CONSTRAINT fk_comments_users FOREIGN KEY (author_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='댓글';

-- 7. 서포터즈 대화방
CREATE TABLE supporters_chats (
  supporters_chat_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '서포터즈 대화방 ID',
  user_id            BIGINT UNSIGNED NOT NULL COMMENT '대화 소유 사용자 ID',
  course_id          BIGINT UNSIGNED NULL COMMENT '대화 진입 과목 ID(전역이면 NULL)',
  menu_code          VARCHAR(50)     NULL COMMENT '진입 메뉴 코드(MENU_ID)',
  title              VARCHAR(300)    NOT NULL COMMENT '대화 제목(첫 질문 또는 서버 갱신 값)',
  message_count      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '메시지 수',
  last_message_at    DATETIME(3)     NULL COMMENT '최근 메시지 일시',
  legacy_chat_seq    BIGINT UNSIGNED NULL COMMENT '원본 대화 번호(SUPPORTERS_CHAT_SEQ)',
  legacy_artl_num    VARCHAR(40)     NULL COMMENT '원본 연결 글 번호(ARTL_NUM)',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at         DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  deleted_at         DATETIME(3)     NULL COMMENT '삭제 일시(사용자 삭제)',
  PRIMARY KEY (supporters_chat_id),
  UNIQUE KEY uq_supporters_chats_legacy_chat_seq (legacy_chat_seq),
  KEY idx_supporters_chats_user_updated (user_id, updated_at),
  KEY idx_supporters_chats_user_deleted (user_id, deleted_at),
  CONSTRAINT fk_supporters_chats_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_supporters_chats_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='서포터즈 대화방';

-- 8. 서포터즈 대화 메시지
CREATE TABLE supporters_chat_messages (
  supporters_chat_message_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '서포터즈 대화 메시지 ID',
  supporters_chat_id         BIGINT UNSIGNED NOT NULL COMMENT '대화방 ID',
  speaker                    VARCHAR(1)      NOT NULL COMMENT '발화 구분: Q(질문)|A(답변)',
  content                    MEDIUMTEXT      NOT NULL COMMENT '메시지 본문(답변은 마크다운)',
  client_index               BIGINT UNSIGNED NULL COMMENT '클라이언트 메시지 인덱스(ms epoch)',
  legacy_message_no          BIGINT UNSIGNED NULL COMMENT '원본 말풍선 번호(messageNo)',
  state                      VARCHAR(16)     NOT NULL DEFAULT 'COMPLETED' COMMENT '상태: PENDING(생성 중)|COMPLETED(완료)|REJECTED(거절)',
  is_late                    TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '지연 도착 여부',
  created_at                 DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (supporters_chat_message_id),
  KEY idx_supporters_chat_messages_chat_created (supporters_chat_id, created_at),
  KEY idx_supporters_chat_messages_chat_client_index (supporters_chat_id, client_index),
  CONSTRAINT fk_supporters_chat_messages_supporters_chats FOREIGN KEY (supporters_chat_id) REFERENCES supporters_chats (supporters_chat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='서포터즈 대화 메시지';

-- 9. 쪽지
CREATE TABLE messages (
  message_id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '쪽지 ID',
  sender_id           BIGINT UNSIGNED NOT NULL COMMENT '발신자 사용자 ID',
  course_id           BIGINT UNSIGNED NULL COMMENT '관련 과목 ID(과목선택 안함이면 NULL)',
  title               VARCHAR(300)    NOT NULL COMMENT '제목',
  body_text           MEDIUMTEXT      NOT NULL COMMENT '본문 평문',
  recipient_count     SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '수신자 수',
  attachment_group_id BIGINT UNSIGNED NULL COMMENT '첨부 그룹 ID',
  client_request_id   VARCHAR(64)     NULL COMMENT '중복 발송 방지 키',
  canceled_at         DATETIME(3)     NULL COMMENT '전송 취소 일시',
  sender_deleted_at   DATETIME(3)     NULL COMMENT '발신자 삭제 일시(보낸함에서만 숨김)',
  legacy_seq          BIGINT UNSIGNED NULL COMMENT '원본 쪽지 번호(SEQ)',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '발송 일시',
  updated_at          DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (message_id),
  UNIQUE KEY uq_messages_sender_client_request (sender_id, client_request_id),
  UNIQUE KEY uq_messages_legacy_seq (legacy_seq),
  KEY idx_messages_sender_created_at (sender_id, created_at),
  KEY idx_messages_sender_deleted (sender_id, sender_deleted_at),
  KEY idx_messages_course_id (course_id),
  CONSTRAINT fk_messages_users FOREIGN KEY (sender_id) REFERENCES users (user_id),
  CONSTRAINT fk_messages_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_messages_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='쪽지';

-- 10. 쪽지 수신자
CREATE TABLE message_recipients (
  message_recipient_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '쪽지 수신자 ID',
  message_id           BIGINT UNSIGNED NOT NULL COMMENT '쪽지 ID',
  recipient_id         BIGINT UNSIGNED NOT NULL COMMENT '수신자 사용자 ID',
  course_id            BIGINT UNSIGNED NULL COMMENT '수신자 기준 과목 ID(없으면 NULL)',
  read_at              DATETIME(3)     NULL COMMENT '읽은 일시(미열람이면 NULL)',
  canceled_at          DATETIME(3)     NULL COMMENT '수신자 기준 전송 취소 일시',
  recipient_deleted_at DATETIME(3)     NULL COMMENT '수신자 삭제 일시(받은함에서만 숨김)',
  purge_after_at       DATETIME(3)     NULL COMMENT '보존 만료 일시(읽은 쪽지 30일)',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at           DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (message_recipient_id),
  UNIQUE KEY uq_message_recipients_message_recipient (message_id, recipient_id),
  KEY idx_message_recipients_recipient_read (recipient_id, read_at),
  KEY idx_message_recipients_recipient_deleted (recipient_id, recipient_deleted_at),
  KEY idx_message_recipients_recipient_created (recipient_id, created_at),
  CONSTRAINT fk_message_recipients_messages FOREIGN KEY (message_id) REFERENCES messages (message_id),
  CONSTRAINT fk_message_recipients_users FOREIGN KEY (recipient_id) REFERENCES users (user_id),
  CONSTRAINT fk_message_recipients_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='쪽지 수신자';

-- 11. 게시글 임시저장
CREATE TABLE post_drafts (
  post_draft_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '게시글 임시저장 ID',
  user_id              BIGINT UNSIGNED NOT NULL COMMENT '작성자 사용자 ID',
  board_id             BIGINT UNSIGNED NOT NULL COMMENT '게시판 ID',
  course_id            BIGINT UNSIGNED NULL COMMENT '과목 ID(전역이면 NULL)',
  target_post_id       BIGINT UNSIGNED NULL COMMENT '수정 대상 게시글 ID(신규 작성이면 NULL)',
  form_key             VARCHAR(20)     NOT NULL COMMENT '작성 폼 구분(FROM_DIV: 1|2)',
  contents             MEDIUMTEXT      NOT NULL COMMENT '직렬화된 폼 값(HTML 포함, sanitize 결과)',
  content_hash         CHAR(64)        NULL COMMENT '내용 지문(중복 저장 판단용)',
  attachment_group_id  BIGINT UNSIGNED NULL COMMENT '첨부 그룹 ID(초안 첨부)',
  version              INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '초안 버전(탭 간 충돌 감지)',
  expires_at           DATETIME(3)     NULL COMMENT '보존 만료 일시(TTL)',
  course_key           BIGINT UNSIGNED AS (IFNULL(course_id, 0)) STORED COMMENT '과목 스코프 키(NULL 대체)',
  target_post_key      BIGINT UNSIGNED AS (IFNULL(target_post_id, 0)) STORED COMMENT '대상 글 스코프 키(NULL 대체)',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at           DATETIME(3)     NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (post_draft_id),
  UNIQUE KEY uq_post_drafts_scope (user_id, board_id, form_key, course_key, target_post_key),
  KEY idx_post_drafts_user_updated (user_id, updated_at),
  KEY idx_post_drafts_expires_at (expires_at),
  CONSTRAINT fk_post_drafts_users FOREIGN KEY (user_id) REFERENCES users (user_id),
  CONSTRAINT fk_post_drafts_boards FOREIGN KEY (board_id) REFERENCES boards (board_id),
  CONSTRAINT fk_post_drafts_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_post_drafts_posts FOREIGN KEY (target_post_id) REFERENCES posts (post_id),
  CONSTRAINT fk_post_drafts_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='게시글 임시저장';


-- ===== 60_files =====
-- ---------------------------------------------------------------
-- 1. 업로드 정책 (K002/K004/K006/M001/H002/MY01)
-- ---------------------------------------------------------------
CREATE TABLE upload_policies (
  upload_policy_id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '업로드 정책 ID',
  policy_code         VARCHAR(20)     NOT NULL COMMENT '경로 코드(K002,K004,K006,M001,H002,MY01)',
  policy_name         VARCHAR(100)    NOT NULL COMMENT '정책 이름(과제 제출 첨부 등)',
  max_file_bytes      BIGINT UNSIGNED NULL     COMMENT '파일 1개 최대 바이트(빈 값=무제한)',
  max_file_count      SMALLINT UNSIGNED NULL   COMMENT '첨부 최대 개수(빈 값=무제한)',
  allowed_extensions  VARCHAR(500)    NULL     COMMENT '허용 확장자 목록(쉼표 구분)',
  default_notice_text VARCHAR(300)    NULL     COMMENT '첨부 안내 문구 원문',
  is_active           TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '정책 사용 여부',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at          DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (upload_policy_id),
  UNIQUE KEY uq_upload_policies_policy_code (policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='업로드 정책';

-- ---------------------------------------------------------------
-- 2. 파일 원본 객체 (물리 저장 단위)
-- ---------------------------------------------------------------
CREATE TABLE file_blobs (
  file_blob_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '파일 원본 객체 ID',
  content_sha256     CHAR(64)        NOT NULL COMMENT '원본 바이트 SHA-256',
  size_bytes         BIGINT UNSIGNED NOT NULL COMMENT '원본 바이트 크기',
  detected_mime_type VARCHAR(150)    NULL     COMMENT '저장 시 판정한 MIME 타입',
  storage_key        VARCHAR(400)    NOT NULL COMMENT '서버 생성 저장 키',
  blob_state         VARCHAR(12)     NOT NULL DEFAULT 'quarantined' COMMENT '검사 상태(quarantined|clean|rejected)',
  reference_count    INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '참조 중인 파일 수',
  purged_at          DATETIME(3)     NULL     COMMENT '물리 삭제 일시',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at         DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (file_blob_id),
  UNIQUE KEY uq_file_blobs_content_sha256 (content_sha256),
  UNIQUE KEY uq_file_blobs_storage_key (storage_key),
  KEY idx_file_blobs_purged_at (purged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일 원본 객체';

-- ---------------------------------------------------------------
-- 3. 파일 (논리 파일 = ILOS FILE_SEQ)
-- ---------------------------------------------------------------
CREATE TABLE files (
  file_id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '파일 ID',
  file_blob_id         BIGINT UNSIGNED NOT NULL COMMENT '원본 객체 ID',
  legacy_file_seq      CHAR(13)        NULL     COMMENT 'ILOS FILE_SEQ(13자)',
  original_file_name   VARCHAR(255)    NOT NULL COMMENT '원본 파일명',
  stored_file_name     VARCHAR(255)    NULL     COMMENT '저장 파일명',
  file_extension       VARCHAR(20)     NULL     COMMENT '확장자 표시값(PDF,DOCX,zip,ETC)',
  mime_type            VARCHAR(150)    NULL     COMMENT '파일 MIME 타입',
  file_size_bytes      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '파일 크기(바이트)',
  uploader_user_id     BIGINT UNSIGNED NULL     COMMENT '업로드한 사용자 ID',
  course_id            BIGINT UNSIGNED NULL     COMMENT '소속 과목 ID(과목 밖 파일은 NULL)',
  source_menu_code     VARCHAR(30)     NULL     COMMENT '업로드 출처 메뉴 코드(K006,lecture_material,report,notice 등)',
  scan_state           VARCHAR(12)     NOT NULL DEFAULT 'pending' COMMENT '검사 상태(pending|clean|infected|failed)',
  inline_view_allowed  TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '이미지 인라인 열람 허용 여부',
  preview_convertible  TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '문서 미리보기 변환 가능 여부',
  file_version_no      INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '파일 버전 번호',
  state                VARCHAR(12)     NOT NULL DEFAULT 'active' COMMENT '상태(active|deleted)',
  deleted_at           DATETIME(3)     NULL     COMMENT '삭제 일시',
  created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '등록 일시',
  updated_at           DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (file_id),
  UNIQUE KEY uq_files_legacy_file_seq (legacy_file_seq),
  KEY idx_files_file_blob (file_blob_id),
  KEY idx_files_course_source (course_id, source_menu_code),
  KEY idx_files_uploader (uploader_user_id),
  CONSTRAINT fk_files_file_blobs FOREIGN KEY (file_blob_id) REFERENCES file_blobs (file_blob_id),
  CONSTRAINT fk_files_users FOREIGN KEY (uploader_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_files_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일';

-- ---------------------------------------------------------------
-- 4. 임시 업로드 세션
-- ---------------------------------------------------------------
CREATE TABLE upload_sessions (
  upload_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '업로드 세션 ID',
  upload_policy_id  BIGINT UNSIGNED NOT NULL COMMENT '업로드 정책 ID',
  owner_user_id     BIGINT UNSIGNED NOT NULL COMMENT '업로드한 사용자 ID',
  course_id         BIGINT UNSIGNED NULL     COMMENT '업로드 대상 과목 ID(과목 밖은 NULL)',
  file_id           BIGINT UNSIGNED NOT NULL COMMENT '업로드로 생성된 파일 ID',
  client_upload_key VARCHAR(100)    NULL     COMMENT '클라이언트 멱등 업로드 키',
  upload_state      VARCHAR(12)     NOT NULL DEFAULT 'quarantined' COMMENT '업로드 상태(quarantined|clean|attached|rejected|deleted|expired)',
  rejection_code    VARCHAR(50)     NULL     COMMENT '거부 사유 코드(E_USER_NOT_SAME 등)',
  expires_at        DATETIME(3)     NOT NULL COMMENT '임시 업로드 만료 일시',
  attached_at       DATETIME(3)     NULL     COMMENT '첨부 확정 일시',
  created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at        DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (upload_id),
  UNIQUE KEY uq_upload_sessions_owner_key (owner_user_id, client_upload_key),
  KEY idx_upload_sessions_policy (upload_policy_id),
  KEY idx_upload_sessions_course (course_id),
  KEY idx_upload_sessions_file (file_id),
  KEY idx_upload_sessions_state_expires (upload_state, expires_at),
  CONSTRAINT fk_upload_sessions_upload_policies FOREIGN KEY (upload_policy_id) REFERENCES upload_policies (upload_policy_id),
  CONSTRAINT fk_upload_sessions_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_upload_sessions_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_upload_sessions_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='임시 업로드 세션';

-- ---------------------------------------------------------------
-- 5. 첨부 그룹
-- ---------------------------------------------------------------
CREATE TABLE attachment_groups (
  attachment_group_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '첨부 그룹 ID',
  group_title         VARCHAR(200)    NULL     COMMENT '첨부 그룹 표시명',
  created_by_user_id  BIGINT UNSIGNED NULL     COMMENT '첨부 그룹 생성자 ID',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at          DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (attachment_group_id),
  KEY idx_attachment_groups_created_by (created_by_user_id),
  CONSTRAINT fk_attachment_groups_users FOREIGN KEY (created_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='첨부 그룹';

-- ---------------------------------------------------------------
-- 6. 첨부
-- ---------------------------------------------------------------
CREATE TABLE attachments (
  attachment_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '첨부 ID',
  attachment_group_id BIGINT UNSIGNED NOT NULL COMMENT '첨부 그룹 ID',
  file_id             BIGINT UNSIGNED NOT NULL COMMENT '파일 ID',
  upload_id           BIGINT UNSIGNED NULL     COMMENT '출처 업로드 세션 ID',
  display_name        VARCHAR(255)    NULL     COMMENT '첨부 표시명(원본명과 다를 때)',
  sort_order          SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '첨부 정렬 순서',
  created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '첨부 일시',
  updated_at          DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (attachment_id),
  UNIQUE KEY uq_attachments_group_sort (attachment_group_id, sort_order),
  KEY idx_attachments_file (file_id),
  KEY idx_attachments_upload (upload_id),
  CONSTRAINT fk_attachments_attachment_groups FOREIGN KEY (attachment_group_id) REFERENCES attachment_groups (attachment_group_id),
  CONSTRAINT fk_attachments_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_attachments_upload_sessions FOREIGN KEY (upload_id) REFERENCES upload_sessions (upload_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='첨부';

-- ---------------------------------------------------------------
-- 7. 파일함 항목 (폴더/파일 트리 + 휴지통)
-- ---------------------------------------------------------------
CREATE TABLE filebox_items (
  filebox_item_id  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '파일함 항목 ID',
  owner_user_id    BIGINT UNSIGNED NOT NULL COMMENT '소유 사용자 ID',
  parent_item_id   BIGINT UNSIGNED NULL     COMMENT '상위 폴더 항목 ID(루트는 NULL)',
  item_kind        VARCHAR(10)     NOT NULL DEFAULT 'file' COMMENT '항목 종류(folder|file)',
  item_name        VARCHAR(255)    NOT NULL COMMENT '항목 이름(파일명 또는 폴더명)',
  file_id          BIGINT UNSIGNED NULL     COMMENT '파일 ID(폴더는 NULL)',
  reference_count  INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '연결 활동 참조 수 캐시',
  item_state       VARCHAR(10)     NOT NULL DEFAULT 'active' COMMENT '상태(active|trashed|purged)',
  trashed_at       DATETIME(3)     NULL     COMMENT '휴지통 이동 일시',
  purged_at        DATETIME(3)     NULL     COMMENT '영구 삭제 일시',
  item_version     INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '이동·이름변경 낙관적 버전',
  parent_key       BIGINT UNSIGNED GENERATED ALWAYS AS (IFNULL(parent_item_id, 0)) STORED COMMENT '상위 폴더 정규화 키(루트=0)',
  active_item_name VARCHAR(255)    GENERATED ALWAYS AS (CASE WHEN item_state = 'active' THEN item_name ELSE NULL END) STORED COMMENT '활성 항목 이름(중복 방지용)',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at       DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (filebox_item_id),
  UNIQUE KEY uq_filebox_items_owner_parent_name (owner_user_id, parent_key, active_item_name),
  KEY idx_filebox_items_parent (parent_item_id),
  KEY idx_filebox_items_file (file_id),
  KEY idx_filebox_items_owner_state (owner_user_id, item_state),
  CONSTRAINT ck_filebox_items_kind CHECK ((item_kind = 'file' AND file_id IS NOT NULL) OR (item_kind = 'folder' AND file_id IS NULL)),
  CONSTRAINT fk_filebox_items_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_filebox_items_filebox_items FOREIGN KEY (parent_item_id) REFERENCES filebox_items (filebox_item_id),
  CONSTRAINT fk_filebox_items_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='파일함 항목';

-- ---------------------------------------------------------------
-- 8. 일괄 다운로드(ZIP) 작업
-- ---------------------------------------------------------------
CREATE TABLE zip_jobs (
  zip_job_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '압축 작업 ID',
  owner_user_id   BIGINT UNSIGNED NOT NULL COMMENT '요청 사용자 ID',
  item_set_hash   CHAR(64)        NOT NULL COMMENT '선택 항목 집합 해시',
  item_count      INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '대상 항목 수',
  total_bytes     BIGINT UNSIGNED NULL     COMMENT '대상 총 바이트',
  zip_state       VARCHAR(12)     NOT NULL DEFAULT 'requested' COMMENT '작업 상태(requested|building|ready|failed|expired)',
  result_file_id  BIGINT UNSIGNED NULL     COMMENT '생성된 ZIP 파일 ID',
  failure_reason  VARCHAR(200)    NULL     COMMENT '실패 사유',
  expires_at      DATETIME(3)     NULL     COMMENT '생성물 만료 일시',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '요청 일시',
  updated_at      DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (zip_job_id),
  KEY idx_zip_jobs_owner_hash (owner_user_id, item_set_hash),
  KEY idx_zip_jobs_state (zip_state, expires_at),
  KEY idx_zip_jobs_result_file (result_file_id),
  CONSTRAINT fk_zip_jobs_users FOREIGN KEY (owner_user_id) REFERENCES users (user_id),
  CONSTRAINT fk_zip_jobs_files FOREIGN KEY (result_file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='일괄 다운로드 작업';

CREATE TABLE zip_job_items (
  zip_job_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '압축 작업 대상 ID',
  zip_job_id      BIGINT UNSIGNED NOT NULL COMMENT '압축 작업 ID',
  filebox_item_id BIGINT UNSIGNED NOT NULL COMMENT '파일함 항목 ID',
  sort_order      SMALLINT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'ZIP 내 정렬 순서',
  created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (zip_job_item_id),
  UNIQUE KEY uq_zip_job_items_job_item (zip_job_id, filebox_item_id),
  KEY idx_zip_job_items_filebox_item (filebox_item_id),
  CONSTRAINT fk_zip_job_items_zip_jobs FOREIGN KEY (zip_job_id) REFERENCES zip_jobs (zip_job_id),
  CONSTRAINT fk_zip_job_items_filebox_items FOREIGN KEY (filebox_item_id) REFERENCES filebox_items (filebox_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='일괄 다운로드 대상';

-- ---------------------------------------------------------------
-- 9. 문서 변환 작업
-- ---------------------------------------------------------------
CREATE TABLE conversion_jobs (
  conversion_job_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '변환 작업 ID',
  file_id               BIGINT UNSIGNED NOT NULL COMMENT '파일 ID',
  file_version_no       INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '변환 대상 파일 버전',
  requested_by_user_id  BIGINT UNSIGNED NULL     COMMENT '변환을 요청한 사용자 ID',
  conv_type             VARCHAR(20)     NOT NULL DEFAULT 'img' COMMENT '변환 타입(img 등)',
  conv_locale           VARCHAR(10)     NOT NULL DEFAULT 'ko_KR' COMMENT '변환 로케일',
  converted_doc_key     CHAR(64)        NULL     COMMENT '변환 결과 문서 키',
  conversion_state      VARCHAR(12)     NOT NULL DEFAULT 'requested' COMMENT '변환 상태(requested|converting|ready|failed|expired)',
  page_count            INT UNSIGNED    NULL     COMMENT '변환된 쪽 수',
  error_code            VARCHAR(50)     NULL     COMMENT '변환 실패 코드',
  started_at            DATETIME(3)     NULL     COMMENT '변환 시작 일시',
  completed_at          DATETIME(3)     NULL     COMMENT '변환 완료 일시',
  expires_at            DATETIME(3)     NULL     COMMENT '변환 결과 만료 일시',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at            DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (conversion_job_id),
  UNIQUE KEY uq_conversion_jobs_converted_doc_key (converted_doc_key),
  KEY idx_conversion_jobs_file_state (file_id, conversion_state),
  KEY idx_conversion_jobs_requested_by (requested_by_user_id),
  CONSTRAINT fk_conversion_jobs_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_conversion_jobs_users FOREIGN KEY (requested_by_user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='문서 변환 작업';

-- ---------------------------------------------------------------
-- 10. 미리보기 뷰어 세션
-- ---------------------------------------------------------------
CREATE TABLE preview_sessions (
  preview_session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '미리보기 세션 ID',
  file_id            BIGINT UNSIGNED NOT NULL COMMENT '파일 ID',
  conversion_job_id  BIGINT UNSIGNED NULL     COMMENT '변환 작업 ID',
  user_id            BIGINT UNSIGNED NOT NULL COMMENT '열람 사용자 ID',
  file_version_no    INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '열람 대상 파일 버전',
  viewer_session_key CHAR(64)        NOT NULL COMMENT '뷰어 세션 키(추측 불가 값)',
  annot_token_hash   CHAR(64)        NULL     COMMENT '주석 토큰 해시',
  expires_at         DATETIME(3)     NOT NULL COMMENT '세션 만료 일시',
  revoked_at         DATETIME(3)     NULL     COMMENT '세션 무효화 일시',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '발급 일시',
  updated_at         DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (preview_session_id),
  UNIQUE KEY uq_preview_sessions_viewer_session_key (viewer_session_key),
  KEY idx_preview_sessions_user (user_id),
  KEY idx_preview_sessions_file (file_id),
  KEY idx_preview_sessions_conversion (conversion_job_id),
  CONSTRAINT fk_preview_sessions_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_preview_sessions_conversion_jobs FOREIGN KEY (conversion_job_id) REFERENCES conversion_jobs (conversion_job_id),
  CONSTRAINT fk_preview_sessions_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='미리보기 뷰어 세션';

-- ---------------------------------------------------------------
-- 11. 콘텐츠 썸네일
-- ---------------------------------------------------------------
CREATE TABLE file_thumbnails (
  thumbnail_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '썸네일 ID',
  content_catalog_id BIGINT UNSIGNED NOT NULL COMMENT '콘텐츠 카탈로그 번호(cid)',
  content_item_key   CHAR(20)        NOT NULL COMMENT '콘텐츠 항목 키(id)',
  variant            VARCHAR(20)     NOT NULL DEFAULT 'thumb_1' COMMENT '썸네일 변형(thumb_1 등)',
  file_id            BIGINT UNSIGNED NOT NULL COMMENT '썸네일 파일 ID',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at         DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (thumbnail_id),
  UNIQUE KEY uq_file_thumbnails_content_variant (content_catalog_id, content_item_key, variant),
  KEY idx_file_thumbnails_file (file_id),
  CONSTRAINT fk_file_thumbnails_files FOREIGN KEY (file_id) REFERENCES files (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='콘텐츠 썸네일';

-- ---------------------------------------------------------------
-- 12. 첨부 AI 요약 (ASTRA 서포터즈 요약)
-- ---------------------------------------------------------------
CREATE TABLE ai_file_summaries (
  ai_file_summary_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'AI 요약 ID',
  file_id            BIGINT UNSIGNED NOT NULL COMMENT '파일 ID',
  course_id          BIGINT UNSIGNED NULL     COMMENT '요약 대상 과목 ID',
  summary_state      VARCHAR(12)     NOT NULL DEFAULT 'pending' COMMENT '요약 상태(pending|ready|failed)',
  summary_markdown   MEDIUMTEXT      NULL     COMMENT '요약 본문(Markdown)',
  model_name         VARCHAR(100)    NULL     COMMENT '생성 모델 이름',
  prompt_version     VARCHAR(30)     NULL     COMMENT '프롬프트 버전',
  source_sha256      CHAR(64)        NULL     COMMENT '요약 대상 원본 SHA-256',
  generated_at       DATETIME(3)     NULL     COMMENT '요약 생성 일시',
  created_at         DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at         DATETIME(3)     NULL     ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (ai_file_summary_id),
  UNIQUE KEY uq_ai_file_summaries_file (file_id),
  KEY idx_ai_file_summaries_course (course_id),
  CONSTRAINT fk_ai_file_summaries_files FOREIGN KEY (file_id) REFERENCES files (file_id),
  CONSTRAINT fk_ai_file_summaries_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='첨부 AI 요약';

-- ===== 통합 FK =====
ALTER TABLE notifications ADD CONSTRAINT fk_notifications_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id);

SET FOREIGN_KEY_CHECKS = 1;
