package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 알림마당(과목 밖 전역 게시판)의 자료실(MATERIAL)과 1:1 문의(H002). 전역 게시판은 boards.course_id가 NULL이고 유형마다 하나다(V35).
 * 자료는 게시 일시가 DB 시계로 지난 것만 본다. 1:1 문의는 작성자로 좁히거나(본인 문의), 운영자가 볼 때만 좁히지 않는다(EP-0160, COMM-09).
 * 자료와 문의 모두 소프트 삭제(deleted_at)이고 지운 문의의 답변은 부모 조건으로 숨긴다.
 */
@Repository
class CommunityRepository {

	private static final String MATERIALS = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id IS NULL AND b.board_type = 'MATERIAL' AND b.is_active = 1 AND p.post_kind = 'MATERIAL'
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.published_at <= CURRENT_TIMESTAMP(3)
			   AND p.audience IN (:audiences)
			""";

	private static final String MATERIAL_COLUMNS = """
			SELECT p.post_id, p.title, %s AS body_text, p.excerpt, p.audience, p.view_count, p.attachment_group_id, p.version,
			       p.published_at, p.updated_at,
			       EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read
			""";

	private static final String INQUIRIES = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id IS NULL AND b.board_type = 'H002' AND p.post_kind = 'QUESTION'
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL
			""";

	private static final String INQUIRY_COLUMNS = """
			SELECT p.post_id, p.board_id, p.author_id, p.title, %s AS body_text, p.is_answered, p.answered_at, p.answer_count,
			       p.attachment_group_id, p.created_at
			""";

	/** 작성자로 좁힌다. 운영자가 모든 문의를 볼 때만 빼고 붙인다. */
	private static final String AUTHOR = " AND p.author_id = :author";

	private static final String TITLE_MATCH = " AND p.title LIKE CONCAT('%', :q, '%')";

	private final JdbcClient jdbc;

	CommunityRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- 자료실

