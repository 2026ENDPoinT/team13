package com.stackcorp.astra.local;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 로컬 실행용 질의응답, 열린게시판, 알림마당 자료실과 1:1 문의. 데이터베이스 과목(bi)에 학생 박민준의 익명 질문과 교수 박은주의 답글, 비공개 질문,
 * 열린게시판 글과 댓글을 넣는다. 알림마당에는 운영자가 올린 자료와 박민준의 1:1 문의를 넣는다. 이미 있으면 넣지 않는다.
 */
final class LocalBoardSeed {

	private static final String STAFF_REF = "ADMIN-01";

	private LocalBoardSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		course(jdbc, student, professor);
		community(jdbc, student);
	}

	private static void course(JdbcClient jdbc, long student, long professor) {
		Long course = jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = 'bi'")
			.query(Long.class)
			.optional()
			.orElse(null);
		if (course == null || jdbc.sql("SELECT COUNT(*) FROM boards WHERE course_id = :c AND board_type IN ('K002', 'K004')")
			.param("c", course)
			.query(Long.class)
			.single() > 0) {
			return;
		}
		long qna = insert(jdbc, """
				INSERT INTO boards (course_id, board_type, board_name, allows_student_write, allows_anonymous, allows_secret,
				                    uses_faq, uses_solved_status)
				VALUES (:c, 'K002', '질의응답', 1, 1, 1, 1, 1)
				""", Map.of("c", course));
		long question = post(jdbc, qna, null, student, "QUESTION", "외래 키와 인덱스",
				"외래 키를 만들면 인덱스가 자동으로 생기나요?", true, false);
		post(jdbc, qna, question, professor, "ANSWER", "re : 외래 키와 인덱스",
				"MySQL InnoDB는 참조하는 열에 인덱스가 없으면 만듭니다.", false, false);
		jdbc.sql("UPDATE posts SET answer_count = 1, pro_status = 'Y' WHERE post_id = :id").param("id", question).update();
		post(jdbc, qna, null, student, "QUESTION", "과제 제출 기한 문의", "병결로 과제 제출이 늦어질 것 같습니다.", false, true);

		long open = insert(jdbc, """
				INSERT INTO boards (course_id, board_type, board_name, allows_student_write, allows_comment)
				VALUES (:c, 'K004', '열린게시판', 1, 1)
				""", Map.of("c", course));
		long article = post(jdbc, open, null, student, "ARTICLE", "기말 스터디 모집", "목요일 저녁 7시에 도서관에서 모입니다.", false,
				false);
		insert(jdbc, "INSERT INTO comments (post_id, author_id, body_text) VALUES (:p, :u, '좋은 모임이네요.')",
				Map.of("p", article, "u", professor));
		jdbc.sql("UPDATE posts SET comment_count = 1 WHERE post_id = :id").param("id", article).update();
	}

	private static void community(JdbcClient jdbc, long student) {
		if (jdbc.sql("SELECT COUNT(*) FROM boards WHERE course_id IS NULL AND board_type IN ('MATERIAL', 'H002')")
			.query(Long.class)
			.single() > 0) {
			return;
		}
		long staff = jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", STAFF_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> insert(jdbc,
					"INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, '운영자', 'STAFF')",
					Map.of("ref", STAFF_REF)));
		long material = insert(jdbc, "INSERT INTO boards (board_type, board_name) VALUES ('MATERIAL', '자료실')", Map.of());
		for (String[] row : new String[][] { { "ALL", "e-Class 이용 안내", "로그인, 강의실, 과제 제출 방법을 정리했습니다." },
				{ "STUDENT", "학생용 과제 제출 안내", "과제는 PDF로 올리면 미리보기가 됩니다." },
				{ "PROFESSOR", "교수자용 강의실 관리 안내", "학습활동과 공지를 등록하는 방법입니다." } }) {
			insert(jdbc, """
					INSERT INTO posts (board_id, author_id, post_kind, title, body_text, excerpt, audience, published_at)
					VALUES (:b, :u, 'MATERIAL', :title, :body, :body, :audience, CURRENT_TIMESTAMP(3) - INTERVAL 3 DAY)
					""", Map.of("b", material, "u", staff, "title", row[1], "body", row[2], "audience", row[0]));
		}
		long inquiry = insert(jdbc, "INSERT INTO boards (board_type, board_name) VALUES ('H002', '1:1 문의')", Map.of());
		long asked = insert(jdbc, """
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, is_answered, answered_at, answer_count)
				VALUES (:b, :u, 'QUESTION', '강의실이 보이지 않습니다', '수강신청한 과목이 강의실 목록에 없습니다.', 1,
				        CURRENT_TIMESTAMP(3), 1)
				""", Map.of("b", inquiry, "u", student));
		insert(jdbc, """
				INSERT INTO posts (board_id, parent_post_id, author_id, post_kind, title, body_text)
				VALUES (:b, :p, :u, 'ANSWER', 're : 강의실이 보이지 않습니다', '학사 동기화 뒤 다음 날 보입니다.')
				""", Map.of("b", inquiry, "p", asked, "u", staff));
	}

	private static long post(JdbcClient jdbc, long board, Long parent, long author, String kind, String title, String body,
			boolean anonymous, boolean secret) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO posts (board_id, parent_post_id, author_id, post_kind, title, body_text, excerpt, is_anonymous,
				                   is_secret, published_at)
				VALUES (:board, :parent, :author, :kind, :title, :body, :body, :anonymous, :secret, CURRENT_TIMESTAMP(3))
				""")
			.param("board", board)
			.param("parent", parent)
			.param("author", author)
			.param("kind", kind)
			.param("title", title)
			.param("body", body)
			.param("anonymous", anonymous)
			.param("secret", secret)
			.update(key, "post_id");
		return key.getKey().longValue();
	}

	private static long insert(JdbcClient jdbc, String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

}
