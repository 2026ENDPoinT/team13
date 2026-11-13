package com.stackcorp.astra.catalog.api;

import java.time.LocalDate;
import java.util.List;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 사용자가 담당하거나 수강이 확정된 과목 전체를 쿼리 한 번으로 돌려준다. 메인 화면 집계(home)처럼 과목마다 따로 묻지 않아야 하는 곳에 쓴다.
 * 역할은 {@link CourseMemberships#roleOf}와 같은 규칙으로 정하고, 폐강 과목은 뺀다. 순서는 현재 학기, 학기 순서, 과목명이다.
 */
public interface MemberCourses {

	List<MemberCourse> of(long userId);

	/**
	 * @param key 과목 키(KJKEY). 없으면 null
	 * @param startDate 수업 기간 시작일. 정규 과목은 학기 시작일, 비정규 과정은 학습기간 시작일이다. 없으면 null
	 * @param endDate 수업 기간 종료일(포함). 시작일과 같은 출처다. 없으면 null
	 */
	record MemberCourse(long courseId, String key, String name, String code, String section, CourseRole role,
			LocalDate startDate, LocalDate endDate) {
	}

}
