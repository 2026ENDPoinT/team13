-- 20 catalog: 알림 생성, 예약, 중복 방지(docs/adr/0027-notifications-in-producer-transaction.md)
-- 알림은 공지 게시, 자료 등록, 과제 공개·점수 공개를 저장하는 transaction 안에서 만든다. 대상이 미래에 보이면 알림 사건만 남기고,
-- 그 시각이 지나면 worker가 그때의 과목 구성원에게 알림 행을 만든다. 같은 사건의 알림은 받는 사람마다 하나다(SC-05).

-- 유형 값이 공개 API(catalog.api.NotificationKind)와 같아지도록 설명을 바꾸고, 재시도·worker 중복에도 한 사람에게 하나만 남도록 유일 키를 둔다.
-- 유일 키는 (유형, 대상) 앞부분으로 대상의 알림을 한꺼번에 지울 때도 쓴다.
ALTER TABLE notifications
  MODIFY COLUMN notification_type VARCHAR(24) NOT NULL COMMENT '알림 유형: COURSE_NOTICE|LECTURE_MATERIAL|ASSIGNMENT_OPENED|ASSIGNMENT_GRADED|QUESTION_ANSWERED|MESSAGE_RECEIVED',
  MODIFY COLUMN target_kind VARCHAR(20) NULL COMMENT '연결 대상 종류: ACTIVITY|ARTICLE|MESSAGE',
  ADD UNIQUE KEY uq_notifications_type_target_user (notification_type, target_ref_id, user_id);

CREATE TABLE notification_events (
  notification_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '알림 사건 ID',
  notification_type     VARCHAR(24)     NOT NULL COMMENT '알림 유형(notifications.notification_type과 같은 값)',
  target_ref_id         BIGINT UNSIGNED NOT NULL COMMENT '연결 대상 ID(공지 게시글, 학습활동. 다른 모듈의 행이라 값으로만 저장, FK 없음)',
  course_id             BIGINT UNSIGNED NOT NULL COMMENT '받는 사람을 정하는 과목 ID',
  activity_id           BIGINT UNSIGNED NULL     COMMENT '관련 학습활동 ID(notifications.activity_id와 같이 learning_activities를 가리킨다)',
  audience_role         VARCHAR(12)     NOT NULL COMMENT '받는 사람의 과목 내 역할: STUDENT|INSTRUCTOR',
  message_text          VARCHAR(500)    NOT NULL COMMENT '알림 문장(서버 생성)',
  visible_at            DATETIME(3)     NOT NULL COMMENT '대상이 받는 사람에게 보이기 시작하는 일시(DB 시계). 이 시각 뒤에 알림을 만든다',
  delivered_at          DATETIME(3)     NULL     COMMENT '알림 행을 만든 일시(만들기 전이면 NULL)',
  created_at            DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '생성 일시',
  updated_at            DATETIME(3)     NULL ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '수정 일시',
  PRIMARY KEY (notification_event_id),
  UNIQUE KEY uq_notification_events_type_target (notification_type, target_ref_id),
  KEY idx_notification_events_pending (delivered_at, visible_at),
  KEY idx_notification_events_course (course_id),
  KEY idx_notification_events_activity (activity_id),
  CONSTRAINT fk_notification_events_courses FOREIGN KEY (course_id) REFERENCES courses (course_id),
  CONSTRAINT fk_notification_events_learning_activities FOREIGN KEY (activity_id) REFERENCES learning_activities (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='알림 사건(과목 구성원에게 보내는 알림 한 건의 원본과 예약)';
