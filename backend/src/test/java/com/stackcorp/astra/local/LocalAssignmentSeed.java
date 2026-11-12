package com.stackcorp.astra.local;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 과제. 데이터베이스 과목(bi)에 교수 박은주의 과제 넷과 기계학습 과목(ai)에 하나를 넣는다. 과제 만들기(AssignmentService)처럼
 * 활동(report), 과제, 성적 항목을 한 묶음으로 쓰고, 제출은 제출 버전과 함께, 채점은 성적 점수로 쓴다.
 * <ul>
 * <li>1차(마감, 늦은 제출 허용 기간도 지남): 점수 공개. 박민준 9점</li>
 * <li>2차(마감): 채점했지만 점수 비공개</li>
 * <li>3차(내일 마감): 박민준은 임시저장만 했다</li>
 * <li>4차(열림, 늦은 제출 허용): 박민준이 최종 제출했다</li>
 * </ul>
 * 알림은 {@link LocalNotificationSeed}가 넣는다. 과목에 과제가 이미 있으면 넣지 않는다.
 */
final class LocalAssignmentSeed {

	static final String RELEASED = "1차 과제: ER 모델링";

	static final String UNRELEASED = "2차 과제: 정규화";

	static final String DRAFTED = "3차 과제: SQL 질의 작성";

	static final String SUBMITTED = "4차 과제: 인덱스 설계";

