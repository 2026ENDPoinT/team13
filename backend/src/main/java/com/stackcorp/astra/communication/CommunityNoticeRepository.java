package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.communication.CommunityRepository.AudienceCount;
import com.stackcorp.astra.communication.CommunityRepository.Group;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 알림마당 공지(EP-0154~0156, EP-0191, EP-0196). 과목이 없는 공지 게시판(board_type NOTICE) 하나의 posts 행(post_kind NOTICE)이다
 * (ADR-0013의 공지 모델, ADR-0035). 대상(audience) 범위 안의 공지만 읽는다. 게시 일시가 DB 시계로 지난 공지만 보이고, 운영자만 예약
 * 공지도 본다. 정렬은 고정 공지 먼저, 그다음 게시 일시와 ID의 역순이다(화면 30 4절).
 */
@Repository
class CommunityNoticeRepository {

	private static final String NOTICES = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id IS NULL AND b.board_type = 'NOTICE' AND b.is_active = 1 AND p.post_kind = 'NOTICE'
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL AND p.audience IN (:audiences)
			   AND (p.published_at <= :now OR (:operator AND p.published_at IS NOT NULL))
			""";

	private static final String COLUMNS = """
			SELECT p.post_id, p.author_id, p.title, %s AS body_text, p.excerpt, p.audience, p.is_pinned, p.view_count,
			       p.attachment_group_id, p.version, p.published_at, p.created_at, p.updated_at,
			       EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read
			""";

	private static final String ORDER = " ORDER BY p.is_pinned DESC, p.published_at DESC, p.post_id DESC";

	private static final String TITLE_MATCH = " AND p.title LIKE CONCAT('%', :q, '%')";

	private final JdbcClient jdbc;

	CommunityNoticeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	/** @param audiences 허용한 대상 중 필터에 맞는 것 */
	List<NoticeRow> page(long userId, Collection<String> audiences, boolean operator, LocalDateTime now, String title,
			int offset, int size) {
		return this.jdbc.sql(COLUMNS.formatted("NULL") + NOTICES + (title == null ? "" : TITLE_MATCH) + ORDER
				+ " LIMIT :size OFFSET :offset")
			.param("user", userId)
			.param("audiences", audiences)
			.param("operator", operator)
			.param("now", now)
			.param("q", PlainText.likeLiteral(title))
			.param("size", size)
			.param("offset", offset)
			.query(CommunityNoticeRepository::row)
			.list();
	}

	/** 대상별 건수. 필터는 빼고 허용한 대상과 검색어만 본다. */
	List<AudienceCount> counts(Collection<String> audiences, boolean operator, LocalDateTime now, String title) {
		return this.jdbc.sql("SELECT p.audience, COUNT(*) AS n" + NOTICES + (title == null ? "" : TITLE_MATCH)
				+ " GROUP BY p.audience")
			.param("audiences", audiences)
			.param("operator", operator)
			.param("now", now)
			.param("q", PlainText.likeLiteral(title))
			.query((rs, n) -> new AudienceCount(rs.getString("audience"), rs.getLong("n")))
			.list();
	}

	Optional<NoticeRow> find(long userId, Collection<String> audiences, boolean operator, LocalDateTime now,
			long postId) {
		return this.jdbc.sql(COLUMNS.formatted("p.body_text") + NOTICES + " AND p.post_id = :post")
			.param("user", userId)
			.param("audiences", audiences)
			.param("operator", operator)
			.param("now", now)
			.param("post", postId)
			.query(CommunityNoticeRepository::row)
			.optional();
	}

	/** 게시된 공지 행을 잠그고 최신 조회수를 읽는다. 예약 공지는 운영자도 읽음을 남기지 않는다. 읽음 기록 전에 부른다(부모 행 먼저). */
	Optional<Integer> lockPublished(Collection<String> audiences, LocalDateTime now, long postId) {
		return this.jdbc.sql("SELECT p.view_count" + NOTICES + " AND p.post_id = :post FOR UPDATE OF p")
			.param("audiences", audiences)
			.param("operator", false)
			.param("now", now)
			.param("post", postId)
			.query(Integer.class)
			.optional();
	}

	/**
	 * 메인 화면의 공지. 게시된 공지만 읽는다.
	 * @param forMe true면 아직 읽지 않은 고정 공지만(진입 공지). false면 최근 공지
	 */
	List<NoticeRow> headlines(long userId, Collection<String> audiences, LocalDateTime now, boolean forMe, int limit) {
		return this.jdbc.sql(COLUMNS.formatted("NULL") + NOTICES
				+ (forMe ? " AND p.is_pinned = 1 AND NOT EXISTS (SELECT 1 FROM post_read_receipts r"
						+ " WHERE r.post_id = p.post_id AND r.user_id = :user)" : "")
				+ ORDER + " LIMIT :limit")
			.param("user", userId)
			.param("audiences", audiences)
			.param("operator", false)
			.param("now", now)
			.param("limit", limit)
			.query(CommunityNoticeRepository::row)
			.list();
	}

	/** @return 새 공지 ID */
	long insert(long boardId, long authorId, NoticeInput input, LocalDateTime publishedAt, Long attachmentGroupId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, excerpt, audience, is_pinned,
				                   attachment_group_id, published_at)
				VALUES (:board, :author, 'NOTICE', :title, :body, :excerpt, :audience, :pinned, :group, :published)
				""")
			.param("board", boardId)
			.param("author", authorId)
			.param("title", input.title())
			.param("body", input.body())
			.param("excerpt", PlainText.excerpt(input.body()))
			.param("audience", input.audience())
			.param("pinned", input.pinned())
			.param("group", attachmentGroupId)
			.param("published", publishedAt)
			.update(key, "post_id");
		return key.getKey().longValue();
	}

	/**
	 * 공지 수정. 버전이 맞을 때만 바꾸고 버전을 올린다. publishAt이 없으면 이미 게시된 공지는 원래 게시 일시를, 예약 공지는 지금을 쓴다.
	 * @return 바뀐 행 수. 0이면 없거나 지웠거나 버전이 다르다
	 */
	int update(long postId, int version, NoticeInput input, LocalDateTime now) {
		return this.jdbc.sql("""
				UPDATE posts p JOIN boards b ON b.board_id = p.board_id
				   SET p.title = :title, p.body_text = :body, p.excerpt = :excerpt, p.audience = :audience,
				       p.is_pinned = :pinned,
				       p.published_at = COALESCE(:publishAt, CASE WHEN p.published_at <= :now THEN p.published_at ELSE :now END),
				       p.version = p.version + 1
				 WHERE p.post_id = :post AND p.version = :version AND p.deleted_at IS NULL AND p.post_kind = 'NOTICE'
				   AND b.course_id IS NULL AND b.board_type = 'NOTICE'
				""")
			.param("title", input.title())
			.param("body", input.body())
			.param("excerpt", PlainText.excerpt(input.body()))
			.param("audience", input.audience())
			.param("pinned", input.pinned())
			.param("publishAt", input.publishAt())
			.param("now", now)
			.param("post", postId)
			.param("version", version)
			.update();
	}

	/** 지우지 않은 공지 행을 잠그고 첨부 그룹을 읽는다. 없거나 지웠으면 비어 있다. */
	Optional<Group> lockManaged(long postId) {
		return this.jdbc.sql("""
				SELECT p.attachment_group_id FROM posts p JOIN boards b ON b.board_id = p.board_id
				 WHERE p.post_id = :post AND p.deleted_at IS NULL AND p.post_kind = 'NOTICE'
				   AND b.course_id IS NULL AND b.board_type = 'NOTICE'
				   FOR UPDATE OF p
				""")
			.param("post", postId)
			.query((rs, n) -> new Group(rs.getObject(1, Long.class)))
			.optional();
	}

	void delete(long postId) {
		this.jdbc.sql("""
				UPDATE posts SET deleted_at = CURRENT_TIMESTAMP(3), attachment_group_id = NULL, updated_at = updated_at
				 WHERE post_id = :post AND deleted_at IS NULL
				""").param("post", postId).update();
	}

	private static NoticeRow row(ResultSet rs, int n) throws SQLException {
		return new NoticeRow(rs.getLong("post_id"), rs.getLong("author_id"), rs.getString("title"),
				rs.getString("body_text"), rs.getString("excerpt"), rs.getString("audience"), rs.getBoolean("is_pinned"),
				rs.getInt("view_count"), rs.getObject("attachment_group_id", Long.class), rs.getInt("version"),
				rs.getObject("published_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class),
				rs.getObject("updated_at", LocalDateTime.class), rs.getBoolean("is_read"));
	}

	/**
	 * 검사한 공지 내용.
	 * @param audience COMMON, PROFESSOR, STUDENT
	 * @param publishAt 요청한 게시 일시. 없으면 null
	 */
	record NoticeInput(String title, String body, String audience, boolean pinned, LocalDateTime publishAt) {
	}

	record NoticeRow(long noticeId, long authorId, String title, String body, String excerpt, String audience,
			boolean pinned, int viewCount, Long attachmentGroupId, int version, LocalDateTime publishedAt,
			LocalDateTime createdAt, LocalDateTime updatedAt, boolean read) {
	}

}
