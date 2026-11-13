package com.stackcorp.astra.local;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 퀴즈. 데이터베이스 과목(bi)에 온라인 퀴즈 넷을 넣는다. 퀴즈 만들기(QuizService)처럼 활동(test), 퀴즈(revision 1), 문항과
 * 보기, 성적 항목(quiz, 만점은 배점 합)을 쓰고, 응시는 제출과 채점(AttemptScoring)이 남기는 행과 같다.
 * <ul>
 * <li>4주차: 열림, 1회 응시, 결과 공개. 박민준이 제출했고 단답형은 교수가 채점했다. 다시 응시할 수 없다</li>
 * <li>6주차: 마감, 결과 비공개. 단답형 채점을 기다리는 응시가 있다(교수 화면의 채점 대기)</li>
 * <li>8주차: 열림, 2회 응시, 제한 시간 20분. 박민준은 아직 응시하지 않아 시작할 수 있다</li>
 * <li>10주차: 모레 열린다(학생 목록에는 아직 없다)</li>
 * </ul>
 * 문항 유형은 single, multiple, short 셋이다. 과목에 퀴즈가 이미 있으면 넣지 않는다.
 */
final class LocalQuizSeed {

	static final String SUBMITTED = "4주차 퀴즈: SQL 기초";

	static final String PENDING = "6주차 퀴즈: 정규화";

	static final String STARTABLE = "8주차 퀴즈: 인덱스";

	static final String UPCOMING = "10주차 퀴즈: 트랜잭션";

