package com.stackcorp.astra.communication;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 게시글 열람 기록과 추천. 둘 다 (post, user) unique 행이라 몇 번 보내도 결과가 같다(SC-05, COMM-10, COMM-11).
 * 조회수와 추천 수(posts.view_count, like_count)는 집계 캐시이고, 행을 실제로 만들거나 지운 때만 바꾼다. 수정 일시는 내용을 고친 때만
 * 바뀌게 그대로 둔다. 권한은 부르는 쪽이 판정한다.
 */
@Repository
class PostReactions {

	private final JdbcClient jdbc;

	PostReactions(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 읽음 기록을 남긴다. 처음 읽을 때만 조회수를 올린다. 이미 있으면 최근 열람 일시와 횟수만 바꾼다.
	 * @return 처음 읽었으면 true
	 */
	boolean markRead(long postId, long userId) {
		try {
			this.jdbc.sql("INSERT INTO post_read_receipts (post_id, user_id, last_read_at) VALUES (:post, :user, CURRENT_TIMESTAMP(3))")
				.param("post", postId)
				.param("user", userId)
				.update();
		}
		catch (DuplicateKeyException ex) {
			this.jdbc.sql("""
					UPDATE post_read_receipts SET last_read_at = CURRENT_TIMESTAMP(3), read_count = read_count + 1
					 WHERE post_id = :post AND user_id = :user
					""").param("post", postId).param("user", userId).update();
			return false;
		}
		this.jdbc.sql("UPDATE posts SET view_count = view_count + 1, updated_at = updated_at WHERE post_id = :post")
			.param("post", postId)
			.update();
		return true;
	}

	/** @return 새로 추천했으면 true. 이미 추천했으면 아무것도 바꾸지 않고 false */
	boolean like(long postId, long userId) {
		try {
			this.jdbc.sql("INSERT INTO post_likes (post_id, user_id) VALUES (:post, :user)")
				.param("post", postId)
				.param("user", userId)
				.update();
		}
		catch (DuplicateKeyException ex) {
			return false;
		}
		this.jdbc.sql("UPDATE posts SET like_count = like_count + 1, updated_at = updated_at WHERE post_id = :post")
			.param("post", postId)
			.update();
		return true;
	}

	/** 추천 취소는 행을 지운다. @return 지운 행이 있었으면 true */
	boolean unlike(long postId, long userId) {
		int deleted = this.jdbc.sql("DELETE FROM post_likes WHERE post_id = :post AND user_id = :user")
			.param("post", postId)
			.param("user", userId)
			.update();
		if (deleted == 0) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE posts SET like_count = CASE WHEN like_count > 0 THEN like_count - 1 ELSE 0 END, updated_at = updated_at
				 WHERE post_id = :post
				""").param("post", postId).update();
		return true;
	}

}
