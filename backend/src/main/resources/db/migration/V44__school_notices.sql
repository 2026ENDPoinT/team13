-- 30 learning: 학교 e-Class 강의실 공지(docs/adr/0043-school-content.md).
-- 학교 강의실 공지 목록(학교 쪽 읽음 표시를 바꾸지 않는 목록 화면)을 그 과목 수강생의 학교 session으로 읽어 둔다. 공지 본문은 저장하지
-- 않는다. 학생이 STack에서 공지를 열 때 그 학생의 학교 session으로 학교 상세를 읽는다(학교에서 그 학생이 읽은 것으로 표시된다). 첨부는
-- 학교 파일(school_files, school_type = 'notice')로 둔다.

-- 학교 강의실 공지 하나. 학교 공지 번호(ARTL_NUM)로 과목 안에서 하나다. 학교 목록에서 사라지면 지운다.
CREATE TABLE school_notices (
  school_notice_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '학교 공지 ID',
  course_id        BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  school_item_id   VARCHAR(40)     NOT NULL COMMENT '학교 공지 번호(ARTL_NUM)',
  title            VARCHAR(300)    NOT NULL COMMENT '제목',
  excerpt          VARCHAR(300)    NULL COMMENT '목록의 본문 앞부분',
  posted_at        DATETIME(3)     NULL COMMENT '게시 일시(학교 화면, 연도는 과목 학년도)',
  view_count       INT UNSIGNED    NULL COMMENT '학교 목록의 조회수',
  expired_yn       TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '공지 만료 표시 여부',
  sort_order       INT UNSIGNED    NOT NULL COMMENT '학교 목록 순서(0부터)',
  school_url       VARCHAR(500)    NOT NULL COMMENT '학교 e-Class에서 여는 주소',
  listed_at        DATETIME(3)     NOT NULL COMMENT '학교 목록에서 마지막으로 본 일시',
  created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  PRIMARY KEY (school_notice_id),
  UNIQUE KEY uq_school_notices_course_item (course_id, school_item_id),
  CONSTRAINT fk_school_notices_courses FOREIGN KEY (course_id) REFERENCES courses (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 강의실 공지';

-- 학생 본인의 학교 읽음 표시. 그 학생의 학교 공지 목록(안 읽음 표시)에서 읽고, 그 학생이 STack에서 공지를 열면 읽음으로 바꾼다.
-- 내 과목 목록과 메인 화면의 안 읽은 공지 수에 더한다. 공지를 열어서 세지 않는다.
CREATE TABLE school_notice_states (
  school_notice_id BIGINT UNSIGNED NOT NULL COMMENT '학교 공지 ID',
  user_id          BIGINT UNSIGNED NOT NULL COMMENT '학생 사용자 ID',
  read_yn          TINYINT(1)      NOT NULL COMMENT '학교에서 읽음 여부',
  synced_at        DATETIME(3)     NOT NULL COMMENT '마지막으로 맞춘 일시',
  PRIMARY KEY (school_notice_id, user_id),
  KEY idx_school_notice_states_user (user_id, read_yn),
  CONSTRAINT fk_school_notice_states_school_notices FOREIGN KEY (school_notice_id)
    REFERENCES school_notices (school_notice_id) ON DELETE CASCADE,
  CONSTRAINT fk_school_notice_states_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 공지 학생 읽음 표시';

-- 학생·과목별로 학교 공지 목록을 마지막으로 읽은 시각. 목록(읽음 표시 포함)은 이 간격 안에 다시 읽지 않는다.
CREATE TABLE school_notice_lists (
  course_id BIGINT UNSIGNED NOT NULL COMMENT '과목 ID',
  user_id   BIGINT UNSIGNED NOT NULL COMMENT '학생 사용자 ID',
  listed_at DATETIME(3)     NOT NULL COMMENT '학교 공지 목록을 마지막으로 읽은 일시',
  PRIMARY KEY (course_id, user_id),
  KEY idx_school_notice_lists_user (user_id),
  CONSTRAINT fk_school_notice_lists_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_school_notice_lists_users FOREIGN KEY (user_id) REFERENCES users (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='학교 공지 목록 읽기';