	private LocalAssignmentSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM learning_activities WHERE course_id = :c AND activity_type = 'report'",
				LocalSql.params("c", bi))) {
			return;
		}
		List<Long> others = LocalPeopleSeed.refs().stream().map((ref) -> LocalSql.user(jdbc, ref)).toList();

		// 만들 때는 비공개였고, 채점을 마친 뒤 수정 화면에서 점수를 공개했다(과제 revision 2, 성적 항목 revision 2).
		Assignment first = create(jdbc, bi, professor, 3, RELEASED, "학사 관리 시스템의 ER 다이어그램을 그리고 설명을 붙인다.", "-20 DAY",
				"-10 DAY", "-8 DAY", "10");
		release(jdbc, first, "-2 DAY");
		submit(jdbc, first, student, "-12 DAY", false, "학생, 과목, 수강 엔터티와 관계를 정리했습니다.");
		grade(jdbc, first, student, professor, "9", "관계의 카디널리티 설명이 좋습니다.", "-3 DAY");
		for (int i = 0; i < others.size(); i++) {
			boolean late = i == 3;
			submit(jdbc, first, others.get(i), late ? "-9 DAY" : "-11 DAY", late, "ER 다이어그램 설명입니다.");
			grade(jdbc, first, others.get(i), professor, late ? "6" : String.valueOf(7 + i % 3), late ? "늦게 제출해 감점했습니다." : null,
					"-3 DAY");
		}

		Assignment second = create(jdbc, bi, professor, 5, UNRELEASED, "주어진 릴레이션을 제3정규형까지 분해한다.", "-14 DAY", "-3 DAY",
				null, "10");
		submit(jdbc, second, student, "-4 DAY", false, "함수 종속을 찾고 3NF로 분해했습니다.");
		grade(jdbc, second, student, professor, "8", "이행 종속 하나를 놓쳤습니다.", "-1 DAY");
		for (int i = 0; i < 5; i++) {
			submit(jdbc, second, others.get(i), "-5 DAY", false, "정규화 과정입니다.");
			grade(jdbc, second, others.get(i), professor, String.valueOf(6 + i % 4), null, "-1 DAY");
		}

		Assignment third = create(jdbc, bi, professor, 7, DRAFTED, "조인과 집계를 쓰는 질의 다섯 개를 작성한다.", "-5 DAY", "+1 DAY", null,
				"10");
		jdbc.sql("""
				INSERT INTO assignment_submissions (assignment_id, owner_type, user_id, draft_body_text, draft_saved_at)
				VALUES (:a, 'user', :u, :body, CURRENT_TIMESTAMP(3) - INTERVAL 5 HOUR)
				""").param("a", third.assignmentId()).param("u", student).param("body", "1. SELECT ... JOIN ...\n2. 작성 중").update();
		for (int i = 0; i < 3; i++) {
			submit(jdbc, third, others.get(i), "-1 DAY", false, "질의 다섯 개입니다.");
		}

		Assignment fourth = create(jdbc, bi, professor, 8, SUBMITTED, "수강 테이블의 질의에 맞는 인덱스를 설계하고 이유를 쓴다.", "-1 DAY",
				"+10 DAY", "+12 DAY", "20");
		submit(jdbc, fourth, student, "-2 HOUR", false, "학번과 과목 번호의 복합 인덱스를 제안합니다.");

		long ai = LocalSql.course(jdbc, "ai");
		create(jdbc, ai, professor, 6, "과제 1: 선형 회귀 구현", "경사하강법으로 선형 회귀를 구현하고 학습 곡선을 그린다.", "-2 DAY", "+5 DAY",
				null, "10");
	}

	/** 과제 만들기: 활동, 과제(revision 1), 성적 항목(비공개, revision 1). */
	private static Assignment create(JdbcClient jdbc, long course, long professor, int week, String title,
			String description, String openAt, String closeAt, String lateUntil, String maxScore) {
		long activity = LocalSql.activity(jdbc, course, week, "report", title, description, openAt, closeAt, false,
				professor);
		long assignment = LocalSql.insert(jdbc, """
				INSERT INTO assignments (activity_id, allow_text_yn, allow_file_yn, late_policy, late_until)
				VALUES (:a, 1, 1, :policy, %s)
				""".formatted(LocalSql.at(lateUntil)), LocalSql.params("a", activity, "policy",
				lateUntil == null ? "none" : "allow_until"));
		long item = LocalSql.insert(jdbc, """
				INSERT INTO grade_items (course_id, activity_id, item_name, item_type, max_score, score_visibility, release_at,
				                         created_by_user_id)
				VALUES (:c, :a, :name, 'assignment', :max, 'hidden', NULL, :u)
				""", LocalSql.params("c", course, "a", activity, "name", title, "max", new BigDecimal(maxScore), "u",
				professor));
		return new Assignment(activity, assignment, item);
	}

	/** 수정 화면에서 점수 공개를 켠 것과 같다(AssignmentService.update). */
	private static void release(JdbcClient jdbc, Assignment assignment, String at) {
		jdbc.sql("UPDATE assignments SET revision_no = revision_no + 1 WHERE assignment_id = :id")
			.param("id", assignment.assignmentId())
			.update();
		jdbc.sql("""
				UPDATE grade_items SET score_visibility = 'immediate', release_at = %s, revision_no = revision_no + 1
				 WHERE grade_item_id = :id
				""".formatted(LocalSql.at(at))).param("id", assignment.gradeItemId()).update();
	}

	/** 최종 제출 한 번: 버전 1과 제출 행(임시저장은 비운다). */
	private static void submit(JdbcClient jdbc, Assignment assignment, long user, String at, boolean late, String body) {
		long submission = LocalSql.insert(jdbc, """
				INSERT INTO assignment_submissions (assignment_id, owner_type, user_id, current_version_no, first_submitted_at,
				                                    submitted_at, is_late, created_at)
				VALUES (:a, 'user', :u, 1, %1$s, %1$s, :late, %1$s)
				""".formatted(LocalSql.at(at)), LocalSql.params("a", assignment.assignmentId(), "u", user, "late", late));
		jdbc.sql("""
				INSERT INTO assignment_submission_versions (assignment_submission_id, version_no, body_text, submitted_at,
				                                            submitted_by_user_id, is_late, created_at)
				VALUES (:s, 1, :body, %1$s, :u, :late, %1$s)
				""".formatted(LocalSql.at(at))).param("s", submission).param("body", body).param("u", user).param("late", late).update();
	}

	/** 제출물 채점(GradebookRecords.putScore): 새 점수 행은 revision 1이다. */
	private static void grade(JdbcClient jdbc, Assignment assignment, long user, long grader, String score,
			String feedback, String at) {
		jdbc.sql("""
				INSERT INTO grade_scores (grade_item_id, user_id, score, score_comment, graded_at, graded_by_user_id)
				VALUES (:item, :u, :score, :feedback, %s, :grader)
				""".formatted(LocalSql.at(at)))
			.param("item", assignment.gradeItemId())
			.param("u", user)
			.param("score", new BigDecimal(score))
			.param("feedback", feedback)
			.param("grader", grader)
			.update();
	}

	private record Assignment(long activityId, long assignmentId, long gradeItemId) {
	}

}
