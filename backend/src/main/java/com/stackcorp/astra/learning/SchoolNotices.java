package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.catalog.api.SchoolCourseNotices;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolSessions;
import com.stackcorp.astra.shared.ApiException;

/**
 * 학교 e-Class 강의실 공지(ADR-0043). 목록은 학교 쪽 읽음 표시를 바꾸지 않는 공지 목록 화면만 읽어 둔다(로그인 때 가져오기와, 학생이
 * 공지 목록을 열었을 때 {@code listInterval}이 지났으면). 상세는 그 학생이 그 공지를 열었을 때만 그 학생의 학교 session으로 읽는다(학교에서
 * 그 학생이 읽은 것으로 바뀐다). 본문은 저장하지 않는다. 첨부는 학교 파일({@link SchoolFileStore}, 유형 {@value #NOTICE})로 둔다.
 * <p>
 * 안 읽은 공지 수는 학생 본인의 학교 목록에 나온 안 읽음 표시에서 세고, STack에서 연 공지는 읽음으로 바꾼다. 수를 세려고 공지를 열지
 * 않는다.
 */
@Component
class SchoolNotices implements SchoolCourseNotices {

	static final String NOTICE = "notice";

	private static final Logger log = LoggerFactory.getLogger(SchoolNotices.class);

	private final JdbcClient jdbc;

	private final SchoolContent content;

	private final SchoolFileStore files;

	private final SchoolSessions sessions;

	private final SchoolContentProperties properties;

	private final TransactionTemplate transaction;

