package com.stackcorp.astra.catalog.api;

import java.util.Collection;
import java.util.List;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 사용자와 같은 과목에 속한 사람. 쪽지 받는사람처럼 "이 사람과 함께 있는 과목과 그 안의 역할"이 필요한 곳에 쓴다. 누구에게 무엇을
 * 허용할지는 부르는 쪽이 정한다. 역할은 {@link CourseMemberships#roleOf}와 같은 규칙이고, 폐강 과목과 자기 자신은 뺀다.
 * 과목 수와 관계없이 쿼리 한 번이다.
 */
public interface CourseMates {

	/**
	 * @param courseId 이 과목만 본다. null이면 사용자의 모든 과목
	 * @param only 이 사용자들만 본다. null이면 과목 구성원 전체
	 * @return 과목과 상대마다 한 행
	 */
	List<CourseMate> of(long userId, Long courseId, Collection<Long> only);

	/**
	 * @param viewerRole 기준 사용자의 그 과목 역할
	 * @param role 상대의 그 과목 역할
	 */
	record CourseMate(long courseId, long userId, CourseRole viewerRole, CourseRole role) {
	}

}
