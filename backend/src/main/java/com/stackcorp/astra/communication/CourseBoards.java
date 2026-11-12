package com.stackcorp.astra.communication;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 과목마다 하나인 강의실 게시판(ADR-0013, ADR-0024). uq_boards_course_notice(K001)와 uq_boards_course_board(K002, K004)가
 * 과목마다 유형별로 하나만 남긴다.
 */
@Repository
class CourseBoards {

	private final JdbcClient jdbc;

	CourseBoards(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 과목의 게시판 ID. 없으면 만든다. 동시에 만들면 늦은 쪽은 먼저 만든 행을 기다렸다가 그 ID를 받는다. 잠금 읽기(FOR UPDATE)는 없는 행에
	 * gap lock을 걸어 두 요청이 서로 기다리게 하므로 쓰지 않는다. transaction 안에서 불러야 LAST_INSERT_ID()가 같은 연결에서 읽힌다.
	 * 글을 쓰는 transaction과 따로 commit한다. 같은 transaction에서 만들면 다른 요청이 먼저 만든 게시판 행이 그 transaction의
	 * snapshot에 보이지 않아 방금 쓴 글을 다시 읽지 못한다.
	 */
	long boardFor(long courseId, BoardKind kind) {
		Optional<Long> existing = this.jdbc
			.sql("SELECT board_id FROM boards WHERE course_id = :course AND board_type = :type")
			.param("course", courseId)
			.param("type", kind.type)
			.query(Long.class)
			.optional();
		if (existing.isPresent()) {
			return existing.get();
		}
		this.jdbc.sql("""
				INSERT INTO boards (course_id, board_type, board_name, allows_student_write, allows_anonymous, allows_secret,
				                    allows_comment, uses_faq, uses_solved_status, requires_notice_confirm)
				VALUES (:course, :type, :name, :studentWrite, :anonymous, :secret, :comments, :answers, :answers, :notice)
				ON DUPLICATE KEY UPDATE board_id = LAST_INSERT_ID(board_id)
				""")
			.param("course", courseId)
			.param("type", kind.type)
			.param("name", kind.defaultName)
			.param("studentWrite", kind != BoardKind.NOTICE)
			.param("anonymous", kind.anonymous)
			.param("secret", kind.secret)
			.param("comments", kind.comments)
			.param("answers", kind.answers)
			.param("notice", kind == BoardKind.NOTICE)
			.update();
		return this.jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
	}

}