	SchoolNotices(JdbcClient jdbc, SchoolContent content, SchoolFileStore files, SchoolSessions sessions,
			SchoolContentProperties properties, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.content = content;
		this.files = files;
		this.sessions = sessions;
		this.properties = properties;
		// 목록을 부르는 쪽이 읽기 전용 transaction 안에 있어도 따로 commit한다.
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public List<SchoolNoticeItem> list(Actor actor, long courseId) {
		Optional<String> key = this.content.courseKey(actor.userId(), courseId);
		if (key.isEmpty()) {
			return List.of();
		}
		if (listDue(courseId, actor.userId())) {
			this.sessions.of(actor.sessionId()).ifPresent((rooms) -> refresh(actor, courseId, key.get(), rooms));
		}
		return rows(courseId, actor.userId(), null).stream()
			.map((row) -> new SchoolNoticeItem(row.id(), row.title(), row.excerpt(), row.postedAt(), row.viewCount(),
					row.read(), row.expired(), row.schoolUrl()))
			.toList();
	}

	@Override
	public Map<Long, Integer> unreadCounts(long userId, Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT n.course_id, COUNT(*) AS unread
				  FROM school_notice_states s JOIN school_notices n ON n.school_notice_id = s.school_notice_id
				 WHERE s.user_id = :user AND s.read_yn = 0 AND n.course_id IN (:courses)
				 GROUP BY n.course_id
				""")
			.param("user", userId)
			.param("courses", new ArrayList<>(courseIds))
			.query((rs) -> {
				counts.put(rs.getLong("course_id"), rs.getInt("unread"));
			});
		return counts;
	}

	/**
	 * 공지 하나를 연다. 그 학생의 학교 session으로 학교 상세와 첨부 목록을 읽는다(학교에서 그 학생이 읽은 것으로 바뀐다).
	 */
	SchoolNoticeView open(Actor actor, long courseId, long schoolNoticeId) {
		this.content.requireMember(actor, courseId);
		NoticeRow row = rows(courseId, actor.userId(), schoolNoticeId).stream()
			.findFirst()
			.orElseThrow(ApiException::notFound);
		SchoolPost post = this.content.read(actor, courseId, (rooms, key) -> rooms.notice(key, row.itemId(),
				this.properties.requestTimeout(), this.properties.readBudget()));
		this.files.replaceFor(courseId, NOTICE, row.itemId(), post.files());
		this.jdbc.sql("""
				INSERT INTO school_notice_states (school_notice_id, user_id, read_yn, synced_at)
				VALUES (:notice, :user, 1, CURRENT_TIMESTAMP(3)) AS new
				ON DUPLICATE KEY UPDATE read_yn = 1, synced_at = new.synced_at
				""").param("notice", row.id()).param("user", actor.userId()).update();
		List<SchoolFileStore.Row> attached = this.files.of(courseId, NOTICE, List.of(row.itemId()));
		return new SchoolNoticeView(SchoolContent.ACTIVITY_PREFIX + row.id(), SchoolContent.ORIGIN, post.title(),
				post.body(), false, post.postedAt() != null ? post.postedAt() : row.postedAt(), true, null,
				post.author(), false, row.viewCount() == null ? 0 : row.viewCount(), null, null, null, true, false, null,
				null, row.expired(), SchoolContent.fileViews(attached, this.content.summarized(attached)),
				row.schoolUrl());
	}

	/** 학교 공지 목록을 그 학생의 학교 session으로 읽어 둔다. 실패는 로그만 남기고 둔 목록을 쓴다. */
	private void refresh(Actor actor, long courseId, String courseKey, SchoolCourseRooms rooms) {
		try {
			long started = System.nanoTime();
			List<SchoolNotice> notices = rooms.notices(courseKey, this.properties.requestTimeout(),
					this.properties.readBudget());
			save(courseId, actor.userId(), notices);
			log.info("School notice list read: course={} notices={} ms={}", courseId, notices.size(),
					(System.nanoTime() - started) / 1_000_000);
		}
		catch (SchoolLoginException ex) {
			if (ex.kind() == SchoolLoginException.Kind.EXPIRED) {
				this.sessions.forget(actor.sessionId());
			}
			log.warn("School notice list read failed: course={} reason={}", courseId, ex.getMessage());
		}
	}

	/**
	 * 학생 한 명의 학교 공지 목록으로 맞춘다. 공지는 넣거나 고치고 그 학생의 읽음 표시를 둔다. 목록에 없는 공지(와 그 첨부)는 지운다(학교
	 * 목록은 과목의 모든 공지를 보여 준다).
	 */
	void save(long courseId, long userId, List<SchoolNotice> notices) {
		this.transaction.executeWithoutResult((tx) -> {
			int order = 0;
			for (SchoolNotice notice : notices) {
				this.jdbc.sql("""
						INSERT INTO school_notices (course_id, school_item_id, title, excerpt, posted_at, view_count,
						                            expired_yn, sort_order, school_url, listed_at)
						VALUES (:course, :item, :title, :excerpt, :posted, :views, :expired, :order, :url,
						        CURRENT_TIMESTAMP(3)) AS new
						ON DUPLICATE KEY UPDATE title = new.title, excerpt = new.excerpt,
						    posted_at = COALESCE(new.posted_at, school_notices.posted_at),
						    view_count = COALESCE(new.view_count, school_notices.view_count), expired_yn = new.expired_yn,
						    sort_order = new.sort_order, school_url = new.school_url, listed_at = new.listed_at
						""")
					.param("course", courseId)
					.param("item", notice.itemId())
					.param("title", notice.title())
					.param("excerpt", notice.excerpt())
					.param("posted", notice.postedAt())
					.param("views", notice.viewCount())
					.param("expired", notice.expired())
					.param("order", order++)
					.param("url", notice.url())
					.update();
				this.jdbc.sql("""
						INSERT INTO school_notice_states (school_notice_id, user_id, read_yn, synced_at)
						SELECT school_notice_id, :user, :read, CURRENT_TIMESTAMP(3) FROM school_notices
						 WHERE course_id = :course AND school_item_id = :item
						ON DUPLICATE KEY UPDATE read_yn = VALUES(read_yn), synced_at = VALUES(synced_at)
						""")
					.param("user", userId)
					.param("read", notice.read())
					.param("course", courseId)
					.param("item", notice.itemId())
					.update();
			}
			Set<String> seen = new HashSet<>();
			notices.forEach((n) -> seen.add(n.itemId()));
			List<String> gone = this.jdbc
				.sql("SELECT school_item_id FROM school_notices WHERE course_id = :course")
				.param("course", courseId)
				.query(String.class)
				.list()
				.stream()
				.filter((item) -> !seen.contains(item))
				.toList();
			if (!gone.isEmpty()) {
				this.files.removeFor(courseId, NOTICE, gone);
				this.jdbc.sql("DELETE FROM school_notices WHERE course_id = :course AND school_item_id IN (:items)")
					.param("course", courseId)
					.param("items", gone)
					.update();
			}
			this.jdbc.sql("""
					INSERT INTO school_notice_lists (course_id, user_id, listed_at)
					VALUES (:course, :user, CURRENT_TIMESTAMP(3)) AS new
					ON DUPLICATE KEY UPDATE listed_at = new.listed_at
					""").param("course", courseId).param("user", userId).update();
		});
	}

	private boolean listDue(long courseId, long userId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM school_notice_lists
				 WHERE course_id = :course AND user_id = :user
				   AND listed_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("seconds", this.properties.listInterval().toSeconds())
			.query(Integer.class)
			.single() == 0;
	}

	private List<NoticeRow> rows(long courseId, long userId, Long schoolNoticeId) {
		return this.jdbc.sql("""
				SELECT n.school_notice_id, n.school_item_id, n.title, n.excerpt, n.posted_at, n.view_count, n.expired_yn,
				       n.school_url, s.read_yn
				  FROM school_notices n
				  LEFT JOIN school_notice_states s ON s.school_notice_id = n.school_notice_id AND s.user_id = :user
				 WHERE n.course_id = :course AND (:id IS NULL OR n.school_notice_id = :id)
				 ORDER BY n.sort_order, n.school_notice_id
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("id", schoolNoticeId)
			.query((rs, n) -> {
				int views = rs.getInt("view_count");
				Integer viewCount = rs.wasNull() ? null : views;
				boolean read = rs.getBoolean("read_yn");
				read = rs.wasNull() || read;
				return new NoticeRow(rs.getLong("school_notice_id"), rs.getString("school_item_id"),
						rs.getString("title"), rs.getString("excerpt"), rs.getObject("posted_at", LocalDateTime.class),
						viewCount, rs.getBoolean("expired_yn"), rs.getString("school_url"), read);
			})
			.list();
	}

	private record NoticeRow(long id, String itemId, String title, String excerpt, LocalDateTime postedAt,
			Integer viewCount, boolean expired, String schoolUrl, boolean read) {
	}

	/**
	 * 학교 공지 상세. 서비스 공지 상세({@code NoticeView})와 같은 이름의 값에 {@code origin}, {@code expired}, {@code files},
	 * {@code schoolUrl}을 더한다. 작성자 계정·아바타·version·열람 수는 없다(null). 연 학생은 학교에서 읽음이 되므로 {@code read}는
	 * true다. "공지를 확인했습니다"는 STack에서 보내지 않는다({@code confirmed} false, 확인은 학교에서).
	 * @param noticeId {@code "school-<id>"}
	 * @param authorName 학교 화면의 작성자 표시. 없으면 null
	 * @param viewCount 학교 목록의 조회수(연 뒤 학교에서 하나 늘었을 수 있다)
	 * @param expired 학교의 공지 만료 표시
	 * @param files 첨부. 자료 파일과 같은 모양이다({@code fileId}는 받기 전 {@code "school-file-<id>"})
	 */
	record SchoolNoticeView(String noticeId, String origin, String title, String body, boolean pinned,
			LocalDateTime publishedAt, boolean published, Long authorId, String authorName, boolean authorHasPhoto,
			int viewCount, LocalDateTime createdAt, LocalDateTime updatedAt, Integer version, boolean read,
			boolean confirmed, Integer readCount, Integer confirmCount, boolean expired,
			List<SchoolContent.SchoolFileView> files, String schoolUrl) {
	}

}
