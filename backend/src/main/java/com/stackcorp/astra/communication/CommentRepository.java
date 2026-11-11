package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 열린게시판 글의 댓글과 댓글 답글(한 단계). 삭제는 deleted_at을 채우는 소프트 삭제다. 지운 댓글은 살아 있는 답글이 있을 때만 자리를 남긴다.
 * posts.comment_count는 살아 있는 댓글 수의 캐시이고, 글 행을 먼저 잠근 뒤 같은 transaction에서 바꾼다.
 */
@Repository
class CommentRepository {

	private final JdbcClient jdbc;

	CommentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 먼저 쓴 댓글부터 cursor보다 큰 ID를 limit건. */
	List<CommentRow> list(long postId, Long cursor, int limit) {
		return this.jdbc.sql("""
				SELECT c.comment_id, c.parent_comment_id, c.author_id, c.body_text, c.version, c.created_at, c.updated_at,
				       c.deleted_at IS NOT NULL AS deleted
				  FROM comments c
				 WHERE c.post_id = :post
				   AND (c.deleted_at IS NULL
				        OR EXISTS (SELECT 1 FROM comments r WHERE r.parent_comment_id = c.comment_id AND r.deleted_at IS NULL))
				""" + (cursor == null ? "" : " AND c.comment_id > :cursor") + " ORDER BY c.comment_id LIMIT :limit")
			.param("post", postId)
			.param("cursor", cursor)
			.param("limit", limit)
			.query(CommentRepository::row)
			.list();
	}

	/** 이 글의 살아 있는 댓글. */
	Optional<CommentRow> find(long postId, long commentId) {
		return this.jdbc.sql("""
				SELECT comment_id, parent_comment_id, author_id, body_text, version, created_at, updated_at, FALSE AS deleted
				  FROM comments WHERE comment_id = :comment AND post_id = :post AND deleted_at IS NULL
				""")
			.param("comment", commentId)
			.param("post", postId)
			.query(CommentRepository::row)
			.optional();
	}

	/**
	 * 작성자의 본문 수정. 글 행을 잠근 뒤 부른다. 버전이 맞을 때만 바꾸고 버전을 올린다.
	 * @return 바뀐 행 수. 0이면 없거나, 지웠거나, 작성자가 아니거나, 버전이 다르다
	 */
	int update(long postId, long commentId, long authorId, int version, String body) {
		return this.jdbc.sql("""
				UPDATE comments SET body_text = :body, version = version + 1
				 WHERE comment_id = :comment AND post_id = :post AND author_id = :author AND deleted_at IS NULL
				   AND version = :version
				""")
			.param("body", body)
			.param("comment", commentId)
			.param("post", postId)
			.param("author", authorId)
			.param("version", version)
			.update();
	}

	/** 글 행을 잠근 뒤 부른다. */
	long insert(long postId, Long parentCommentId, long authorId, String body) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO comments (post_id, parent_comment_id, author_id, body_text)
				VALUES (:post, :parent, :author, :body)
				""")
			.param("post", postId)
			.param("parent", parentCommentId)
			.param("author", authorId)
			.param("body", body)
			.update(key, "comment_id");
		this.count(postId, 1);
		return key.getKey().longValue();
	}

	/** 글 행을 잠근 뒤 부른다. @return 지웠으면 true */
	boolean delete(long postId, long commentId) {
		int deleted = this.jdbc.sql("""
				UPDATE comments SET deleted_at = CURRENT_TIMESTAMP(3), updated_at = updated_at
				 WHERE comment_id = :comment AND post_id = :post AND deleted_at IS NULL
				""").param("comment", commentId).param("post", postId).update();
		if (deleted == 0) {
			return false;
		}
		this.count(postId, -1);
		return true;
	}

	// 컬럼이 UNSIGNED라 0에서 빼면 범위 오류다. 0 아래로 내려가지 않게 나눠 계산한다.
	private void count(long postId, int delta) {
		this.jdbc.sql("""
				UPDATE posts SET comment_count = CASE WHEN :delta > 0 THEN comment_count + 1
				                                      WHEN comment_count > 0 THEN comment_count - 1 ELSE 0 END,
				                 updated_at = updated_at
				 WHERE post_id = :post
				""").param("delta", delta).param("post", postId).update();
	}

	private static CommentRow row(ResultSet rs, int n) throws SQLException {
		return new CommentRow(rs.getLong("comment_id"), rs.getObject("parent_comment_id", Long.class),
				rs.getLong("author_id"), rs.getString("body_text"), rs.getInt("version"),
				rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
				rs.getBoolean("deleted"));
	}

	/**
	 * @param deleted 지운 댓글이면 true. 본문과 작성자는 응답에 넣지 않는다
	 */
	record CommentRow(long commentId, Long parentCommentId, long authorId, String body, int version,
			LocalDateTime createdAt, LocalDateTime updatedAt, boolean deleted) {
	}

}
