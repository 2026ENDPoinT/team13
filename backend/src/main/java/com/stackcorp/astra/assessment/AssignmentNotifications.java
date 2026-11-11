package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivityInput;

import org.springframework.stereotype.Component;

/**
 * 과제 알림(ADR-0027). 과제를 만들거나 고치는 transaction 안에서 과제의 지금 상태에 알림을 맞춘다.
 * 공개한 과제는 시작 일시에 수강생에게 "새 과제"를 보내고, 점수를 공개했으면 과제가 학생에게 보이는 때에 "점수 공개"를 보낸다.
 * 비공개로 돌리거나 점수 공개를 거두면 그 알림과 예약을 거둔다. 같은 과제를 다시 저장해도 이미 받은 사람에게 다시 보내지 않는다.
 */
@Component
class AssignmentNotifications {

	private final Notifications notifications;

	AssignmentNotifications(Notifications notifications) {
		this.notifications = notifications;
	}

	void sync(long courseId, long activityId, AssignmentInput input) {
		ReportActivityInput activity = input.activity();
		LocalDateTime visibleAt = activity.openAt();
		if (activity.published()) {
			this.notifications.publish(new CourseNotification(NotificationKind.ASSIGNMENT_OPENED, courseId,
					CourseRole.STUDENT, activityId, activity.title(), visibleAt));
		}
		else {
			this.notifications.withdraw(NotificationKind.ASSIGNMENT_OPENED, activityId);
		}
		if (activity.published() && input.scoresReleased()) {
			this.notifications.publish(new CourseNotification(NotificationKind.ASSIGNMENT_GRADED, courseId,
					CourseRole.STUDENT, activityId, activity.title(), visibleAt));
		}
		else {
			this.notifications.withdraw(NotificationKind.ASSIGNMENT_GRADED, activityId);
		}
	}

}
