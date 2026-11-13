-- 30 learning: 학교 e-Class 강의실에서 가져온 주차와 학습활동 목록(docs/adr/0042-school-activity-import.md).
-- 학번·학교 비밀번호로 로그인할 때 그 학생의 학교 session으로 과목마다 강의실 첫 화면을 읽어 둔다. 목록(유형, 제목, 주차, 기간,
-- 학생 본인 완료 표시)만 두고 파일, 영상, 제출물, 본문은 가져오지 않는다. 활동을 열면 학교 e-Class로 간다. 학교 화면이 원천인 사본이라
-- 학교 화면에서 사라진 행은 지운다(deleted_at으로 남기지 않는다). 이 학생 본인의 완료 표시는 그 학생이 로그인할 때만 바뀐다.

-- 학교 강의실의 주차(비정규는 회차). 기간은 학교 화면에 있을 때만 있다.
CREATE TABLE school_course_weeks (
  school_course_week_id BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '학교 주차 ID',
  course_id             BIGINT UNSIGNED  NOT NULL COMMENT '과목 ID',
  week_no               INT UNSIGNED NOT NULL COMMENT '주차 번호(비정규는 회차)',
  starts_on             DATE             NULL COMMENT '주 시작일(학교 화면)',
  ends_on               DATE             NULL COMMENT '주 끝일(학교 화면, 포함)',
  synced_at             DATETIME(3)      NOT NULL COMMENT '학교 화면에서 마지막으로 읽은 일시',
  PRIMARY KEY (school_course_week_id),
  UNIQUE KEY uq_school_course_weeks_course_week (course_id, week_no),
  CONSTRAINT fk_school_course_weeks_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 강의실 주차';

-- 학교 강의실의 학습활동 행. 학교 활동 ID(링크의 n)와 메뉴 ID(링크의 m)로 과목 안에서 하나다.
CREATE TABLE school_activities (
  school_activity_id BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT COMMENT '학교 활동 ID',
  course_id          BIGINT UNSIGNED   NOT NULL COMMENT '과목 ID',
  school_type        VARCHAR(30)       NOT NULL COMMENT '학교 메뉴 ID: lecture_weeks|lecture_material|report|test|survey|discuss|zoom|clicker 등',
  school_kind        VARCHAR(30)       NULL COMMENT '세부 유형(행 class): report1|report2|test1|test2|test3 등',
  school_item_id     VARCHAR(40)       NOT NULL COMMENT '학교 활동 번호(링크의 n)',
  week_no            INT UNSIGNED NOT NULL COMMENT '주차 번호(비정규는 회차)',
  sort_order         INT UNSIGNED      NOT NULL COMMENT '학교 화면의 과목 안 순서(0부터)',
  title              VARCHAR(300)      NOT NULL COMMENT '제목',
  open_at            DATETIME(3)       NULL COMMENT '시작 일시(화면에 날짜가 있을 때)',
  close_at           DATETIME(3)       NULL COMMENT '마감 일시(화면에 날짜가 있을 때)',
  expired_yn         TINYINT(1)        NOT NULL DEFAULT 0 COMMENT '마감 만료 표시 여부',
  attendance_label   VARCHAR(40)       NULL COMMENT '출석 표시(영상, 예: 3 주 1차시)',
  learning_time_sec  INT UNSIGNED      NULL COMMENT '학습시간(영상, 초)',
  offline_yn         TINYINT(1)        NOT NULL DEFAULT 0 COMMENT '오프라인 시험 표시 여부',
  school_url         VARCHAR(500)      NOT NULL COMMENT '학교 e-Class에서 여는 주소(과목을 묶고 활동으로 넘기는 연결 주소)',
  synced_at          DATETIME(3)       NOT NULL COMMENT '학교 화면에서 마지막으로 읽은 일시',
  created_at         DATETIME(3)       NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (school_activity_id),
  UNIQUE KEY uq_school_activities_course_item (course_id, school_type, school_item_id),
  KEY idx_school_activities_course_order (course_id, week_no, sort_order),
  CONSTRAINT fk_school_activities_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 강의실 학습활동';

-- 학생 본인의 완료 표시(학교 화면의 체크). 그 학생의 학교 session으로 읽은 값만 둔다.
CREATE TABLE school_activity_states (
  school_activity_state_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학교 활동 상태 ID',
  school_activity_id       BIGINT UNSIGNED NOT NULL COMMENT '학교 활동 ID',
  user_id                  BIGINT UNSIGNED NOT NULL COMMENT '학생 사용자 ID',
  done_yn                  TINYINT(1)      NOT NULL COMMENT '완료 표시 여부',
  synced_at                DATETIME(3)     NOT NULL COMMENT '학교 화면에서 마지막으로 읽은 일시',
  PRIMARY KEY (school_activity_state_id),
  UNIQUE KEY uq_school_activity_states_activity_user (school_activity_id, user_id),
  KEY idx_school_activity_states_user (user_id),
  CONSTRAINT fk_school_activity_states_school_activities FOREIGN KEY (school_activity_id)
    REFERENCES school_activities (school_activity_id) ON DELETE CASCADE,
  CONSTRAINT fk_school_activity_states_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 학습활동 학생 상태';

-- 학생별 마지막 가져오기. 같은 학생이 짧은 간격 안에 다시 로그인하면 학교 강의실을 다시 읽지 않는다.
CREATE TABLE school_activity_imports (
  user_id       BIGINT UNSIGNED NOT NULL COMMENT '학생 사용자 ID',
  started_at    DATETIME(3)     NOT NULL COMMENT '마지막 가져오기 시작 일시',
  finished_at   DATETIME(3)     NULL COMMENT '마지막 가져오기 끝 일시(진행 중이면 NULL)',
  courses_read  SMALLINT UNSIGNED NULL COMMENT '읽은 과목 수',
  courses_failed SMALLINT UNSIGNED NULL COMMENT '읽지 못한 과목 수(시간 초과로 남긴 과목 포함)',
  PRIMARY KEY (user_id),
  CONSTRAINT fk_school_activity_imports_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 학습활동 가져오기';
