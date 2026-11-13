package com.stackcorp.astra.communication;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 알림마당 전역 게시판(과목 없음). 공지(NOTICE), 자료실(MATERIAL), 1:1 문의(H002)가 하나씩이고 uq_boards_community_board(V35)가
 * 유형마다 하나만 남긴다. 만드는 방식은 {@link CourseBoards}와 같다.
 */
@Repository
class CommunityBoards {

	static final String NOTICE = "NOTICE";

	static final String MATERIAL = "MATERIAL";

	static final String INQUIRY = "H002";

	private final JdbcClient jdbc;

	CommunityBoards(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 그 유형의 전역 게시판 ID. 없으면 만든다. 동시에 만들면 늦은 쪽은 먼저 만든 행의 ID를 받는다. 글을 쓰는 transaction과 따로
	 * commit한다({@link CourseBoards#boardFor}와 같은 이유).
	 * @param type {@link #NOTICE}, {@link #MATERIAL}, {@link #INQUIRY}
	 */
	long boardFor(String type) {
		Optional<Long> existing = this.jdbc
			.sql("SELECT board_id FROM boards WHERE community_board_key = :type")
			.param("type", type)
			.query(Long.class)
			.optional();
		if (existing.isPresent()) {
			return existing.get();
		}
		this.jdbc.sql("""
				INSERT INTO boards (course_id, board_type, board_name, allows_student_write)
				VALUES (NULL, :type, :name, :studentWrite)
				ON DUPLICATE KEY UPDATE board_id = LAST_INSERT_ID(board_id)
				""")
			.param("type", type)
			.param("name", switch (type) {
				case NOTICE -> "공지사항";
				case MATERIAL -> "자료실";
				default -> "1:1 문의";
			})
			.param("studentWrite", INQUIRY.equals(type))
			.update();
		return this.jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
	}

}
