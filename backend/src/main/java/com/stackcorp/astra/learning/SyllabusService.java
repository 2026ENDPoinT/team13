package com.stackcorp.astra.learning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강의계획서(EP-0218, EP-0219의 대체 계약). 과목 기본 정보(과목명, 학수번호, 학기, 이수구분, 학점)는 catalog의 값이고 여기서 고치지
 * 않는다. 담당자는 서술 항목, 강의시간/강의실, 주별 계획, 자기 강사 정보만 고친다. 모두 일반 텍스트다. 권한은 부르는 쪽이 판정한다.
 * 계획서 행은 첫 저장 때 version 0으로 만든 뒤 잠가서 비교하므로, 동시에 처음 저장해도 한 요청만 이긴다.
 */
@Service
class SyllabusService {

	private final JdbcClient jdbc;

	private final AuditLog audit;

	SyllabusService(JdbcClient jdbc, AuditLog audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	Optional<SyllabusRow> find(long courseId) {
		return this.jdbc.sql("""
				SELECT syllabus_id, class_time_room, overview, objectives, outcome_relevance, textbooks, equipment,
				       grading_policy, row_version
				  FROM course_syllabi WHERE course_id = :course
				""").param("course", courseId).query((rs, n) -> new SyllabusRow(rs.getLong(1), rs.getString(2),
				rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
				rs.getInt(9))).optional();
	}

	List<WeekPlan> weeks(long syllabusId) {
		return this.jdbc.sql("""
				SELECT week_no, content, note FROM course_syllabus_weeks WHERE syllabus_id = :syllabus ORDER BY week_no
				""")
			.param("syllabus", syllabusId)
			.query((rs, n) -> new WeekPlan(rs.getInt(1), rs.getString(2), rs.getString(3)))
			.list();
	}

	/** 사용자 ID → 강사 정보. */
	Map<Long, Card> cards(long syllabusId) {
		Map<Long, Card> cards = new HashMap<>();
		this.jdbc.sql("""
				SELECT user_id, department, email, office_hours, phone, office, homepage, row_version
				  FROM syllabus_instructors WHERE syllabus_id = :syllabus
				""").param("syllabus", syllabusId).query((rs) -> {
			cards.put(rs.getLong(1), new Card(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
					rs.getString(6), rs.getString(7), rs.getInt(8)));
		});
		return cards;
	}

	/** 서술 항목과 주별 계획을 통째로 바꾼다. 본문에 없는 주차는 지운다. */
	@Transactional
	int update(Actor actor, long courseId, int expectedVersion, Content content, List<WeekPlan> weeks) {
		Locked locked = lock(courseId);
		long syllabusId = locked.syllabusId();
		int version = locked.version();
		if (version != expectedVersion) {
			throw Versions.conflict();
		}
		this.jdbc.sql("""
				UPDATE course_syllabi
				   SET class_time_room = :classTimeRoom, overview = :overview, objectives = :objectives,
				       outcome_relevance = :outcome, textbooks = :textbooks, equipment = :equipment,
				       grading_policy = :grading, updated_by = :user, row_version = row_version + 1
				 WHERE syllabus_id = :id
				""")
			.param("classTimeRoom", content.classTimeRoom())
			.param("overview", content.overview())
			.param("objectives", content.objectives())
			.param("outcome", content.outcomeRelevance())
			.param("textbooks", content.textbooks())
			.param("equipment", content.equipment())
			.param("grading", content.gradingPolicy())
			.param("user", actor.userId())
			.param("id", syllabusId)
			.update();
		this.jdbc.sql("DELETE FROM course_syllabus_weeks WHERE syllabus_id = :id").param("id", syllabusId).update();
		for (WeekPlan week : weeks) {
			this.jdbc.sql("""
					INSERT INTO course_syllabus_weeks (syllabus_id, week_no, content, note) VALUES (:id, :no, :content, :note)
					""")
				.param("id", syllabusId)
				.param("no", week.weekNo())
				.param("content", week.content())
				.param("note", week.note())
				.update();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "syllabus.update", "course_syllabus", syllabusId,
				courseId);
		return version + 1;
	}

	/** 요청한 담당자 자신의 강사 정보를 바꾼다. 처음이면 version 0을 보낸다. */
	@Transactional
	int updateCard(Actor actor, long courseId, int expectedVersion, Card card, int sortOrder) {
		long syllabusId = lock(courseId).syllabusId();
		Optional<Integer> current = this.jdbc.sql("""
				SELECT row_version FROM syllabus_instructors WHERE syllabus_id = :id AND user_id = :user FOR UPDATE
				""").param("id", syllabusId).param("user", actor.userId()).query(Integer.class).optional();
		int version = current.orElse(0);
		if (version != expectedVersion) {
			throw Versions.conflict();
		}
		this.jdbc.sql("""
				INSERT INTO syllabus_instructors (syllabus_id, user_id, department, email, office_hours, phone, office,
				                                  homepage, sort_order)
				VALUES (:id, :user, :department, :email, :officeHours, :phone, :office, :homepage, :order) AS n
				ON DUPLICATE KEY UPDATE department = n.department, email = n.email, office_hours = n.office_hours,
				                        phone = n.phone, office = n.office, homepage = n.homepage,
				                        sort_order = n.sort_order, row_version = syllabus_instructors.row_version + 1
				""")
			.param("id", syllabusId)
			.param("user", actor.userId())
			.param("department", card.department())
			.param("email", card.email())
			.param("officeHours", card.officeHours())
			.param("phone", card.phone())
			.param("office", card.office())
			.param("homepage", card.homepage())
			.param("order", sortOrder)
			.update();
		this.audit.success(actor.userId(), actor.sessionId(), "syllabus.instructor_update", "course_syllabus",
				syllabusId, courseId);
		return version + 1;
	}

	// 계획서 행을 만들고(없으면 version 0) 잠근다. 같은 과목의 계획서 저장은 여기서 차례를 기다린다.
	private Locked lock(long courseId) {
		this.jdbc.sql("""
				INSERT INTO course_syllabi (course_id, row_version) VALUES (:course, 0)
				ON DUPLICATE KEY UPDATE course_id = course_id
				""").param("course", courseId).update();
		return this.jdbc.sql("SELECT syllabus_id, row_version FROM course_syllabi WHERE course_id = :course FOR UPDATE")
			.param("course", courseId)
			.query((rs, n) -> new Locked(rs.getLong(1), rs.getInt(2)))
			.single();
	}

	private record Locked(long syllabusId, int version) {
	}

	record SyllabusRow(long syllabusId, String classTimeRoom, String overview, String objectives,
			String outcomeRelevance, String textbooks, String equipment, String gradingPolicy, int version) {
	}

	record Content(String classTimeRoom, String overview, String objectives, String outcomeRelevance,
			String textbooks, String equipment, String gradingPolicy) {
	}

	record WeekPlan(int weekNo, String content, String note) {
	}

	/**
	 * @param version 강사 정보 행의 version. 수정 때만 쓴다
	 */
	record Card(String department, String email, String officeHours, String phone, String office, String homepage,
			int version) {
	}

}
