package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 알림마당(과목 밖 전역 게시판)의 자료실(MATERIAL)과 1:1 문의(H002) 조회. 전역 게시판은 boards.course_id가 NULL이다. 글을 쓰는 운영 화면은
 * 아직 없다. 자료는 게시 일시가 DB 시계로 지난 것만, 1:1 문의는 요청한 사람이 쓴 것만 다룬다(EP-0160, COMM-09).
 */
@Repository
class CommunityRepository {

	private static final String MATERIALS = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id IS NULL AND b.board_type = 'MATERIAL' AND b.is_active = 1 AND p.post_kind = 'MATERIAL'
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.published_at <= CURRENT_TIMESTAMP(3)
			   AND p.audience IN (:audiences)
			""";

	private static final String INQUIRIES = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id IS NULL AND b.board_type = 'H002' AND p.post_kind = 'QUESTION'
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.author_id = :user
			""";

	private static final String TITLE_MATCH = " AND p.title LIKE CONCAT('%', :q, '%')";

	private final JdbcClient jdbc;

	CommunityRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param audiences 요청한 사람에게 허용한 대상 중 필터에 맞는 것
	 */
	List<MaterialRow> materials(long userId, Collection<String> audiences, String title, Long cursor, int limit) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.title, NULL AS body_text, p.excerpt, p.audience, p.view_count, p.published_at, p.updated_at,
				       EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read
				""" + MATERIALS + (title == null ? "" : TITLE_MATCH) + (cursor == null ? "" : " AND p.post_id < :cursor")
				+ " ORDER BY p.post_id DESC LIMIT :limit")
			.param("user", userId)
			.param("audiences", audiences)
			.param("q", PlainText.likeLiteral(title))
			.param("cursor", cursor)
			.param("limit", limit)
			.query(CommunityRepository::material)
			.list();
	}

	/** 대상별 건수. 필터는 빼고 허용한 대상과 검색어만 본다. */
	List<AudienceCount> materialCounts(Collection<String> audiences, String title) {
		return this.jdbc.sql("SELECT p.audience, COUNT(*) AS n" + MATERIALS + (title == null ? "" : TITLE_MATCH)
				+ " GROUP BY p.audience")
			.param("audiences", audiences)
			.param("q", PlainText.likeLiteral(title))
			.query((rs, n) -> new AudienceCount(rs.getString("audience"), rs.getLong("n")))
			.list();
	}

	Optional<MaterialRow> material(long userId, Collection<String> audiences, long postId) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.title, p.body_text, p.excerpt, p.audience, p.view_count, p.published_at, p.updated_at,
				       EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read
				""" + MATERIALS + " AND p.post_id = :post")
			.param("user", userId)
			.param("audiences", audiences)
			.param("post", postId)
			.query(CommunityRepository::material)
			.optional();
	}

	/** 볼 수 있는 자료 행을 잠그고 최신 조회수를 읽는다. 읽음 기록 전에 부른다(부모 행 먼저). */
	Optional<Integer> lockMaterial(Collection<String> audiences, long postId) {
		return this.jdbc.sql("SELECT p.view_count" + MATERIALS + " AND p.post_id = :post FOR UPDATE OF p")
			.param("audiences", audiences)
			.param("post", postId)
			.query(Integer.class)
			.optional();
	}

	/** @param answered null이면 모두, true면 답변이 있는 문의, false면 미답변 */
	List<InquiryRow> inquiries(long userId, Boolean answered, String title, Long cursor, int limit) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.title, NULL AS body_text, p.is_answered, p.answered_at, p.answer_count, p.created_at
				""" + INQUIRIES + answered(answered) + (title == null ? "" : TITLE_MATCH)
				+ (cursor == null ? "" : " AND p.post_id < :cursor") + " ORDER BY p.post_id DESC LIMIT :limit")
			.param("user", userId)
			.param("q", PlainText.likeLiteral(title))
			.param("cursor", cursor)
			.param("limit", limit)
			.query(CommunityRepository::inquiry)
			.list();
	}

	InquiryCounts inquiryCounts(long userId, String title) {
		return this.jdbc.sql("SELECT COUNT(*) AS total, COALESCE(SUM(p.is_answered = 1), 0) AS answered" + INQUIRIES
				+ (title == null ? "" : TITLE_MATCH))
			.param("user", userId)
			.param("q", PlainText.likeLiteral(title))
			.query((rs, n) -> new InquiryCounts(rs.getLong("total"), rs.getLong("answered"),
					rs.getLong("total") - rs.getLong("answered")))
			.single();
	}

	Optional<InquiryRow> inquiry(long userId, long postId) {
		return this.jdbc.sql("""
				SELECT p.post_id, p.title, p.body_text, p.is_answered, p.answered_at, p.answer_count, p.created_at
				""" + INQUIRIES + " AND p.post_id = :post")
			.param("user", userId)
			.param("post", postId)
			.query(CommunityRepository::inquiry)
			.optional();
	}

	/** 문의의 답변. 먼저 쓴 답변부터 limit건. */
	List<ReplyRow> replies(long inquiryId, int limit) {
		return this.jdbc.sql("""
				SELECT post_id, author_id, body_text, created_at FROM posts
				 WHERE parent_post_id = :inquiry AND deleted_at IS NULL
				 ORDER BY post_id LIMIT :limit
				""")
			.param("inquiry", inquiryId)
			.param("limit", limit)
			.query((rs, n) -> new ReplyRow(rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("body_text"),
					rs.getObject("created_at", LocalDateTime.class)))
			.list();
	}

	private static String answered(Boolean answered) {
		return answered == null ? "" : answered ? " AND p.is_answered = 1" : " AND p.is_answered = 0";
	}

	private static MaterialRow material(ResultSet rs, int n) throws SQLException {
		return new MaterialRow(rs.getLong("post_id"), rs.getString("title"), rs.getString("body_text"),
				rs.getString("excerpt"), rs.getString("audience"), rs.getInt("view_count"),
				rs.getObject("published_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
				rs.getBoolean("is_read"));
	}

	private static InquiryRow inquiry(ResultSet rs, int n) throws SQLException {
		return new InquiryRow(rs.getLong("post_id"), rs.getString("title"), rs.getString("body_text"),
				rs.getBoolean("is_answered"), rs.getObject("answered_at", LocalDateTime.class), rs.getInt("answer_count"),
				rs.getObject("created_at", LocalDateTime.class));
	}

	record MaterialRow(long postId, String title, String body, String excerpt, String audience, int viewCount,
			LocalDateTime publishedAt, LocalDateTime updatedAt, boolean read) {
	}

	record AudienceCount(String audience, long count) {
	}

	record InquiryRow(long postId, String title, String body, boolean answered, LocalDateTime answeredAt,
			int answerCount, LocalDateTime createdAt) {
	}

	record InquiryCounts(long total, long answered, long unanswered) {
	}

	record ReplyRow(long replyId, long authorId, String body, LocalDateTime createdAt) {
	}

}
