package com.stackcorp.astra.identity.api;

import java.util.List;

import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;

/**
 * 학번·학교 e-Class 비밀번호 로그인이 commit된 뒤의 사건. 그 학생의 학교 수강과목을 담는다. {@code catalog}가 받아 과목과 수강을
 * 학교 화면과 맞춘다. 로그인 transaction을 연 쪽에서 발행한다.
 * @param userId 로그인한 내부 사용자
 * @param courses 학교 메인 화면의 수강과목. 읽지 못했으면 비어 있다
 */
public record SchoolLoginSucceeded(long userId, List<SchoolCourse> courses) {

	public SchoolLoginSucceeded {
		courses = List.copyOf(courses);
	}

}
