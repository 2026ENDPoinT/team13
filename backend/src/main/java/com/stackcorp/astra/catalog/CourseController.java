package com.stackcorp.astra.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 과목 조회(EP-0214, EP-0216의 대체 계약). 담당하거나 수강이 확정된 과목만 돌려준다.
 * 과목의 구성원이 아니면 그 과목이 있는지 드러내지 않도록 404로 응답한다(SC-02).
 */
@RestController
class CourseController {

	private static final String SELECT = """
			SELECT c.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no,
			       t.term_year, t.term_code, t.term_name, t.is_current
			  FROM courses c LEFT JOIN terms t ON t.term_id = c.term_id
			""";

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	CourseController(JdbcClient jdbc, CourseMemberships memberships) {
		this.jdbc = jdbc;
		this.memberships = memberships;
	}

	@GetMapping("/api/me/courses")
	ResponseEntity<List<CourseView>> mine(@AuthenticationPrincipal Actor actor) {
		List<CourseRow> rows = this.jdbc.sql(SELECT + """
				 WHERE c.course_status <> 'CANCELLED'
				   AND (c.course_id IN (SELECT course_id FROM course_instructors WHERE user_id = :user)
				     OR c.course_id IN (SELECT course_id FROM course_enrollments
				                         WHERE user_id = :user AND enrollment_status = 'ENROLLED'))
				 ORDER BY t.is_current DESC, t.sort_order, c.course_name, c.course_id
				""").param("user", actor.userId()).query(CourseController::row).list();
		List<CourseView> courses = new ArrayList<>();
		for (CourseRow row : rows) {
			// 역할은 다른 권한 판정과 같은 규칙으로 정한다.
			this.memberships.roleOf(actor.userId(), row.courseId()).ifPresent((role) -> courses.add(view(row, role)));
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(courses);
	}

	@GetMapping("/api/courses/{courseId}")
	ResponseEntity<CourseView> one(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		Optional<CourseRole> role = this.memberships.roleOf(actor.userId(), courseId);
		CourseRow row = this.jdbc.sql(SELECT + " WHERE c.course_id = :course AND c.course_status <> 'CANCELLED'")
			.param("course", courseId)
			.query(CourseController::row)
			.optional()
			.filter((r) -> role.isPresent())
			.orElseThrow(ApiException::notFound);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(row, role.get()));
	}

	private CourseView view(CourseRow row, CourseRole role) {
		List<String> instructors = this.jdbc.sql("""
				SELECT u.user_name FROM course_instructors i JOIN users u ON u.user_id = i.user_id
				 WHERE i.course_id = :course ORDER BY i.is_primary DESC, i.sort_order, i.course_instructor_id
				""").param("course", row.courseId()).query(String.class).list();
		return new CourseView(row.courseId(), row.key(), row.name(), row.code(), row.section(), row.term(), role.name(),
				instructors);
	}

	private static CourseRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		Integer year = rs.getObject("term_year", Integer.class);
		TermView term = year == null ? null
				: new TermView(year, rs.getString("term_code"), rs.getString("term_name"), rs.getBoolean("is_current"));
		return new CourseRow(rs.getLong("course_id"), rs.getString("legacy_kjkey"), rs.getString("course_name"),
				rs.getString("course_code"), rs.getString("section_no"), term);
	}

	private record CourseRow(long courseId, String key, String name, String code, String section, TermView term) {
	}

	record TermView(int year, String code, String name, boolean current) {
	}

	/**
	 * @param key 과목 키(KJKEY). 없으면 null
	 * @param role 이 과목에서 요청한 사용자의 역할. INSTRUCTOR|STUDENT
	 */
	record CourseView(long courseId, String key, String name, String code, String section, TermView term, String role,
			List<String> instructors) {
	}

}
