package com.stackcorp.astra.catalog.api;

/**
 * 알림 유형. 이름이 {@code notifications.notification_type} 값이다. 유형이 연결 대상의 종류와 알림 문장의 앞부분을 정한다.
 * 같은 유형과 대상 ID의 알림은 받는 사람마다 하나다(SC-05).
 */
public enum NotificationKind {

	/** 강의실 공지 게시. 대상은 공지 게시글 ID */
	COURSE_NOTICE(Target.ARTICLE, "새로운 공지사항이 있습니다."),

	/** 알림마당 전체 공지 게시. 과목이 없고 {@link Notifications#publishSite}로만 만든다. 대상은 공지 게시글 ID */
	SITE_NOTICE(Target.ARTICLE, "새로운 공지사항이 있습니다."),

	/** 강의 자료 공개. 대상은 학습활동 ID */
	LECTURE_MATERIAL(Target.ACTIVITY, "새로운 강의자료가 있습니다."),

	/** 과제 공개. 대상은 과제 ID */
	ASSIGNMENT_OPENED(Target.ASSIGNMENT, "새로운 과제가 있습니다."),

	/** 과제 점수 공개. 대상은 과제 ID */
	ASSIGNMENT_GRADED(Target.ASSIGNMENT, "과제 점수가 공개되었습니다."),

	/** 내 질문에 답변. 대상은 질문 게시글 ID */
	QUESTION_ANSWERED(Target.ARTICLE, "질문에 답변이 등록되었습니다."),

	/** 쪽지 받음. 대상은 쪽지 ID */
	MESSAGE_RECEIVED(Target.MESSAGE, "새 쪽지가 도착했습니다.");

	private final Target target;

	private final String lead;

	NotificationKind(Target target, String lead) {
		this.target = target;
		this.lead = lead;
	}

	/** 연결 대상의 종류({@code notifications.target_kind}). */
	public Target target() {
		return this.target;
	}

	/** 알림 문장의 앞부분. 뒤에 대상 제목을 따옴표로 붙인다. */
	public String lead() {
		return this.lead;
	}

	/**
	 * 연결 대상 종류. 대상 행은 다른 모듈의 것이라 ID를 값으로만 저장한다. 대상 ID는 화면이 그 대상을 여는 경로의 ID다(ADR-0036).
	 */
	public enum Target {
		/** learning_activities. 화면은 /api/courses/{courseId}/activities/{targetId}로 연다 */
		ACTIVITY,
		/** assignments. 화면은 /api/courses/{courseId}/assignments/{targetId}로 연다 */
		ASSIGNMENT,
		/** posts */
		ARTICLE,
		/** 쪽지 */
		MESSAGE
	}

}
