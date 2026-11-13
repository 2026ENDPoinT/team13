package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 학교 e-Class 강의실에서 가져온 과제 행(ADR-0043). 학생이 학번·학교 비밀번호로 로그인할 때 읽어 둔 목록 값만 쓰고 학교에 요청하지
 * 않는다. 서비스 과제 목록({@code GET /api/courses/{courseId}/assignments})이 학생에게 서비스 과제 뒤에 붙인다. 과제 상세는
 * {@code GET /api/courses/{courseId}/assignments/school-<id>}(learning)이다.
 */
public interface SchoolAssignmentRows {

	/**
	 * 과목의 학교 과제. 학교 강의실 화면 순서(주차, 화면 순)이다. 과목 권한은 판정하지 않는다. 부르는 쪽이 그 학생의 수강을 확인한 과목만
	 * 넘긴다. 쿼리 한 번이다.
	 * @param now DB 시계 값
	 */
	List<SchoolAssignmentRow> of(long userId, long courseId, LocalDateTime now);

	/**
	 * @param activityId {@code "school-<id>"}. 과제 상세 경로와 주차 목록의 학교 행이 같은 ID를 쓴다
	 * @param phase not_open, open, closed(학교 화면의 만료 표시도 closed)
	 * @param done 그 학생의 학교 강의실 완료 표시(가져온 값). 가져온 적이 없으면 false
	 * @param schoolUrl 학교 e-Class에서 그 과제를 여는 주소(제출은 여기서 한다)
	 */
	record SchoolAssignmentRow(String activityId, int weekNo, String title, LocalDateTime openAt,
			LocalDateTime closeAt, String phase, boolean done, String schoolUrl) {
	}

}
