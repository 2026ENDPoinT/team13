package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.communication.BoardAccess.Viewer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 강의실 질의응답·열린게시판 글의 저장(ADR-0024). 최상위 글은 과목의 게시판(K002, K004)에 속한 posts 행이고, 질문의 답글은
 * parent_post_id가 질문인 ANSWER 행이다. 삭제는 deleted_at을 채우는 소프트 삭제다. 지운 질문의 답글은 따로 지우지 않고 부모 조건으로 숨긴다.
 * <p>
 * 비공개 글은 작성자와 담당자만 본다. 목록, 건수, 검색, 상세, 답글, 첨부가 모두 {@link #VISIBLE}를 거친다(COMM-09).
 * 추천·읽음·답글·댓글처럼 글 한 건에 딸린 행을 바꾸는 명령은 먼저 {@link #lockVisible}로 글 행을 잠근다. 같은 글의 명령이 이 행에서 줄을
 * 서므로 중복 key 검사의 공유 잠금끼리 교착하지 않는다.
 */
@Repository
class BoardPostRepository {

	private static final String TOP = """
			  FROM posts p JOIN boards b ON b.board_id = p.board_id
			 WHERE b.course_id = :course AND b.board_type = :type AND p.post_kind = :kind
			   AND p.parent_post_id IS NULL AND p.deleted_at IS NULL
			""";

	private static final String VISIBLE = " AND (p.is_secret = 0 OR p.author_id = :user OR :manager)";

	private static final String COLUMNS = """
			SELECT p.post_id, p.board_id, p.author_id, p.title, %s AS body_text, p.excerpt, p.is_anonymous, p.is_secret,
			       p.is_faq, p.pro_status, p.view_count, p.like_count, p.comment_count, p.answer_count,
			       p.attachment_group_id, p.version, p.created_at, p.updated_at,
			       EXISTS (SELECT 1 FROM post_read_receipts r WHERE r.post_id = p.post_id AND r.user_id = :user) AS is_read,
			       EXISTS (SELECT 1 FROM post_likes l WHERE l.post_id = p.post_id AND l.user_id = :user) AS is_liked
			""";

	private static final String LIST_COLUMNS = COLUMNS.formatted("NULL");

	private static final String DETAIL_COLUMNS = COLUMNS.formatted("p.body_text");

	private static final String ANSWERS = """
			  FROM posts p
			 WHERE p.parent_post_id = :question AND p.post_kind = 'ANSWER' AND p.deleted_at IS NULL
			""";

	private final JdbcClient jdbc;

	BoardPostRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<PostRow> find(Viewer viewer, BoardKind kind, long postId) {
		return scoped(DETAIL_COLUMNS + TOP + VISIBLE + " AND p.post_id = :post", viewer, kind).param("post", postId)
			.query(BoardPostRepository::row)
			.optional();
	}

	/**
	 * 볼 수 있는 최상위 글 행을 잠그고 최신 값을 읽는다. 읽음·추천·답글·댓글 명령은 이 잠금을 먼저 잡는다(부모 행 먼저).
	 * 게시판 행은 잠그지 않는다.
	 */
	Optional<Locked> lockVisible(Viewer viewer, BoardKind kind, long postId) {
		return scoped("SELECT p.post_id, p.board_id, p.author_id, p.title, p.view_count, p.like_count" + TOP + VISIBLE
				+ " AND p.post_id = :post FOR UPDATE OF p", viewer, kind)
			.param("post", postId)
			.query((rs, n) -> new Locked(rs.getLong("post_id"), rs.getLong("board_id"), rs.getLong("author_id"),
					rs.getString("title"), rs.getInt("view_count"), rs.getInt("like_count")))
			.optional();
	}

	/**
	 * 조건부 UPDATE가 0행일 때 이유를 가린다. 잠금 읽기라 transaction의 snapshot이 아니라 최신 값을 본다.
	 * @return 지웠거나 없으면 비어 있다
	 */
	Optional<State> current(long postId) {
		return this.jdbc.sql("SELECT answer_count, version FROM posts WHERE post_id = :post AND deleted_at IS NULL FOR SHARE")
			.param("post", postId)
			.query((rs, n) -> new State(rs.getInt("answer_count"), rs.getInt("version")))
			.optional();
	}

	/** 게시판의 가장 큰 최상위 글 ID. 쪽 번호 목록의 기준점(snapshot)이다. 글이 없으면 0. */
	long maxId(Viewer viewer, BoardKind kind) {
		return scoped("SELECT COALESCE(MAX(p.post_id), 0)" + TOP, viewer, kind).query(Long.class).single();
	}

	/** 쪽 번호 목록. 최근 글 먼저(ID 역순)다. 같은 입력에는 같은 순서를 돌려준다. */
	List<PostRow> page(Viewer viewer, BoardKind kind, Filter filter, int offset, int size) {
		return filtered(LIST_COLUMNS + TOP + VISIBLE + filter.sql(true) + " ORDER BY p.post_id DESC LIMIT :size OFFSET :offset",
				viewer, kind, filter)
			.param("size", size)
			.param("offset", offset)
			.query(BoardPostRepository::row)
			.list();
	}

	/** cursor 목록. cursor보다 작은 ID를 최근 글부터 limit건 돌려준다. */
	List<PostRow> before(Viewer viewer, BoardKind kind, Filter filter, Long cursor, int limit) {
		return filtered(LIST_COLUMNS + TOP + VISIBLE + filter.sql(true) + (cursor == null ? "" : " AND p.post_id < :cursor")
				+ " ORDER BY p.post_id DESC LIMIT :limit", viewer, kind, filter)
			.param("cursor", cursor)
			.param("limit", limit)
			.query(BoardPostRepository::row)
			.list();
	}

	/** 볼 수 있는 글의 상태별 건수. 상태 필터는 빼고 검색어와 나의 질문·나의 답변 조건은 넣는다. */
	StatusCounts counts(Viewer viewer, BoardKind kind, Filter filter) {
		return filtered("""
				SELECT COUNT(*) AS total, COALESCE(SUM(p.is_faq = 1), 0) AS faq, COALESCE(SUM(p.pro_status = 'Y'), 0) AS solved,
				       COALESCE(SUM(p.pro_status <> 'Y'), 0) AS unsolved
				""" + TOP + VISIBLE + filter.sql(false), viewer, kind, filter)
			.query((rs, n) -> new StatusCounts(rs.getLong("total"), rs.getLong("faq"), rs.getLong("solved"),
					rs.getLong("unsolved")))
			.single();
	}

	long count(Viewer viewer, BoardKind kind, Filter filter) {
		return filtered("SELECT COUNT(*)" + TOP + VISIBLE + filter.sql(true), viewer, kind, filter).query(Long.class)
			.single();
	}

	/**
	 * @param parentId 답글이면 질문 ID
	 * @return 새 글 ID
	 */
	long insert(long boardId, long authorId, String postKind, Long parentId, NewText text, Long attachmentGroupId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO posts (board_id, parent_post_id, author_id, post_kind, title, body_text, excerpt, is_anonymous,
				                   is_secret, attachment_group_id, published_at)
				VALUES (:board, :parent, :author, :kind, :title, :body, :excerpt, :anonymous, :secret, :group,
				        CURRENT_TIMESTAMP(3))
				""")
			.param("board", boardId)
			.param("parent", parentId)
			.param("author", authorId)
			.param("kind", postKind)
			.param("title", text.title())
			.param("body", text.body())
			.param("excerpt", PlainText.excerpt(text.body()))
			.param("anonymous", text.anonymous())
			.param("secret", text.secret())
			.param("group", attachmentGroupId)
			.update(key, "post_id");
		return key.getKey().longValue();
	}

	/**
	 * 작성자의 글 수정. 버전이 맞을 때만 바꾸고 버전을 올린다. 첨부는 {@link #setAttachmentGroup}으로 따로 바꾼다.
	 * @param unanswered true면 답글이 없을 때만 바꾼다(질문)
	 * @return 바뀐 행 수. 0이면 없거나, 버전이 다르거나, 답글이 있다
	 */
	int update(long postId, long authorId, int version, NewText text, boolean unanswered) {
		return this.jdbc.sql("""
				UPDATE posts SET title = :title, body_text = :body, excerpt = :excerpt, is_anonymous = :anonymous,
				                 is_secret = :secret, version = version + 1
				 WHERE post_id = :post AND author_id = :author AND deleted_at IS NULL AND version = :version
				""" + (unanswered ? " AND answer_count = 0" : ""))
			.param("title", text.title())
			.param("body", text.body())
			.param("excerpt", PlainText.excerpt(text.body()))
			.param("anonymous", text.anonymous())
			.param("secret", text.secret())
			.param("post", postId)
			.param("author", authorId)
			.param("version", version)
			.update();
	}

	/** 글이나 답글의 지금 첨부 그룹. 잠금 읽기라 이 transaction이 잠근 행의 최신 값이다. */
	Long attachmentGroup(long postId) {
		return this.jdbc.sql("SELECT attachment_group_id FROM posts WHERE post_id = :post FOR UPDATE")
			.param("post", postId)
			.query((rs, n) -> rs.getObject(1, Long.class))
			.single();
	}

	/** 첨부를 바꾼 뒤의 그룹을 적는다. 버전을 올린 수정과 같은 transaction에서 부른다. */
	void setAttachmentGroup(long postId, Long attachmentGroupId) {
		this.jdbc.sql("UPDATE posts SET attachment_group_id = :group WHERE post_id = :post")
			.param("group", attachmentGroupId)
			.param("post", postId)
			.update();
	}

	/**
	 * @param unanswered true면 답글이 없을 때만 지운다(학생이 자기 질문을 지울 때)
	 * @return 지운 행 수
	 */
	int delete(long postId, boolean unanswered) {
		return this.jdbc.sql("""
				UPDATE posts SET deleted_at = CURRENT_TIMESTAMP(3), updated_at = updated_at
				 WHERE post_id = :post AND deleted_at IS NULL
				""" + (unanswered ? " AND answer_count = 0" : "")).param("post", postId).update();
	}

	/** 목표 상태를 적는다. null인 항목은 그대로 둔다. 몇 번 보내도 결과가 같다(EP-0072). */
	void setStatus(long postId, Boolean solved, Boolean faq) {
		this.jdbc.sql("""
				UPDATE posts SET pro_status = CASE WHEN :solved IS NULL THEN pro_status WHEN :solved THEN 'Y' ELSE 'N' END,
				                 is_faq = COALESCE(:faq, is_faq), updated_at = updated_at
				 WHERE post_id = :post AND deleted_at IS NULL
				""").param("solved", solved).param("faq", faq).param("post", postId).update();
	}

	/** 질문의 답글. 먼저 쓴 답글부터 cursor보다 큰 ID를 limit건 돌려준다. */
	List<PostRow> answers(long questionId, long userId, Long cursor, int limit) {
		return this.jdbc.sql(DETAIL_COLUMNS + ANSWERS + (cursor == null ? "" : " AND p.post_id > :cursor")
				+ " ORDER BY p.post_id LIMIT :limit")
			.param("question", questionId)
			.param("user", userId)
			.param("cursor", cursor)
			.param("limit", limit)
			.query(BoardPostRepository::row)
			.list();
	}

	Optional<PostRow> answer(long questionId, long answerId, long userId) {
		return this.jdbc.sql(DETAIL_COLUMNS + ANSWERS + " AND p.post_id = :answer")
			.param("question", questionId)
			.param("answer", answerId)
			.param("user", userId)
			.query(BoardPostRepository::row)
			.optional();
	}

	/**
	 * 답글 수를 하나 올리거나(delta &gt; 0) 내린다. 질문 행은 {@link #lockVisible}로 먼저 잠근 뒤 부른다. 컬럼이 UNSIGNED라 0에서 빼면
	 * 범위 오류이므로 0 아래로 내려가지 않게 나눠 계산한다.
	 */
	void countAnswer(long questionId, int delta) {
		this.jdbc.sql("""
				UPDATE posts SET answer_count = CASE WHEN :delta > 0 THEN answer_count + 1
				                                     WHEN answer_count > 0 THEN answer_count - 1 ELSE 0 END,
				                 updated_at = updated_at
				 WHERE post_id = :question
				""").param("delta", delta).param("question", questionId).update();
	}

	/** @return 바뀐 행 수. 0이면 없거나 버전이 다르다 */
	int updateAnswer(long questionId, long answerId, long authorId, int version, String body, boolean anonymous) {
		return this.jdbc.sql("""
				UPDATE posts SET body_text = :body, excerpt = :excerpt, is_anonymous = :anonymous, version = version + 1
				 WHERE post_id = :answer AND parent_post_id = :question AND author_id = :author AND deleted_at IS NULL
				   AND version = :version
				""")
			.param("body", body)
			.param("excerpt", PlainText.excerpt(body))
			.param("anonymous", anonymous)
			.param("answer", answerId)
			.param("question", questionId)
			.param("author", authorId)
			.param("version", version)
			.update();
	}

	int deleteAnswer(long questionId, long answerId) {
		return this.jdbc.sql("""
				UPDATE posts SET deleted_at = CURRENT_TIMESTAMP(3), updated_at = updated_at
				 WHERE post_id = :answer AND parent_post_id = :question AND deleted_at IS NULL
				""").param("answer", answerId).param("question", questionId).update();
	}

	/**
	 * 이 과목의 질의응답·열린게시판 글 또는 그 답글에 붙은 첨부 그룹을 이 사용자가 볼 수 있는지. 글과 부모 질문이 지워지지 않았고, 비공개
	 * 질문이면 질문 작성자나 담당자여야 한다.
	 */
	boolean attachmentVisible(long courseId, long attachmentGroupId, long userId, boolean manager) {
		return this.jdbc.sql("""
				SELECT COUNT(*)
				  FROM posts p JOIN boards b ON b.board_id = p.board_id
				  LEFT JOIN posts q ON q.post_id = p.parent_post_id
				 WHERE p.attachment_group_id = :group AND b.course_id = :course AND b.board_type IN (:types)
				   AND p.deleted_at IS NULL AND (p.parent_post_id IS NULL OR (q.deleted_at IS NULL AND q.parent_post_id IS NULL))
				   AND (COALESCE(q.is_secret, p.is_secret) = 0 OR COALESCE(q.author_id, p.author_id) = :user OR :manager)
				""")
			.param("group", attachmentGroupId)
			.param("course", courseId)
			.param("types", List.of(BoardKind.QNA.type, BoardKind.OPEN.type))
			.param("user", userId)
			.param("manager", manager)
			.query(Long.class)
			.single() > 0;
	}

	private StatementSpec scoped(String sql, Viewer viewer, BoardKind kind) {
		return this.jdbc.sql(sql)
			.param("course", viewer.courseId())
			.param("type", kind.type)
			.param("kind", kind.postKind)
			.param("user", viewer.userId())
			.param("manager", viewer.manager());
	}

	private StatementSpec filtered(String sql, Viewer viewer, BoardKind kind, Filter filter) {
		return scoped(sql, viewer, kind).param("q", PlainText.likeLiteral(filter.title())).param("upTo", filter.upTo());
	}

	private static PostRow row(ResultSet rs, int n) throws SQLException {
		return new PostRow(rs.getLong("post_id"), rs.getLong("board_id"), rs.getLong("author_id"), rs.getString("title"),
				rs.getString("body_text"), rs.getString("excerpt"), rs.getBoolean("is_anonymous"),
				rs.getBoolean("is_secret"), rs.getBoolean("is_faq"), "Y".equals(rs.getString("pro_status")),
				rs.getInt("view_count"), rs.getInt("like_count"), rs.getInt("comment_count"), rs.getInt("answer_count"),
				rs.getObject("attachment_group_id", Long.class), rs.getInt("version"),
				rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
				rs.getBoolean("is_read"), rs.getBoolean("is_liked"));
	}

	/** 질의응답 목록의 상태 필터(EP-0070 SCH_FAQ, SCH_STATUS). */
	enum QnaStatus {

		ALL, FAQ, SOLVED, UNSOLVED;

		long of(StatusCounts counts) {
			return switch (this) {
				case ALL -> counts.total();
				case FAQ -> counts.faq();
				case SOLVED -> counts.solved();
				case UNSOLVED -> counts.unsolved();
			};
		}

	}

	/**
	 * 목록 조건. SQL 조각은 고정 문자열이고 사용자 입력은 모두 parameter다.
	 * @param title 제목 검색어. null이면 없음
	 * @param status 질의응답 상태 필터. null이면 없음
	 * @param mine 내가 쓴 글만
	 * @param answered 내가 답글을 단 질문만(나의 답변)
	 * @param upTo 이 ID 이하의 글만. 쪽 번호 목록이 새 글 때문에 밀리지 않게 한다. null이면 없음
	 */
	record Filter(String title, QnaStatus status, boolean mine, boolean answered, Long upTo) {

		String sql(boolean withStatus) {
			StringBuilder sql = new StringBuilder();
			if (this.title != null) {
				sql.append(" AND p.title LIKE CONCAT('%', :q, '%')");
			}
			if (this.mine) {
				sql.append(" AND p.author_id = :user");
			}
			if (this.answered) {
				sql.append(" AND EXISTS (SELECT 1 FROM posts a WHERE a.parent_post_id = p.post_id AND a.author_id = :user")
					.append(" AND a.deleted_at IS NULL)");
			}
			if (this.upTo != null) {
				sql.append(" AND p.post_id <= :upTo");
			}
			if (withStatus && this.status != null) {
				sql.append(switch (this.status) {
					case ALL -> "";
					case FAQ -> " AND p.is_faq = 1";
					case SOLVED -> " AND p.pro_status = 'Y'";
					case UNSOLVED -> " AND p.pro_status <> 'Y'";
				});
			}
			return sql.toString();
		}

	}

	/** 검사를 마친 글 내용. 일반 텍스트다. */
	record NewText(String title, String body, boolean anonymous, boolean secret) {
	}

	record PostRow(long postId, long boardId, long authorId, String title, String body, String excerpt,
			boolean anonymous, boolean secret, boolean faq, boolean solved, int viewCount, int likeCount,
			int commentCount, int answerCount, Long attachmentGroupId, int version, LocalDateTime createdAt,
			LocalDateTime updatedAt, boolean read, boolean liked) {
	}

	record Locked(long postId, long boardId, long authorId, String title, int viewCount, int likeCount) {
	}

	record State(int answerCount, int version) {
	}

	record StatusCounts(long total, long faq, long solved, long unsolved) {
	}

}