	/**
	 * @param audiences 요청한 사람에게 허용한 대상 중 필터에 맞는 것
	 */
	List<MaterialRow> materials(long userId, Collection<String> audiences, String title, Long cursor, int limit) {
		return this.jdbc.sql(MATERIAL_COLUMNS.formatted("NULL") + MATERIALS + (title == null ? "" : TITLE_MATCH)
				+ (cursor == null ? "" : " AND p.post_id < :cursor") + " ORDER BY p.post_id DESC LIMIT :limit")
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
		return this.jdbc.sql(MATERIAL_COLUMNS.formatted("p.body_text") + MATERIALS + " AND p.post_id = :post")
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

	/** 운영자가 올린 자료. 지금 게시한다. @return 새 글 ID */
	long insertMaterial(long boardId, long authorId, String title, String body, String audience,
			Long attachmentGroupId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, excerpt, audience, attachment_group_id,
				                   published_at)
				VALUES (:board, :author, 'MATERIAL', :title, :body, :excerpt, :audience, :group, CURRENT_TIMESTAMP(3))
				""")
			.param("board", boardId)
			.param("author", authorId)
			.param("title", title)
			.param("body", body)
			.param("excerpt", PlainText.excerpt(body))
			.param("audience", audience)
			.param("group", attachmentGroupId)
			.update(key, "post_id");
		return key.getKey().longValue();
	}

	/**
	 * 자료 수정. 버전이 맞을 때만 바꾸고 버전을 올린다. 행을 잠그므로 뒤이은 첨부 변경과 같은 자료의 다른 수정이 줄을 선다.
	 * @return 바뀐 행 수. 0이면 없거나 지웠거나 버전이 다르다
	 */
	int updateMaterial(long postId, int version, String title, String body, String audience) {
		return this.jdbc.sql("""
				UPDATE posts p JOIN boards b ON b.board_id = p.board_id
				   SET p.title = :title, p.body_text = :body, p.excerpt = :excerpt, p.audience = :audience,
				       p.version = p.version + 1
				 WHERE p.post_id = :post AND p.version = :version AND p.deleted_at IS NULL AND p.post_kind = 'MATERIAL'
				   AND b.course_id IS NULL AND b.board_type = 'MATERIAL'
				""")
			.param("title", title)
			.param("body", body)
			.param("excerpt", PlainText.excerpt(body))
			.param("audience", audience)
			.param("post", postId)
			.param("version", version)
			.update();
	}

	/**
	 * 지우지 않은 자료 행을 잠그고 첨부 그룹을 읽는다. 수정·삭제가 0행일 때 이유를 가리는 데도 쓴다.
	 * @return 없거나 지웠으면 비어 있다. 있으면 첨부 그룹(없으면 내용이 null인 값)
	 */
	Optional<Group> lockManagedMaterial(long postId) {
		return this.jdbc.sql("""
				SELECT p.attachment_group_id FROM posts p JOIN boards b ON b.board_id = p.board_id
				 WHERE p.post_id = :post AND p.deleted_at IS NULL AND p.post_kind = 'MATERIAL'
				   AND b.course_id IS NULL AND b.board_type = 'MATERIAL'
				   FOR UPDATE OF p
				""")
			.param("post", postId)
			.query((rs, n) -> new Group(rs.getObject(1, Long.class)))
			.optional();
	}

	void deleteMaterial(long postId) {
		this.jdbc.sql("""
				UPDATE posts SET deleted_at = CURRENT_TIMESTAMP(3), attachment_group_id = NULL, updated_at = updated_at
				 WHERE post_id = :post AND deleted_at IS NULL
				""").param("post", postId).update();
	}

	// ---- 1:1 문의

	/**
	 * @param author 이 사람의 문의만. 운영자가 모든 문의를 볼 때 null
	 * @param answered null이면 모두, true면 답변이 있는 문의, false면 미답변
	 */
	List<InquiryRow> inquiries(Long author, Boolean answered, String title, Long cursor, int limit) {
		return this.jdbc.sql(INQUIRY_COLUMNS.formatted("NULL") + INQUIRIES + (author == null ? "" : AUTHOR)
				+ answered(answered) + (title == null ? "" : TITLE_MATCH)
				+ (cursor == null ? "" : " AND p.post_id < :cursor") + " ORDER BY p.post_id DESC LIMIT :limit")
			.param("author", author)
			.param("q", PlainText.likeLiteral(title))
			.param("cursor", cursor)
			.param("limit", limit)
			.query(CommunityRepository::inquiry)
			.list();
	}

	/** @param author 이 사람의 문의만. 운영자가 모든 문의를 셀 때 null */
	InquiryCounts inquiryCounts(Long author, String title) {
		return this.jdbc.sql("SELECT COUNT(*) AS total, COALESCE(SUM(p.is_answered = 1), 0) AS answered" + INQUIRIES
				+ (author == null ? "" : AUTHOR) + (title == null ? "" : TITLE_MATCH))
			.param("author", author)
			.param("q", PlainText.likeLiteral(title))
			.query((rs, n) -> new InquiryCounts(rs.getLong("total"), rs.getLong("answered"),
					rs.getLong("total") - rs.getLong("answered")))
			.single();
	}

	/** @param author 이 사람의 문의일 때만. 운영자가 볼 때 null */
	Optional<InquiryRow> inquiry(Long author, long postId) {
		return this.jdbc.sql(INQUIRY_COLUMNS.formatted("p.body_text") + INQUIRIES + (author == null ? "" : AUTHOR)
				+ " AND p.post_id = :post")
			.param("author", author)
			.param("post", postId)
			.query(CommunityRepository::inquiry)
			.optional();
	}

	/** 지우지 않은 문의 행을 잠근다. 답변 작성이 이 행에서 줄을 선다(부모 행 먼저). */
	Optional<InquiryRow> lockInquiry(long postId) {
		return this.jdbc.sql(INQUIRY_COLUMNS.formatted("NULL") + INQUIRIES + " AND p.post_id = :post FOR UPDATE OF p")
			.param("post", postId)
			.query(CommunityRepository::inquiry)
			.optional();
	}

	/** 답변을 하나 세고 처음 답한 시각을 남긴다. {@link #lockInquiry}로 잠근 뒤 부른다. */
	void markAnswered(long postId) {
		this.jdbc.sql("""
				UPDATE posts SET answer_count = answer_count + 1, is_answered = 1,
				                 answered_at = COALESCE(answered_at, CURRENT_TIMESTAMP(3)), updated_at = updated_at
				 WHERE post_id = :post
				""").param("post", postId).update();
	}

	/** 문의의 답변. 먼저 쓴 답변부터 limit건. */
	List<ReplyRow> replies(long inquiryId, int limit) {
		return this.jdbc.sql("""
				SELECT post_id, author_id, body_text, attachment_group_id, created_at FROM posts
				 WHERE parent_post_id = :inquiry AND deleted_at IS NULL
				 ORDER BY post_id LIMIT :limit
				""")
			.param("inquiry", inquiryId)
			.param("limit", limit)
			.query((rs, n) -> new ReplyRow(rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("body_text"),
					rs.getObject("attachment_group_id", Long.class), rs.getObject("created_at", LocalDateTime.class)))
			.list();
	}

	// ---- 첨부 권한

	/**
	 * 알림마당 글이나 답변에 붙은 첨부 그룹을 이 사람이 읽을 수 있는지. 자료는 지우지 않았고 게시했으며 대상이 허용 범위 안이어야 한다.
	 * 1:1 문의와 그 답변은 지우지 않은(답변이면 부모 문의도 지우지 않은) 것이고 문의 작성자이거나 운영자여야 한다.
	 */
	boolean attachmentVisible(long attachmentGroupId, long userId, Collection<String> audiences, boolean operator) {
		return this.jdbc.sql("""
				SELECT COUNT(*)
				  FROM posts p JOIN boards b ON b.board_id = p.board_id
				  LEFT JOIN posts q ON q.post_id = p.parent_post_id
				 WHERE p.attachment_group_id = :group AND b.course_id IS NULL AND p.deleted_at IS NULL
				   AND ((b.board_type IN ('MATERIAL', 'NOTICE') AND b.is_active = 1 AND p.post_kind = b.board_type
				         AND p.parent_post_id IS NULL AND p.audience IN (:audiences)
				         AND (p.published_at <= CURRENT_TIMESTAMP(3) OR (:operator AND p.published_at IS NOT NULL)))
				     OR (b.board_type = 'H002'
				         AND ((p.parent_post_id IS NULL AND p.post_kind = 'QUESTION')
				              OR (q.post_kind = 'QUESTION' AND q.parent_post_id IS NULL AND q.deleted_at IS NULL))
				         AND (:operator OR COALESCE(q.author_id, p.author_id) = :user)))
				""")
			.param("group", attachmentGroupId)
			.param("audiences", audiences)
			.param("operator", operator)
			.param("user", userId)
			.query(Long.class)
			.single() > 0;
	}

	private static String answered(Boolean answered) {
		return answered == null ? "" : answered ? " AND p.is_answered = 1" : " AND p.is_answered = 0";
	}

	private static MaterialRow material(ResultSet rs, int n) throws SQLException {
		return new MaterialRow(rs.getLong("post_id"), rs.getString("title"), rs.getString("body_text"),
				rs.getString("excerpt"), rs.getString("audience"), rs.getInt("view_count"),
				rs.getObject("attachment_group_id", Long.class), rs.getInt("version"),
				rs.getObject("published_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
				rs.getBoolean("is_read"));
	}

	private static InquiryRow inquiry(ResultSet rs, int n) throws SQLException {
		return new InquiryRow(rs.getLong("post_id"), rs.getLong("board_id"), rs.getLong("author_id"),
				rs.getString("title"), rs.getString("body_text"), rs.getBoolean("is_answered"),
				rs.getObject("answered_at", LocalDateTime.class), rs.getInt("answer_count"),
				rs.getObject("attachment_group_id", Long.class), rs.getObject("created_at", LocalDateTime.class));
	}

	record MaterialRow(long postId, String title, String body, String excerpt, String audience, int viewCount,
			Long attachmentGroupId, int version, LocalDateTime publishedAt, LocalDateTime updatedAt, boolean read) {
	}

	record AudienceCount(String audience, long count) {
	}

	record InquiryRow(long postId, long boardId, long authorId, String title, String body, boolean answered,
			LocalDateTime answeredAt, int answerCount, Long attachmentGroupId, LocalDateTime createdAt) {
	}

	record InquiryCounts(long total, long answered, long unanswered) {
	}

	record ReplyRow(long replyId, long authorId, String body, Long attachmentGroupId, LocalDateTime createdAt) {
	}

	/** @param attachmentGroupId 첨부가 없으면 null */
	record Group(Long attachmentGroupId) {
	}

}
