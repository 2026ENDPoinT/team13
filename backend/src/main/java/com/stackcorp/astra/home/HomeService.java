package com.stackcorp.astra.home;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.api.ActivityCompletions;
import com.stackcorp.astra.assessment.api.ReleasedGrades;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.catalog.api.NotificationCounts;
import com.stackcorp.astra.communication.api.CommunityNoticeDigest;
import com.stackcorp.astra.communication.api.CommunityNoticeDigest.Headline;
import com.stackcorp.astra.communication.api.CommunityNoticeDigest.Headlines;
import com.stackcorp.astra.communication.api.CourseNoticeDigest;
import com.stackcorp.astra.communication.api.MessageCounts;
import com.stackcorp.astra.learning.api.DueActivities;
import com.stackcorp.astra.learning.api.DueActivities.DueActivity;
import com.stackcorp.astra.learning.api.DueActivities.Position;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 화면 집계(ADR-0005)와 할 일 목록. 각 모듈의 공개 API만 부르고 다른 모듈의 테이블을 읽지 않는다.
 * 과목 수와 관계없이 쿼리 수가 같다: 시계 1, 과목 1, 할 일 1(마감 전 활동)과 완료 판정 4 이하, 공지 2(안 읽은 수, 최근 공지), 알림마당
 * 공지 2(최근 공지, 진입 공지), 안 읽은 쪽지 1, 안 읽은 알림 수 1, 새 성적 5 이하. 읽기 전용 transaction 하나로 묶어 연결 하나와 같은
 * snapshot에서 읽는다. 시각은 DB 시계 한 번으로 정해 모든 판정에 같이 쓴다(SC-06).
 * <p>
 * 할 일은 학생으로 수강하는 과목의, 마감 전이고 본인이 아직 끝내지 않은 활동이다(원본 할일 팝업의 "미완료 학습활동"). 끝낸 활동은 목록과
 * 건수에서 뺀다. 완료 표시를 달아 남기지 않는 것은 원본 화면과 frontend가 남은 활동만 보여 주고 건수를 배지로 쓰기 때문이다. 완료 판정은
 * assessment가 정한다({@link ActivityCompletions}). 담당 과목의 활동은 넣지 않는다.
 */
@Service
class HomeService {

	static final int HOME_NOTICES = 5;

	static final int HOME_COMMUNITY_NOTICES = 5;

	/** 진입 공지(읽지 않은 알림마당 고정 공지) 상한. */
	static final int NOTICES_FOR_ME = 5;

	static final int HOME_TODO = 5;

	/** 완료를 거르기 전에 읽는 마감 전 활동 수의 상한. 넘으면 truncated가 true이고 마감이 늦은 쪽이 빠진다. */
	static final int MAX_DUE = 500;

	static final int HOME_GRADES = 5;

	/** 메인 화면에 새 성적으로 보이는 기간. 공개 일시가 이 안이면 보인다. */
	static final int NEW_GRADE_DAYS = 7;

	static final int MAX_TODO_PAGE = 50;

	private final JdbcClient jdbc;

	private final MemberCourses courses;

	private final DueActivities dueActivities;

	private final ActivityCompletions completions;

	private final ReleasedGrades releasedGrades;

	private final CourseNoticeDigest notices;

	private final MessageCounts messages;

	private final NotificationCounts notifications;

	private final CommunityNoticeDigest communityNotices;

	HomeService(JdbcClient jdbc, MemberCourses courses, DueActivities dueActivities, ActivityCompletions completions,
			ReleasedGrades releasedGrades, CourseNoticeDigest notices, MessageCounts messages,
			NotificationCounts notifications, CommunityNoticeDigest communityNotices) {
		this.jdbc = jdbc;
		this.courses = courses;
		this.dueActivities = dueActivities;
		this.completions = completions;
		this.releasedGrades = releasedGrades;
		this.notices = notices;
		this.messages = messages;
		this.notifications = notifications;
		this.communityNotices = communityNotices;
	}

