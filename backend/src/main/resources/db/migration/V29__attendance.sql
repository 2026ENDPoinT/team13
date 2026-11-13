-- 30 learning: 출석 차시·출석부, 스마트출석(PIN/QR), 과목 그룹, 강의계획서(docs/adr/0030-attendance-and-smart-rounds.md)
-- 수정·삭제는 If-Match version으로 막는다(ADR-0011과 같은 방식). 행이 아직 없는 대상(출석 기록, 강의계획서, 강사 정보)은 version 0이다.

-- 1. 출석 차시: 수정 version과 같은 주차·차시의 중복 방지(SC-05). 보강 차시는 원 차시와 번호가 같을 수 있어 보강 여부까지 묶는다.
ALTER TABLE attendance_sessions
  ADD COLUMN row_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '수정 낙관적 버전(If-Match로 확인)' AFTER makeup_yn,
  ADD UNIQUE KEY uq_attendance_sessions_week_session_makeup (course_week_id, session_no, makeup_yn);

-- 2. 스마트출석 회차: 회차 비밀값, 지각 기준 시각, QR 교체 주기, 마감 처리, 차시당 진행 중 회차 하나.
-- PIN과 QR token은 회차 비밀값에서 서버가 만든다. 비밀값은 회차를 닫거나 취소하면 지운다.
-- 진행 중(open) 회차는 차시마다 하나다. MySQL에는 부분 유일 인덱스가 없어 생성 열과 UNIQUE로 구현한다.
ALTER TABLE smart_attendance_rounds
  ADD COLUMN round_secret     BINARY(32)        NULL COMMENT 'PIN·QR token을 만드는 회차 비밀값(닫거나 취소하면 지운다)' AFTER pin_length,
  ADD COLUMN qr_rotation_sec  SMALLINT UNSIGNED NULL COMMENT 'QR token 교체 주기(초, qr 방식만)' AFTER round_secret,
  ADD COLUMN late_at          DATETIME(3)       NOT NULL COMMENT '이 시각부터 제출하면 지각(개시 시각 + 지각 기준)' AFTER opened_at,
  ADD COLUMN closed_at        DATETIME(3)       NULL COMMENT '마감·취소 처리 일시' AFTER ends_at,
  ADD COLUMN closed_by        BIGINT UNSIGNED   NULL COMMENT '마감·취소한 사용자 ID' AFTER closed_at,
  ADD COLUMN open_session_key BIGINT UNSIGNED GENERATED ALWAYS AS (IF(state = 'open', attendance_session_id, NULL)) STORED COMMENT '진행 중 회차의 출석 차시(차시당 하나 부분 유일 제약용)' AFTER state,
  ADD UNIQUE KEY uq_smart_attendance_rounds_open_session_key (open_session_key),
  ADD KEY idx_smart_attendance_rounds_closed_by (closed_by),
  ADD CONSTRAINT fk_smart_attendance_rounds_users_closed_by FOREIGN KEY (closed_by) REFERENCES users (user_id);

ALTER TABLE smart_attendance_attempts
  MODIFY COLUMN result VARCHAR(20) NOT NULL COMMENT '결과(ok|duplicate|bad_pin|bad_qr|locked|expired|closed|cancelled|not_target)';

-- 3. 출석 기록: 수정 version과 기록을 만든 스마트출석 회차. 자동 판정(online, smart)도 상태를 바꾸면 version을 올린다.
ALTER TABLE attendance_records
  ADD COLUMN smart_attendance_round_id BIGINT UNSIGNED NULL COMMENT '이 기록을 만든 스마트출석 회차 ID(source smart일 때만)' AFTER source,
  ADD COLUMN row_version               INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '수정 낙관적 버전(If-Match로 확인)' AFTER rule_version,
  ADD KEY idx_attendance_records_smart_attendance_round_id (smart_attendance_round_id),
  ADD CONSTRAINT fk_attendance_records_smart_attendance_rounds FOREIGN KEY (smart_attendance_round_id) REFERENCES smart_attendance_rounds (smart_attendance_round_id);