	private LocalQuizSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM learning_activities WHERE course_id = :c AND activity_type = 'test'",
				LocalSql.params("c", bi))) {
			return;
		}
		List<Long> others = LocalPeopleSeed.refs().stream().map((ref) -> LocalSql.user(jdbc, ref)).toList();

		Quiz sql = create(jdbc, bi, professor, 4, SUBMITTED, "-3 DAY", "+4 DAY", null, 1,
				List.of(new Q("single", "학생 테이블의 모든 행을 읽는 질의는?", "2", List.of("SELECT * FROM student", "GET student"), "1"),
						new Q("multiple", "집계 함수를 모두 고르시오.", "2", List.of("COUNT", "SUM", "LIKE"), "12"),
						new Q("short", "중복 행을 없애는 SELECT 키워드를 쓰시오.", "1", List.of(), "")));
		// 만든 뒤 결과 공개 일시를 정했다(QuizService.update: 퀴즈와 성적 항목 revision 2).
		jdbc.sql("UPDATE quizzes SET revision_no = revision_no + 1 WHERE quiz_id = :q").param("q", sql.quizId()).update();
		jdbc.sql("""
				UPDATE grade_items SET score_visibility = 'after_release', release_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 DAY,
				       revision_no = revision_no + 1
				 WHERE grade_item_id = :i
				""").param("i", sql.gradeItemId()).update();
		graded(jdbc, sql, student, professor, "-2 DAY", List.of("1", "12", "DISTINCT"), "1");
		for (int i = 0; i < 4; i++) {
			graded(jdbc, sql, others.get(i), professor, "-2 DAY", List.of(i % 2 == 0 ? "1" : "2", "12", "UNIQUE"), "0");
		}

		Quiz normal = create(jdbc, bi, professor, 6, PENDING, "-9 DAY", "-2 DAY", null, 1,
				List.of(new Q("single", "부분 함수 종속을 없앤 정규형은?", "2", List.of("1NF", "2NF", "BCNF"), "2"),
						new Q("short", "이행 함수 종속의 예를 하나 쓰시오.", "3", List.of(), "")));
		pending(jdbc, normal, student, "-3 DAY", List.of("2", "학번 -> 학과 -> 학과장"));
		for (int i = 0; i < 5; i++) {
			pending(jdbc, normal, others.get(i), "-4 DAY", List.of(i % 2 == 0 ? "2" : "3", "주문번호 -> 고객번호 -> 고객주소"));
		}

		create(jdbc, bi, professor, 8, STARTABLE, "-1 DAY", "+3 DAY", 20, 2,
				List.of(new Q("single", "범위 검색에 알맞은 인덱스 구조는?", "2", List.of("B+tree", "해시"), "1"),
						new Q("multiple", "인덱스가 쓰이기 어려운 조건을 모두 고르시오.", "2",
								List.of("열에 함수를 씌운 조건", "앞이 와일드카드인 LIKE", "기본 키 동등 조건"), "12"),
						new Q("short", "복합 인덱스에서 왼쪽 열부터 쓰는 규칙의 이름을 쓰시오.", "2", List.of(), "")));

		create(jdbc, bi, professor, 10, UPCOMING, "+2 DAY", "+3 DAY", 30, 1,
				List.of(new Q("single", "격리 수준 중 가장 강한 것은?", "2", List.of("READ COMMITTED", "SERIALIZABLE"), "2"),
						new Q("short", "갱신 손실을 막는 방법을 하나 쓰시오.", "3", List.of(), "")));
	}

	/** 퀴즈 만들기: 활동, 퀴즈, 문항과 보기, 성적 항목(결과 공개 일시가 없으면 hidden). */
	private static Quiz create(JdbcClient jdbc, long course, long professor, int week, String title, String openAt,
			String closeAt, Integer minutes, Integer attemptLimit, List<Q> questions) {
		long activity = LocalSql.activity(jdbc, course, week, "test", title, "강의 내용을 확인하는 퀴즈입니다.", openAt, closeAt, false,
				professor);
		long quiz = LocalSql.insert(jdbc, """
				INSERT INTO quizzes (activity_id, exam_type, duration_minutes, attempt_limit, shuffle_yn, answer_release_yn)
				VALUES (:a, 'online', :minutes, :limit, 0, 0)
				""", LocalSql.params("a", activity, "minutes", minutes, "limit", attemptLimit));
		List<Question> stored = new ArrayList<>();
		BigDecimal max = BigDecimal.ZERO;
		for (int no = 1; no <= questions.size(); no++) {
			Q q = questions.get(no - 1);
			BigDecimal points = new BigDecimal(q.points());
			max = max.add(points);
			long id = LocalSql.insert(jdbc, """
					INSERT INTO quiz_questions (quiz_id, question_no, question_type, question_text, score, explanation, sort_order)
					VALUES (:quiz, :no, :type, :text, :points, NULL, :no)
					""", LocalSql.params("quiz", quiz, "no", no, "type", q.type(), "text", q.text(), "points", points));
			List<Long> options = new ArrayList<>();
			for (int o = 1; o <= q.options().size(); o++) {
				options.add(LocalSql.insert(jdbc, """
						INSERT INTO quiz_question_options (quiz_question_id, option_no, option_text, is_correct_yn, sort_order)
						VALUES (:q, :no, :text, :correct, :no)
						""", LocalSql.params("q", id, "no", o, "text", q.options().get(o - 1), "correct",
						q.correct().contains(String.valueOf(o)))));
			}
			stored.add(new Question(id, q, points, options));
		}
		long item = LocalSql.insert(jdbc, """
				INSERT INTO grade_items (course_id, activity_id, item_name, item_type, max_score, score_visibility, release_at,
				                         created_by_user_id)
				VALUES (:c, :a, :name, 'quiz', :max, 'hidden', NULL, :u)
				""", LocalSql.params("c", course, "a", activity, "name", title, "max", max, "u", professor));
		return new Quiz(quiz, item, stored);
	}

	/**
	 * 제출 뒤 교수가 단답형까지 채점을 마친 응시(status graded)와 최고 점수 행.
	 * @param answers 문항마다 고른 보기 번호들(예: "12") 또는 단답 문구
	 * @param shortScore 교수가 단답형에 준 점수
	 */
	private static void graded(JdbcClient jdbc, Quiz quiz, long user, long professor, String at, List<String> answers,
			String shortScore) {
		long attempt = attempt(jdbc, quiz, user, at);
		BigDecimal total = BigDecimal.ZERO;
		for (int i = 0; i < quiz.questions().size(); i++) {
			Question question = quiz.questions().get(i);
			BigDecimal awarded = answer(jdbc, attempt, question, answers.get(i), at, shortScore, professor);
			total = total.add(awarded);
		}
		finish(jdbc, attempt, at, "graded", total, "%s + INTERVAL 1 DAY".formatted(LocalSql.at(at)));
		jdbc.sql("""
				INSERT INTO grade_scores (grade_item_id, user_id, score, graded_at, graded_by_user_id)
				VALUES (:item, :u, :score, %s + INTERVAL 1 DAY, :grader)
				""".formatted(LocalSql.at(at)))
			.param("item", quiz.gradeItemId())
			.param("u", user)
			.param("score", total)
			.param("grader", professor)
			.update();
	}

	/** 제출했고 단답형이 채점 대기(pending)라 status submitted이며 점수 행이 없다. */
	private static void pending(JdbcClient jdbc, Quiz quiz, long user, String at, List<String> answers) {
		long attempt = attempt(jdbc, quiz, user, at);
		for (int i = 0; i < quiz.questions().size(); i++) {
			answer(jdbc, attempt, quiz.questions().get(i), answers.get(i), at, null, null);
		}
		finish(jdbc, attempt, at, "submitted", null, null);
	}

	/** 응시 시작: 20분 전에 시작해 1회차, 잠금 key는 그때의 session이다. */
	private static long attempt(JdbcClient jdbc, Quiz quiz, long user, String at) {
		return LocalSql.insert(jdbc, """
				INSERT INTO quiz_attempts (quiz_id, user_id, attempt_no, status, started_at, deadline_at, session_lock_key,
				                           lock_acquired_at)
				SELECT :quiz, :u, 1, 'in_progress', %1$s - INTERVAL 20 MINUTE,
				       LEAST(a.close_at, COALESCE(%1$s - INTERVAL 20 MINUTE + INTERVAL q.duration_minutes MINUTE, a.close_at)),
				       'seed', %1$s - INTERVAL 20 MINUTE
				  FROM quizzes q JOIN learning_activities a ON a.activity_id = q.activity_id WHERE q.quiz_id = :quiz
				""".formatted(LocalSql.at(at)), LocalSql.params("quiz", quiz.quizId(), "u", user));
	}

	/**
	 * 저장한 답(revision 1)과 채점 결과. 객관식은 전부 맞아야 배점을 받는다. 단답형은 shortScore가 있으면 교수 채점(manual), 없으면
	 * 채점 대기(pending)다.
	 * @return 받은 점수(대기면 0)
	 */
	private static BigDecimal answer(JdbcClient jdbc, long attempt, Question question, String value, String at,
			String shortScore, Long professor) {
		boolean text = "short".equals(question.spec().type());
		long answer = LocalSql.insert(jdbc, """
				INSERT INTO quiz_attempt_answers (quiz_attempt_id, quiz_question_id, answer_text, revision, saved_at)
				VALUES (:a, :q, :text, 1, %s - INTERVAL 5 MINUTE)
				""".formatted(LocalSql.at(at)), LocalSql.params("a", attempt, "q", question.id(), "text", text ? value : null));
		if (text) {
			if (shortScore == null) {
				jdbc.sql("UPDATE quiz_attempt_answers SET grading_status = 'pending' WHERE quiz_attempt_answer_id = :id")
					.param("id", answer)
					.update();
				return BigDecimal.ZERO;
			}
			BigDecimal awarded = new BigDecimal(shortScore);
			jdbc.sql("""
					UPDATE quiz_attempt_answers SET awarded_score = :score, grading_status = 'manual', grader_comment = :comment,
					       graded_at = %s + INTERVAL 1 DAY, graded_by_user_id = :grader
					 WHERE quiz_attempt_answer_id = :id
					""".formatted(LocalSql.at(at)))
				.param("score", awarded)
				.param("comment", awarded.signum() > 0 ? "정답입니다." : "DISTINCT가 맞습니다.")
				.param("grader", professor)
				.param("id", answer)
				.update();
			return awarded;
		}
		for (char c : value.toCharArray()) {
			jdbc.sql("INSERT INTO quiz_attempt_answer_options (quiz_attempt_answer_id, quiz_question_option_id) VALUES (:a, :o)")
				.param("a", answer)
				.param("o", question.options().get(c - '1'))
				.update();
		}
		boolean correct = value.equals(question.spec().correct());
		BigDecimal awarded = correct ? question.points() : BigDecimal.ZERO;
		jdbc.sql("""
				UPDATE quiz_attempt_answers SET is_correct_yn = :correct, awarded_score = :score, grading_status = 'auto',
				       graded_at = %s
				 WHERE quiz_attempt_answer_id = :id
				""".formatted(LocalSql.at(at))).param("correct", correct).param("score", awarded).param("id", answer).update();
		return awarded;
	}

	private static void finish(JdbcClient jdbc, long attempt, String at, String status, BigDecimal total,
			String gradedAt) {
		jdbc.sql("""
				UPDATE quiz_attempts SET status = :status, submitted_at = %s, finish_reason = 'submitted', total_score = :total,
				       graded_at = %s
				 WHERE quiz_attempt_id = :id AND status = 'in_progress'
				""".formatted(LocalSql.at(at), gradedAt == null ? "NULL" : gradedAt))
			.param("status", status)
			.param("total", total)
			.param("id", attempt)
			.update();
	}

	/**
	 * @param correct 정답 보기 번호들(예: "12"). 단답형은 빈 문자열
	 */
	private record Q(String type, String text, String points, List<String> options, String correct) {
	}

	private record Question(long id, Q spec, BigDecimal points, List<Long> options) {
	}

	private record Quiz(long quizId, long gradeItemId, List<Question> questions) {
	}

}
