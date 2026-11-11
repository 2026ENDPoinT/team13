package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.communication.api.CourseNoticeDigest.NoticeHeadline;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 강의실 공지의 저장. 공지는 과목의 공지 게시판(boards.board_type = 'K001')에 속한 posts 행이다(ADR-0013).
 * 게시 일시(published_at)가 NULL이면 임시 공지, 미래면 예약 공지다. 학생 조회에는 {@link #PUBLISHED} 조건을 붙인다.
 * 삭제는 deleted_at을 채우는 소프트 삭제다. 열람·확인 기록은 (post, user) unique 행이다(SC-05).
 */
@Repository
class NoticeRepository {

	private static final String NOTICES = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id = :course AND b.board_type = 'K001' AND p.post_kind = 'NOTICE'
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL
			""";

	private static final String PUBLISHED = " AND p.published_at <= :now";

	private static final String CALLER_STATE = """
			, EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read
			, EXISTS (SELECT 1 FROM post_confirmations f WHERE f.post_id = p.post_id AND f.user_id = :user) AS is_confirmed
			""";

	// 담당자에게만 보이는 열람·확인 수. 작성자 본인의 기록은 세지 않는다(EP-0006).
	private static final String COUNTS = """
			, (SELECT COUNT(*) FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id <> p.author_id) AS read_count
			, (SELECT COUNT(*) FROM post_confirmations f WHERE f.post_id = p.post_id AND f.user_id <> p.author_id) AS confirm_count
			""";

	private static final String NO_COUNTS = ", NULL AS read_count, NULL AS confirm_count";

	private static final String TITLE_MATCH = " AND p.title LIKE CONCAT('%', :q, '%')";

	private final JdbcClient jdbc;

	NoticeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	long insert(long boardId, long authorId, NoticeInput input, LocalDateTime now, String clientKey) {
		LocalDateTime publishedAt = !input.published() ? null : input.publishAt() != null ? input.publishAt() : now;
		this.jdbc.sql("""
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, excerpt, is_pinned, published_at,
				                   client_request_id)
				VALUES (:board, :author, 'NOTICE', :title, :body, :excerpt, :pinned, :publishedAt, :clientKey)
				""")
			.param("board", boardId)
			.param("author", authorId)
			.param("title", input.title())
			.param("body", input.body())
			.param("excerpt", input.excerpt())
			.param("pinned", input.pinned())
			.param("publishedAt", publishedAt)
			.param("clientKey", clientKey)
			.update();
		return this.jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
	}

	/** 같은 게시판에서 이 멱등 key로 만든 공지. 삭제한 공지도 돌려준다. */
	Optional<Replay> byClientKey(long courseId, String clientKey) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.author_id, p.deleted_at IS NOT NULL AS deleted
				  FROM posts p JOIN boards b ON b.board_id = p.board_id
				 WHERE b.course_id = :course AND b.board_type = 'K001' AND p.client_request_id = :key
				""")
			.param("course", courseId)
			.param("key", clientKey)
			.query((rs, n) -> new Replay(rs.getLong("post_id"), rs.getLong("author_id"), rs.getBoolean("deleted")))
			.optional();
	}

	/**
	 * @param manager 담당자면 true. 임시·예약 공지와 열람·확인 수를 함께 본다
	 */
	Optional<NoticeRow> find(long courseId, long noticeId, long userId, boolean manager, LocalDateTime now) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.author_id, p.title, p.body_text, p.is_pinned, p.published_at, p.view_count, p.version,
				       p.created_at, p.updated_at
				""" + CALLER_STATE + (manager ? COUNTS : NO_COUNTS) + NOTICES + " AND p.post_id = :notice"
				+ (manager ? "" : PUBLISHED))
			.param("course", courseId)
			.param("notice", noticeId)
			.param("user", userId)
			.param("now", now)
			.query(NoticeRepository::noticeRow)
			.optional();
	}

	/** 고정 공지 먼저, 그다음 임시 공지, 게시 일시와 ID의 역순이다. 같은 입력에는 같은 순서를 돌려준다. */
	List<NoticeItemRow> page(long courseId, long userId, boolean manager, LocalDateTime now, String titleQuery,
			int offset, int size) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.title, p.excerpt, p.is_pinned, p.published_at, p.view_count
				""" + CALLER_STATE + (manager ? COUNTS : NO_COUNTS) + NOTICES + filter(manager, titleQuery) + """
				 ORDER BY p.is_pinned DESC, p.published_at IS NULL DESC, p.published_at DESC, p.post_id DESC
				 LIMIT :size OFFSET :offset
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("now", now)
			.param("q", PlainText.likeLiteral(titleQuery))
			.param("size", size)
			.param("offset", offset)
			.query((rs, n) -> new NoticeItemRow(rs.getLong("post_id"), rs.getString("title"), rs.getString("excerpt"),
					rs.getBoolean("is_pinned"), rs.getObject("published_at", LocalDateTime.class), rs.getInt("view_count"),
					rs.getBoolean("is_read"), rs.getBoolean("is_confirmed"), rs.getObject("read_count", Integer.class),
					rs.getObject("confirm_count", Integer.class)))
			.list();
	}

	long count(long courseId, boolean manager, LocalDateTime now, String titleQuery) {
		return this.jdbc.sql("SELECT COUNT(*)" + NOTICES + filter(manager, titleQuery))
			.param("course", courseId)
			.param("now", now)
			.param("q", PlainText.likeLiteral(titleQuery))
			.query(Long.class)
			.single();
	}

	/**
	 * 버전이 맞을 때만 바꾸고 버전을 올린다(낙관적 잠금). 게시 일시를 비워 보내면 이미 게시된 공지는 원래 게시 일시를 지키고, 임시·예약 공지는 지금 게시한다.
	 * @return 바뀐 행 수. 0이면 없거나 버전이 다르다
	 */
	int update(long courseId, long noticeId, NoticeInput input, int version, LocalDateTime now) {
		return this.jdbc.sql("""
				UPDATE posts p JOIN boards b ON b.board_id = p.board_id
				   SET p.title = :title, p.body_text = :body, p.excerpt = :excerpt, p.is_pinned = :pinned,
				       p.published_at = CASE WHEN :published = 0 THEN NULL
				                             WHEN :publishAt IS NOT NULL THEN :publishAt
				                             WHEN p.published_at IS NOT NULL AND p.published_at <= :now THEN p.published_at
				                             ELSE :now END,
				       p.version = p.version + 1
				 WHERE b.course_id = :course AND b.board_type = 'K001' AND p.post_kind = 'NOTICE'
				   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL
				   AND p.post_id = :notice AND p.version = :version
				""")
			.param("title", input.title())
			.param("body", input.body())
			.param("excerpt", input.excerpt())
			.param("pinned", input.pinned())
			.param("published", input.published())
			.param("publishAt", input.publishAt())
			.param("now", now)
			.param("course", courseId)
			.param("notice", noticeId)
			.param("version", version)
			.update();
	}

	boolean exists(long courseId, long noticeId) {
		return this.jdbc.sql("SELECT COUNT(*)" + NOTICES + " AND p.post_id = :notice")
			.param("course", courseId)
			.param("notice", noticeId)
			.query(Long.class)
			.single() > 0;
	}

	int delete(long courseId, long noticeId) {
		return this.jdbc.sql("""
				UPDATE posts p JOIN boards b ON b.board_id = p.board_id
				   SET p.deleted_at = CURRENT_TIMESTAMP(3), p.updated_at = p.updated_at
				 WHERE b.course_id = :course AND b.board_type = 'K001' AND p.post_kind = 'NOTICE'
				   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.post_id = :notice
				""").param("course", courseId).param("notice", noticeId).update();
	}

	/** @return 처음 확인했으면 true */
	boolean confirm(long noticeId, long userId) {
		try {
			this.jdbc.sql("INSERT INTO post_confirmations (post_id, user_id) VALUES (:post, :user)")
				.param("post", noticeId)
				.param("user", userId)
				.update();
			return true;
		}
		catch (DuplicateKeyException ex) {
			return false;
		}
	}

	Map<Long, Integer> unreadCounts(long userId, Collection<Long> courseIds, LocalDateTime now) {
		Map<Long, Integer> counts = new HashMap<>();
		this.jdbc.sql("""
				SELECT b.course_id, COUNT(*) AS unread
				  FROM posts p JOIN boards b ON b.board_id = p.board_id
				 WHERE b.course_id IN (:courses) AND b.board_type = 'K001' AND p.post_kind = 'NOTICE'
				   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.published_at <= :now
				   AND p.author_id <> :user
				   AND NOT EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user)
				 GROUP BY b.course_id
				""")
			.param("courses", courseIds)
			.param("now", now)
			.param("user", userId)
			.query((rs) -> {
				counts.put(rs.getLong("course_id"), rs.getInt("unread"));
			});
		return counts;
	}

	List<NoticeHeadline> latest(long userId, Collection<Long> courseIds, LocalDateTime now, int limit) {
		return this.jdbc.sql("""
				SELECT p.post_id, b.course_id, p.title, p.is_pinned, p.published_at,
				       EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read
				  FROM posts p JOIN boards b ON b.board_id = p.board_id
				 WHERE b.course_id IN (:courses) AND b.board_type = 'K001' AND p.post_kind = 'NOTICE'
				   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.published_at <= :now
				 ORDER BY p.is_pinned DESC, p.published_at DESC, p.post_id DESC
				 LIMIT :limit
				""")
			.param("courses", courseIds)
			.param("now", now)
			.param("user", userId)
			.param("limit", limit)
			.query((rs, n) -> new NoticeHeadline(rs.getLong("post_id"), rs.getLong("course_id"), rs.getString("title"),
					rs.getBoolean("is_pinned"), rs.getObject("published_at", LocalDateTime.class), rs.getBoolean("is_read")))
			.list();
	}

	private static String filter(boolean manager, String titleQuery) {
		return (manager ? "" : PUBLISHED) + (titleQuery == null ? "" : TITLE_MATCH);
	}

	private static NoticeRow noticeRow(ResultSet rs, int n) throws SQLException {
		return new NoticeRow(rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("title"),
				rs.getString("body_text"), rs.getBoolean("is_pinned"), rs.getObject("published_at", LocalDateTime.class),
				rs.getInt("view_count"), rs.getInt("version"), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("updated_at", LocalDateTime.class), rs.getBoolean("is_read"), rs.getBoolean("is_confirmed"),
				rs.getObject("read_count", Integer.class), rs.getObject("confirm_count", Integer.class));
	}

	/**
	 * @param excerpt 목록 미리보기. 본문 앞부분
	 * @param publishAt 게시 일시. null이면 지금
	 */
	record NoticeInput(String title, String body, String excerpt, boolean pinned, boolean published,
			LocalDateTime publishAt) {
	}

	record Replay(long noticeId, long authorId, boolean deleted) {
	}

	record NoticeRow(long noticeId, long authorId, String title, String body, boolean pinned, LocalDateTime publishedAt,
			int viewCount, int version, LocalDateTime createdAt, LocalDateTime updatedAt, boolean read,
			boolean confirmed, Integer readCount, Integer confirmCount) {
	}

	record NoticeItemRow(long noticeId, String title, String excerpt, boolean pinned, LocalDateTime publishedAt,
			int viewCount, boolean read, boolean confirmed, Integer readCount, Integer confirmCount) {
	}

}