	/**
	 * @param institutionRole session 사용자의 기관 역할 이름. 알림마당 공지의 대상을 정한다
	 */
	@Transactional(readOnly = true)
	HomeView home(long userId, String institutionRole) {
		LocalDateTime now = now();
		List<MemberCourse> mine = this.courses.of(userId);
		Map<Long, MemberCourse> byId = mine.stream()
			.collect(Collectors.toMap(MemberCourse::courseId, Function.identity()));
		List<Long> all = mine.stream().map(MemberCourse::courseId).toList();
		Map<Long, Integer> unread = this.notices.unreadCounts(userId, all, now);
		List<HomeCourse> courseViews = mine.stream()
			.map((c) -> new HomeCourse(c.courseId(), c.key(), c.name(), c.code(), c.section(), c.role().name(),
					unread.getOrDefault(c.courseId(), 0)))
			.toList();
		List<HomeNotice> latest = this.notices.latest(userId, all, now, HOME_NOTICES)
			.stream()
			.map((n) -> new HomeNotice(n.noticeId(), n.courseId(), byId.get(n.courseId()).name(), n.title(), n.pinned(),
					n.publishedAt(), n.read()))
			.toList();
		List<Long> studying = studentCourses(mine);
		TodoList todo = todo(userId, byId, studying, now, null, HOME_TODO);
		List<HomeGrade> grades = this.releasedGrades
			.releasedSince(userId, studying, now.minusDays(NEW_GRADE_DAYS), now, HOME_GRADES)
			.stream()
			.map((g) -> new HomeGrade(g.gradeItemId(), g.courseId(), byId.get(g.courseId()).name(), g.itemType(),
					g.activityId(), g.itemName(), g.releasedAt()))
			.toList();
		Headlines community = this.communityNotices.headlines(userId, institutionRole, now, HOME_COMMUNITY_NOTICES,
				NOTICES_FOR_ME);
		return new HomeView(now, courseViews, latest, community.latest(), community.forMe(), todo, grades,
				this.messages.unread(userId), this.notifications.unread(userId, now));
	}

	/**
	 * @param cursor 앞 응답의 nextCursor. 처음이면 null
	 */
	@Transactional(readOnly = true)
	TodoList todo(long userId, String cursor, int size) {
		if (size < 1 || size > MAX_TODO_PAGE) {
			throw ApiException.invalid("size는 1 이상 " + MAX_TODO_PAGE + " 이하여야 합니다.");
		}
		Position after = decode(cursor);
		LocalDateTime now = now();
		List<MemberCourse> mine = this.courses.of(userId);
		Map<Long, MemberCourse> byId = mine.stream()
			.collect(Collectors.toMap(MemberCourse::courseId, Function.identity()));
		return todo(userId, byId, studentCourses(mine), now, after, size);
	}

	/**
	 * 마감 전 활동을 상한까지 한 번에 읽고 본인이 끝낸 것을 뺀 뒤, 남은 목록에서 after 다음 size개를 고른다. 건수도 남은 목록으로 정하므로
	 * 끝낸 활동은 배지에도 없다. 쿼리 수는 쪽과 관계없다.
	 */
	private TodoList todo(long userId, Map<Long, MemberCourse> byId, List<Long> courseIds, LocalDateTime now,
			Position after, int size) {
		List<DueActivity> rows = this.dueActivities.due(courseIds, now, MAX_DUE + 1);
		boolean truncated = rows.size() > MAX_DUE;
		List<DueActivity> due = truncated ? rows.subList(0, MAX_DUE) : rows;
		Set<Long> done = this.completions.completedBy(userId, due.stream().map(DueActivity::activityId).toList(), now);
		List<DueActivity> remaining = due.stream().filter((a) -> !done.contains(a.activityId())).toList();
		// 한 건 더 골라 다음 쪽이 있는지 안다.
		List<DueActivity> rest = remaining.stream()
			.filter((a) -> after == null || a.position().after(after))
			.limit(size + 1L)
			.toList();
		List<DueActivity> page = rest.size() > size ? rest.subList(0, size) : rest;
		String next = rest.size() > size ? encode(page.getLast().position()) : null;
		List<TodoItem> items = page.stream().map((a) -> {
			MemberCourse course = byId.get(a.courseId());
			return new TodoItem(a.activityId(), a.courseId(), course.name(), course.code(), course.section(), a.type(),
					a.title(), a.openAt(), a.closeAt());
		}).toList();
		return new TodoList(now, items, remaining.size(), next, truncated);
	}

