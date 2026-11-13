package com.stackcorp.astra.catalog;

/**
 * 과목 구성원 판정의 하나뿐인 규칙(SC-03). {@link CourseMembershipQueries#roleOf}, 수강생 명단, 함께 듣는 사람, 내 과목, 시간표,
 * 알림 받는 사람과 보이는 알림, 수강 신청 처리 권한이 모두 이 조각으로 정해진다. 다른 module은 이 규칙을 {@code catalog.api}로만 쓴다.
 * <ul>
 * <li>폐강(course_status = CANCELLED) 과목에는 구성원이 없다. 조회와 명령이 모두 404다. 담당·수강 행과 학습 기록은 지우지 않는다.</li>
 * <li>INSTRUCTOR: 담당 관계(course_instructors)가 있거나 교수·조교로 수강이 확정됐다.</li>
 * <li>STUDENT: INSTRUCTOR가 아니고 학생·청강생으로 수강이 확정됐다.</li>
 * </ul>
 * 인자는 테이블 별칭을 붙인 열 이름이나 named parameter다. 하위 질의 안의 같은 이름 열로 풀리지 않도록 별칭 없는 열 이름은 넘기지 않는다.
 * 요청 값을 SQL로 넣지 않는다.
 */
final class MembershipRule {

	private MembershipRule() {
	}

	/** 폐강하지 않은 과목이다. */
	static String liveCourse(String course) {
		return "EXISTS (SELECT 1 FROM courses mr_c WHERE mr_c.course_id = " + course
				+ " AND mr_c.course_status <> 'CANCELLED')";
	}

	/** 그 과목의 INSTRUCTOR다. */
	static String instructorOf(String course, String user) {
		return "(" + liveCourse(course) + " AND " + staff(course, user) + ")";
	}

	/** 그 과목의 STUDENT다. */
	static String studentOf(String course, String user) {
		return "(" + liveCourse(course) + " AND " + learner(course, user) + " AND NOT " + staff(course, user) + ")";
	}

	/** 그 과목의 INSTRUCTOR이거나 STUDENT다. */
	static String memberOf(String course, String user) {
		return "(" + liveCourse(course) + " AND (" + staff(course, user) + " OR " + learner(course, user) + "))";
	}

	/**
	 * 사용자가 담당하거나 수강 행이 있는 과목의 ID. 판정이 아니라 index로 후보를 좁히는 데만 쓰고, 판정은 {@link #memberOf}로 한다.
	 */
	static String candidateCourses(String user) {
		return "SELECT course_id FROM course_instructors WHERE user_id = " + user
				+ " UNION SELECT course_id FROM course_enrollments WHERE user_id = " + user;
	}

	private static String staff(String course, String user) {
		return "(EXISTS (SELECT 1 FROM course_instructors mr_i WHERE mr_i.course_id = " + course + " AND mr_i.user_id = "
				+ user + ") OR EXISTS (SELECT 1 FROM course_enrollments mr_s WHERE mr_s.course_id = " + course
				+ " AND mr_s.user_id = " + user
				+ " AND mr_s.enrollment_status = 'ENROLLED' AND mr_s.enrollment_role IN ('PROFESSOR', 'TA')))";
	}

	private static String learner(String course, String user) {
		return "EXISTS (SELECT 1 FROM course_enrollments mr_e WHERE mr_e.course_id = " + course + " AND mr_e.user_id = "
				+ user + " AND mr_e.enrollment_status = 'ENROLLED' AND mr_e.enrollment_role IN ('STUDENT', 'AUDITOR'))";
	}

}
