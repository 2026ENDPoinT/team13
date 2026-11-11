package com.stackcorp.astra.communication;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 임시저장(post_drafts). 행은 (사용자, 문맥, 게시판, 폼, 과목, 대상 글)마다 하나다. 사용자는 언제나 session principal이고
 * 모든 문장이 그 사용자의 행으로 좁혀진다(COMM-08).
 */
@Repository
class DraftRepository {

	private static final String SCOPE = """
			 user_id = :user AND context_type = :context AND board_key = :board AND form_key = :form
			   AND course_key = :course AND target_post_key = :target
			""";

	private final JdbcClient jdbc;

	DraftRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 저장된 revision보다 큰 요청만 내용을 바꾼다. 같거나 작으면 행을 그대로 둔다. version은 IF 비교 뒤에 바꾸도록 마지막에 둔다
	 * (ON DUPLICATE KEY UPDATE는 왼쪽부터 적용한다).
	 */
	void upsert(long userId, DraftKey key, long revision, String contents, String hash) {
		this.jdbc.sql("""
				INSERT INTO post_drafts (user_id, context_type, board_id, course_id, target_post_id, form_key, contents,
				                         content_hash, version)
				VALUES (:user, :context, :board, :course, :target, :form, :contents, :hash, :revision) AS incoming
				ON DUPLICATE KEY UPDATE
				  contents = IF(incoming.version > post_drafts.version, incoming.contents, post_drafts.contents),
				  content_hash = IF(incoming.version > post_drafts.version, incoming.content_hash, post_drafts.content_hash),
				  version = GREATEST(post_drafts.version, incoming.version)
				""")
			.param("user", userId)
			.param("context", key.contextType())
			.param("board", key.boardId())
			.param("course", key.courseId())
			.param("target", key.targetPostId())
			.param("form", key.formKey())
			.param("contents", contents)
			.param("hash", hash)
			.param("revision", revision)
			.update();
	}

	Optional<DraftRow> find(long userId, DraftKey key) {
		return this.jdbc.sql("SELECT version, contents, content_hash, COALESCE(updated_at, created_at) FROM post_drafts WHERE"
				+ SCOPE)
			.params(scope(userId, key))
			.query((rs, n) -> new DraftRow(rs.getLong(1), rs.getString(2), rs.getString(3),
					rs.getObject(4, LocalDateTime.class)))
			.optional();
	}

	void delete(long userId, DraftKey key) {
		this.jdbc.sql("DELETE FROM post_drafts WHERE" + SCOPE).params(scope(userId, key)).update();
	}

	private static Map<String, Object> scope(long userId, DraftKey key) {
		return Map.of("user", userId, "context", key.contextType(), "board", zero(key.boardId()), "form",
				key.formKey(), "course", zero(key.courseId()), "target", zero(key.targetPostId()));
	}

	private static long zero(Long id) {
		return id == null ? 0 : id;
	}

	record DraftRow(long revision, String contents, String hash, LocalDateTime updatedAt) {
	}

}
