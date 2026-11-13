package com.stackcorp.astra.identity.api;

import java.util.List;

import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;

/**
 * 학번·학교 e-Class 비밀번호 로그인이 commit된 뒤의 사건. 그 학생의 학교 수강과목을 담는다. 로그인 transaction을 연 쪽에서 발행한다.
 * commit 뒤 같은 thread에서 순서대로 받는다. {@code catalog}가 먼저({@link #CATALOG_ORDER}) 과목과 수강을 학교 화면과 맞추고,
 * {@code learning}이 그다음({@link #LEARNING_ORDER}) 강의실 학습활동 목록을 가져온다.
 * @param userId 로그인한 내부 사용자
 * @param courses 학교 메인 화면의 수강과목. 읽지 못했으면 비어 있다
 * @param rooms 학교 강의실을 읽는 손잡이. 없으면 null. 받은 쪽은 읽기만 하고 닫지 않는다. identity가 로그인한 STack session에 붙여
 * 두고({@link SchoolSessions}) 그 session이 끝날 때 닫는다
 */
public record SchoolLoginSucceeded(long userId, List<SchoolCourse> courses, SchoolCourseRooms rooms) {

	/** 과목·수강 동기화 listener의 {@code @Order}. */
	public static final int CATALOG_ORDER = 0;

	/** 강의실 학습활동 가져오기 listener의 {@code @Order}. 과목이 맞춰진 뒤에 돈다. */
	public static final int LEARNING_ORDER = 1;

	public SchoolLoginSucceeded {
		courses = List.copyOf(courses);
	}

	@Override
	public String toString() {
		return "SchoolLoginSucceeded[userId=" + this.userId + ", courses=" + this.courses.size() + "]";
	}

}
