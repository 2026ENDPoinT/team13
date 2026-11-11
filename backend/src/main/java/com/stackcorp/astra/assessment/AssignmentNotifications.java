package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivityInput;

import org.springframework.stereotype.Component;

/**
 * 과제 알림(ADR-0027). 과제를 만들거나 고치거나 점수 공개를 바꾸는 transaction 안에서 과제의 지금 상태에 알림을 맞춘다.
 * 공개한 과제는 시작 일시에 수강생에게 "새 과제"를 보내고, 점수를 공개했으면 과제가 학생에게 보이는 때에 "점수 공개"를 보낸다.
 * 비공개로 돌리거나 점수 공개를 거두면 그 알림과 예약을 거둔다. 같은 과제를 다시 저장해도 이미 받은 사람에게 다시 보내지 않는다.
 * 대상 ID는 화면이 과제를 여는 과제 ID다(ADR-0036).
 */
@Component
class AssignmentNotifications {

	private final Notifications notifications;

	AssignmentNotifications(Notifications notifications) {
		this.notifications = notifications;
	}

	void sync(long courseId, long assignmentId, AssignmentInput input) {
		ReportActivityInput activity = input.activity();
		if (activity.published()) {
			this.notifications.publish(new CourseNotification(NotificationKind.ASSIGNMENT_OPENED, courseId,
					CourseRole.STUDENT, assignmentId, activity.title(), activity.openAt()));
		}
		else {
			this.notifications.withdraw(NotificationKind.ASSIGNMENT_OPENED, assignmentId);
		}
		graded(courseId, assignmentId, activity.published() && input.scoresReleased(), activity.title(),
				activity.openAt());
	}

	/**
	 * 점수 공개 알림만 맞춘다.
	 * @param released 공개한 과제이고 점수를 공개했는지
	 * @param visibleAt 과제가 학생에게 보이기 시작하는 일시. null이면 지금
	 */
	void graded(long courseId, long assignmentId, boolean released, String title, LocalDateTime visibleAt) {
		if (released) {
			this.notifications.publish(new CourseNotification(NotificationKind.ASSIGNMENT_GRADED, courseId,
					CourseRole.STUDENT, assignmentId, title, visibleAt));
		}
		else {
			this.notifications.withdraw(NotificationKind.ASSIGNMENT_GRADED, assignmentId);
		}
	}

}
