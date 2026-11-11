package com.stackcorp.astra.catalog.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 강의계획서처럼 과목 밖의 사람에게도 보이는 과목 기본 정보와 담당자 목록. 폐강 과목은 없는 과목이다.
 * 권한은 판정하지 않는다. 구성원이 아닌 사람에게 무엇을 보일지는 부르는 쪽이 정한다.
 */
public interface CourseProfiles {

	Optional<CourseProfile> find(long courseId);

	/**
	 * @param regular 정규과목이면 true. 과목 목록(카탈로그)에서 누구나 상세를 열 수 있는 과목이다
	 * @param termName 학기 표시명(예: 2026년 2학기). 비정규 과정은 null
	 * @param instructorIds 담당자(교수, 조교, 비정규 담당자) 사용자 ID. 대표 담당, 표시 순서대로
	 */
	record CourseProfile(long courseId, boolean regular, String name, String code, String section, String termName,
			String completionType, BigDecimal credit, List<Long> instructorIds) {
	}

}