	private static List<Long> studentCourses(List<MemberCourse> mine) {
		return mine.stream().filter((c) -> c.role() == CourseRole.STUDENT).map(MemberCourse::courseId).toList();
	}

	private LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	/** 커서는 마지막 항목의 (마감 일시, 활동 ID)다. 서버 판정에는 쓰지 않고 다음 쪽의 시작 위치로만 쓴다. */
	private static String encode(Position position) {
		String raw = position.closeAt() + "~" + position.activityId();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static Position decode(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int at = raw.indexOf('~');
			return new Position(LocalDateTime.parse(raw.substring(0, at)), Long.parseLong(raw.substring(at + 1)));
		}
		catch (RuntimeException ex) {
			throw ApiException.invalid("cursor가 올바르지 않습니다.");
		}
	}

	/**
	 * @param serverTime 판정에 쓴 DB 시각. 화면의 D-day와 남은 시간은 이 값으로 계산한다
	 * @param communityNotices 알림마당 최근 공지(EP-0191 aside). 고정 공지 먼저. 상세는 {@code GET /api/community/notices/{noticeId}}
	 * @param noticesForMe 읽지 않은 알림마당 고정 공지(진입 공지, EP-0190). {@code POST /api/community/notices/{noticeId}/read}로 빠진다
	 * @param newGrades 최근 {@value #NEW_GRADE_DAYS}일 안에 공개된 본인 성적 항목, 최근 공개순 {@value #HOME_GRADES}개. 점수는 없다
	 * @param unreadMessages 받은함의 안 읽은 쪽지 수(헤더 배지, EP-0239)
	 * @param unreadNotifications 안 읽은 알림 수(헤더 배지, EP-0138). 알림 목록의 안 읽음 필터와 같은 조건이다
	 */
	record HomeView(LocalDateTime serverTime, List<HomeCourse> courses, List<HomeNotice> notices,
			List<Headline> communityNotices, List<Headline> noticesForMe, TodoList todo, List<HomeGrade> newGrades,
			int unreadMessages, int unreadNotifications) {
	}

	/**
	 * @param role 이 과목에서의 역할. INSTRUCTOR|STUDENT
	 * @param unreadNotices 게시된 공지 중 읽음으로 표시하지 않은 수
	 */
	record HomeCourse(long courseId, String key, String name, String code, String section, String role,
			int unreadNotices) {
	}

	record HomeNotice(long noticeId, long courseId, String courseName, String title, boolean pinned,
			LocalDateTime publishedAt, boolean read) {
	}

	/**
	 * @param type 활동 유형(learning_activities.activity_type)
	 */
	record TodoItem(long activityId, long courseId, String courseName, String courseCode, String section, String type,
			String title, LocalDateTime openAt, LocalDateTime closeAt) {
	}

	/**
	 * 새로 공개된 성적 항목. 점수와 의견은 메인 화면에 내보내지 않는다.
	 *
	 * @param itemType assignment, quiz, manual
	 * @param activityId 과제·퀴즈 항목의 학습활동. 직접 입력 항목이면 null
	 * @param releasedAt 점수 공개 일시
	 */
	record HomeGrade(long gradeItemId, long courseId, String courseName, String itemType, Long activityId,
			String itemName, LocalDateTime releasedAt) {
	}

	/**
	 * @param total 지금 남은(마감 전이고 본인이 끝내지 않은) 할 일 전체 수(헤더 배지)
	 * @param nextCursor 다음 쪽이 있으면 그 커서, 없으면 null
	 * @param truncated 마감 전 활동이 {@value #MAX_DUE}개를 넘어 마감이 늦은 쪽을 보지 않았는지
	 */
	record TodoList(LocalDateTime serverTime, List<TodoItem> items, int total, String nextCursor, boolean truncated) {
	}

}
