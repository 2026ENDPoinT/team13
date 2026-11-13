package com.stackcorp.astra.communication;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.communication.api.OpenQuestions;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link OpenQuestions} 구현. 질문은 {@link BoardKind#QNA} 게시판의 최상위 글이고, 답글 수는 답글을 달고 지울 때 같은 transaction에서
 * 고치는 {@code posts.answer_count}다. 해결 상태는 질문 목록의 unsolved 필터와 같은 {@code pro_status}다.
 */
@Service
class OpenQuestionQueries implements OpenQuestions {

	private static final String OPEN = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id IN (:courses) AND b.board_type = :type AND p.post_kind = :kind
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL
			   AND p.answer_count = 0 AND p.pro_status <> 'Y' AND p.is_faq = 0
			""";

	private final JdbcClient jdbc;

	OpenQuestionQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, Integer> counts(Collection<Long> courseIds) {
		Map<Long, Integer> counts = new HashMap<>();
		if (courseIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("SELECT b.course_id, COUNT(*)" + OPEN + " GROUP BY b.course_id")
			.param("courses", courseIds)
			.param("type", BoardKind.QNA.type)
			.param("kind", BoardKind.QNA.postKind)
			.query((rs) -> {
				counts.put(rs.getLong(1), rs.getInt(2));
			});
		return counts;
	}

	@Override
	public List<OpenQuestion> oldest(Collection<Long> courseIds, int limit) {
		if (courseIds.isEmpty() || limit < 1) {
			return List.of();
		}
		return this.jdbc.sql("SELECT p.post_id, b.course_id, p.title, p.created_at" + OPEN
				+ " ORDER BY p.created_at, p.post_id LIMIT :limit")
			.param("courses", courseIds)
			.param("type", BoardKind.QNA.type)
			.param("kind", BoardKind.QNA.postKind)
			.param("limit", limit)
			.query((rs, n) -> new OpenQuestion(rs.getLong(1), rs.getLong(2), rs.getString(3),
					rs.getObject(4, LocalDateTime.class)))
			.list();
	}

}
