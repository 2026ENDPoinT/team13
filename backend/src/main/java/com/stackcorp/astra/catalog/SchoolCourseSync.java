package com.stackcorp.astra.catalog;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.api.SchoolLoginSucceeded;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.Slot;

/**
 * 학번·학교 e-Class 비밀번호로 로그인한 학생의 과목과 수강을 학교 메인 화면과 맞춘다. 로그인 transaction이 commit된 뒤 같은 thread에서
 * 새 transaction으로 실행하므로, 로그인 응답이 나갈 때는 이미 맞춰져 있다. 실패하면 이 transaction만 rollback하고 로그만 남긴다.
 * 로그인은 그대로 성공한다.
 * <ul>
 * <li>과목은 학교 과목 키({@code courses.legacy_kjkey})로 찾고 없으면 만든다. 있는 과목은 학교 화면 값으로 갱신한다.</li>
 * <li>정규과목의 학기는 (학년도, 학교 학기 코드)로 찾고 없으면 만든다. 학기의 현재 여부는 바꾸지 않는다.</li>
 * <li>수강은 학생 역할의 확정 수강(정규 REGISTRAR, 비정규 NONREGULAR)으로 둔다. 이 동기화가 만든 수강 중 학교 화면에서 사라진
 * 과목은 취소한다. 학교 화면에서 과목을 하나도 읽지 못하면 아무것도 바꾸지 않는다.</li>
 * <li>담당자는 학교 화면이 이름 글자만 주므로 {@code courses.instructor_label}에 둔다. 사용자 행을 지어내지 않는다.</li>
 * </ul>
 */
@Component
class SchoolCourseSync {

	private static final Logger log = LoggerFactory.getLogger(SchoolCourseSync.class);

	private static final Map<String, String> TERM_NAMES = Map.of("1", "1학기", "2", "여름학기", "3", "2학기", "4",
			"겨울학기", "5", "디스커버리학기", "F3", "2학기(미래융합)");

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	SchoolCourseSync(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(SchoolLoginSucceeded event) {
		try {
			this.transaction.executeWithoutResult((status) -> sync(event.userId(), event.courses()));
		}
		catch (RuntimeException ex) {
			log.warn("School course sync failed: type={}", ex.getClass().getSimpleName());
		}
	}

	void sync(long userId, List<SchoolCourse> courses) {
		if (courses.isEmpty()) {
			return;
		}
		for (SchoolCourse course : courses) {
			long courseId = upsertCourse(course);
			replaceSchedules(courseId, course.slots());
			this.jdbc.sql("""
					INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status,
					                                source, external_sync_at)
					VALUES (:course, :user, :kind, 'STUDENT', 'ENROLLED', 'REGISTRAR', CURRENT_TIMESTAMP(3))
					ON DUPLICATE KEY UPDATE external_sync_at = CURRENT_TIMESTAMP(3)
					""")
				.param("course", courseId)
				.param("user", userId)
				.param("kind", course.regular() ? "REGISTRAR" : "NONREGULAR")
				.update();
		}
		this.jdbc.sql("""
				UPDATE course_enrollments e JOIN courses c ON c.course_id = e.course_id
				   SET e.enrollment_status = 'CANCELLED', e.cancelled_at = CURRENT_TIMESTAMP(3)
				 WHERE e.user_id = :user AND e.enrollment_status = 'ENROLLED' AND e.source = 'REGISTRAR'
				   AND e.external_sync_at IS NOT NULL AND c.legacy_kjkey NOT IN (:keys)
				""")
			.param("user", userId)
			.param("keys", courses.stream().map(SchoolCourse::key).toList())
			.update();
	}

	private long upsertCourse(SchoolCourse course) {
		Long termId = course.regular() ? termOf(course.termYear(), course.termCode()) : null;
		this.jdbc.sql("""
				INSERT INTO courses (term_id, course_type, course_name, course_code, section_no, org_sect, ledger_year,
				                     ledger_session, legacy_kjkey, credit, lecture_type, cover_template, instructor_label,
				                     learning_period_label)
				VALUES (:term, :type, :name, :code, :section, :org, :year, :session, :key, :credit, :lectureType, :cover,
				        :instructors, :period) AS new
				ON DUPLICATE KEY UPDATE course_name = new.course_name, credit = COALESCE(new.credit, courses.credit),
				                        lecture_type = COALESCE(new.lecture_type, courses.lecture_type),
				                        cover_template = COALESCE(new.cover_template, courses.cover_template),
				                        instructor_label = new.instructor_label,
				                        learning_period_label = new.learning_period_label
				""")
			.param("term", termId)
			.param("type", course.regular() ? CatalogQueries.REGULAR : CatalogQueries.NON_REGULAR)
			.param("name", course.name())
			.param("code", course.code())
			.param("section", course.section())
			.param("org", course.regular() ? course.key().substring(0, 1) : null)
			.param("year", course.termYear())
			.param("session", course.termCode())
			.param("key", course.key())
			.param("credit", course.credit())
			.param("lectureType", course.lectureType())
			.param("cover", course.coverTemplate())
			.param("instructors", course.instructors())
			.param("period", course.learningPeriod())
			.update();
		return this.jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = :key")
			.param("key", course.key())
			.query(Long.class)
			.single();
	}

	private long termOf(int year, String code) {
		this.jdbc.sql("""
				INSERT INTO terms (term_year, term_code, term_name, legacy_year_term)
				VALUES (:year, :code, :name, :legacy)
				ON DUPLICATE KEY UPDATE term_id = term_id
				""")
			.param("year", year)
			.param("code", code)
			.param("name", year + "년 " + TERM_NAMES.getOrDefault(code, code + "학기"))
			.param("legacy", year + "^" + code)
			.update();
		return this.jdbc.sql("""
				SELECT term_id FROM terms WHERE institution_code = 'SEOULTECH' AND term_year = :year AND term_code = :code
				""").param("year", year).param("code", code).query(Long.class).single();
	}

	// 학교 화면이 시간표의 원천이므로 매번 통째로 바꾼다. 같은 과목을 듣는 학생은 같은 값을 본다.
	private void replaceSchedules(long courseId, List<Slot> slots) {
		if (slots.isEmpty()) {
			return;
		}
		this.jdbc.sql("DELETE FROM course_schedules WHERE course_id = :course").param("course", courseId).update();
		for (Slot slot : slots) {
			this.jdbc.sql("""
					INSERT INTO course_schedules (course_id, day_of_week, period_start, period_end)
					VALUES (:course, :day, :start, :end)
					""")
				.param("course", courseId)
				.param("day", slot.dayOfWeek())
				.param("start", slot.periodStart())
				.param("end", slot.periodEnd())
				.update();
		}
	}

}
