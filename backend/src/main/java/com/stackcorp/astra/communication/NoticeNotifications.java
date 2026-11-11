package com.stackcorp.astra.communication;

import java.time.LocalDateTime;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;

import org.springframework.stereotype.Component;

/**
 * 강의실 공지의 알림(ADR-0027). 공지를 쓰거나 고치거나 지우는 transaction 안에서 공지의 지금 상태에 알림을 맞춘다.
 * 게시 일시가 있으면 그 시각에 수강생에게 보내고(예약 공지는 그 시각에), 임시 공지가 되거나 지우면 보낸 알림과 예약을 거둔다.
 */
@Component
class NoticeNotifications {

	private final Notifications notifications;

	NoticeNotifications(Notifications notifications) {
		this.notifications = notifications;
	}

	/**
	 * @param publishedAt 게시 일시. 임시 공지면 null
	 */
	void sync(long courseId, long noticeId, String title, LocalDateTime publishedAt) {
		if (publishedAt == null) {
			withdraw(noticeId);
			return;
		}
		this.notifications.publish(new CourseNotification(NotificationKind.COURSE_NOTICE, courseId, CourseRole.STUDENT,
				noticeId, title, publishedAt));
	}

	void withdraw(long noticeId) {
		this.notifications.withdraw(NotificationKind.COURSE_NOTICE, noticeId);
	}

}