-- 4. 담당자가 고친 출석 상태의 이력과 사유. 감사 기록(audit_events)은 자유 텍스트를 받지 않으므로 사유는 여기에만 남는다.
CREATE TABLE attendance_record_changes (
  attendance_record_change_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '출석 상태 변경 이력 ID',
  attendance_record_id        BIGINT UNSIGNED NOT NULL COMMENT '출석 기록 ID',
  previous_status             VARCHAR(20)     NULL COMMENT '바꾸기 전 상태(기록이 없었으면 NULL)',
  previous_source             VARCHAR(20)     NULL COMMENT '바꾸기 전 산출 방식(smart|online|manual)',
  new_status                  VARCHAR(20)     NOT NULL COMMENT '바꾼 상태(출석|지각|결석|공결)',
  reason                      VARCHAR(200)    NOT NULL COMMENT '변경 사유(일반 텍스트)',
  changed_by                  BIGINT UNSIGNED NOT NULL COMMENT '바꾼 담당자 사용자 ID',
  changed_at                  DATETIME(3)     NOT NULL COMMENT '변경 일시(DB 시계)',
  PRIMARY KEY (attendance_record_change_id),
  KEY idx_attendance_record_changes_record (attendance_record_id, attendance_record_change_id),
  KEY idx_attendance_record_changes_changed_by (changed_by),
  CONSTRAINT fk_attendance_record_changes_attendance_records FOREIGN KEY (attendance_record_id) REFERENCES attendance_records (attendance_record_id),
  CONSTRAINT fk_attendance_record_changes_users FOREIGN KEY (changed_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='출석 상태 변경 이력';

-- 5. 과목별 출석 정책. 학교가 정하지 않은 값을 과목 설정으로 둔다. 행이 없으면 앱의 기본값(ADR-0030)을 쓴다.
CREATE TABLE attendance_settings (
  course_id          BIGINT UNSIGNED   NOT NULL COMMENT '과목 ID(catalog.courses)',
  late_after_minutes SMALLINT UNSIGNED NOT NULL COMMENT '스마트출석 개시 뒤 이 시간(분)이 지나면 지각',
  round_minutes      SMALLINT UNSIGNED NOT NULL COMMENT '스마트출석 회차의 기본 진행 시간(분)',
  lates_per_absence  TINYINT UNSIGNED  NOT NULL COMMENT '지각 몇 번을 결석 한 번으로 환산하는지(0이면 환산하지 않음)',
  row_version        INT UNSIGNED      NOT NULL DEFAULT 0 COMMENT '수정 낙관적 버전(If-Match로 확인, 설정 전 0)',
  updated_by         BIGINT UNSIGNED   NULL COMMENT '마지막으로 바꾼 담당자 사용자 ID',
  created_at         DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at         DATETIME(3)       NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (course_id),
  KEY idx_attendance_settings_updated_by (updated_by),
  CONSTRAINT fk_attendance_settings_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_attendance_settings_users FOREIGN KEY (updated_by) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='과목 출석 정책';

-- 6. 과목 그룹과 강의계획서의 수정 version.
ALTER TABLE course_groups
  ADD COLUMN row_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '수정 낙관적 버전(If-Match로 확인)' AFTER sort_order,
  ADD UNIQUE KEY uq_course_groups_course_id_group_name (course_id, group_name);

-- 강의계획서 행은 첫 저장 때 만든다. 아직 내용을 저장하지 않은 행은 version 0이다.
ALTER TABLE course_syllabi
  ADD COLUMN row_version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '수정 낙관적 버전(If-Match로 확인, 저장 전 0)' AFTER attachment_group_id,
  ADD COLUMN updated_by  BIGINT UNSIGNED NULL COMMENT '마지막으로 바꾼 담당자 사용자 ID' AFTER row_version,
  ADD KEY idx_course_syllabi_updated_by (updated_by),
  ADD CONSTRAINT fk_course_syllabi_users FOREIGN KEY (updated_by) REFERENCES users (user_id);

ALTER TABLE syllabus_instructors
  ADD COLUMN row_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '수정 낙관적 버전(If-Match로 확인)' AFTER sort_order;
