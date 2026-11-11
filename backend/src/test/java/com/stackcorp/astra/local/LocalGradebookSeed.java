package com.stackcorp.astra.local;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 성적부. 데이터베이스 과목(bi)에 성적부 설정(학생에게 보임), 직접 입력 항목 "중간고사"(만점 100, 가중치 30, 공개)와 수강생
 * 전원의 점수, 과제와 퀴즈 항목의 가중치를 넣는다. 직접 입력 항목은 GradebookRecords.insertManual처럼 만들고, 공개와 가중치 변경은
 * 성적 항목 revision을 올린다. {@link LocalAssignmentSeed}, {@link LocalQuizSeed} 다음에 부른다. 성적부 설정이 있으면 넣지 않는다.
 */
final class LocalGradebookSeed {

	static final String MANUAL = "중간고사";

	private LocalGradebookSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM grade_settings WHERE course_id = :c", LocalSql.params("c", bi))) {
			return;
		}
		jdbc.sql("INSERT INTO grade_settings (course_id, activities_visible_yn) VALUES (:c, 1)").param("c", bi).update();

		// 만들 때 가중치를 함께 정했고(revision 1), 채점 뒤 공개했다(revision 2).
		long item = LocalSql.insert(jdbc, """
				INSERT INTO grade_items (course_id, item_name, item_type, max_score, weight, score_visibility, created_by_user_id,
				                         created_at)
				VALUES (:c, :name, 'manual', 100, 30, 'hidden', :u, CURRENT_TIMESTAMP(3) - INTERVAL 15 DAY)
				""", LocalSql.params("c", bi, "name", MANUAL, "u", professor));
		List<Long> students = new ArrayList<>();
		students.add(student);
		LocalPeopleSeed.refs().forEach((ref) -> students.add(LocalSql.user(jdbc, ref)));
		int[] scores = { 86, 92, 74, 81, 68, 95, 77, 88, 59 };
		for (int i = 0; i < students.size(); i++) {
			jdbc.sql("""
					INSERT INTO grade_scores (grade_item_id, user_id, score, score_comment, graded_at, graded_by_user_id)
					VALUES (:item, :u, :score, NULL, CURRENT_TIMESTAMP(3) - INTERVAL 2 DAY, :grader)
					""").param("item", item).param("u", students.get(i)).param("score", scores[i]).param("grader", professor).update();
		}
		jdbc.sql("""
				UPDATE grade_items SET score_visibility = 'immediate', release_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 DAY,
				       revision_no = revision_no + 1
				 WHERE grade_item_id = :id
				""").param("id", item).update();

		// 과제·퀴즈 항목은 가중치만 바꿀 수 있다(GradebookService.update).
		for (String[] weight : new String[][] { { LocalAssignmentSeed.RELEASED, "10" },
				{ LocalAssignmentSeed.UNRELEASED, "10" }, { LocalAssignmentSeed.DRAFTED, "10" },
				{ LocalAssignmentSeed.SUBMITTED, "20" }, { LocalQuizSeed.SUBMITTED, "10" },
				{ LocalQuizSeed.PENDING, "10" } }) {
			jdbc.sql("""
					UPDATE grade_items SET weight = :w, revision_no = revision_no + 1
					 WHERE course_id = :c AND item_name = :name AND item_type IN ('assignment', 'quiz')
					""").param("w", new BigDecimal(weight[1])).param("c", bi).param("name", weight[0]).update();
		}
	}

}
