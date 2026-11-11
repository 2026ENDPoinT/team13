package com.stackcorp.astra.home;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.communication.api.CourseNoticeDigest;
import com.stackcorp.astra.learning.api.DueActivities;
import com.stackcorp.astra.learning.api.DueActivities.DueActivity;
import com.stackcorp.astra.learning.api.DueActivities.Position;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 화면 집계(ADR-0005)와 할 일 목록. 각 모듈의 공개 API만 부르고 다른 모듈의 테이블을 읽지 않는다.
 * 과목 수와 관계없이 쿼리 수가 같다: 시계 1, 과목 1, 할 일 2(목록, 건수), 공지 2(안 읽은 수, 최근 공지).
 * 읽기 전용 transaction 하나로 묶어 연결 하나와 같은 snapshot에서 읽는다. 시각은 DB 시계 한 번으로 정해 모든 판정에 같이 쓴다(SC-06).
 * 할 일은 학생으로 수강하는 과목의 활동만이다. 담당 과목의 활동은 넣지 않는다.
 */
@Service
class HomeService {

	static final int HOME_NOTICES = 5;

	static final int HOME_TODO = 5;

	static final int MAX_TODO_PAGE = 50;

	private final JdbcClient jdbc;

	private final MemberCourses courses;

	private final DueActivities dueActivities;

	private final CourseNoticeDigest notices;

	HomeService(JdbcClient jdbc, MemberCourses courses, DueActivities dueActivities, CourseNoticeDigest notices) {
		this.jdbc = jdbc;
		this.courses = courses;
		this.dueActivities = dueActivities;
		this.notices = notices;
	}

	@Transactional(readOnly = true)
	HomeView home(long userId) {
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
		TodoList todo = todo(byId, studentCourses(mine), now, null, HOME_TODO);
		return new HomeView(now, courseViews, latest, todo);
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
		return todo(byId, studentCourses(mine), now, after, size);
	}

	private TodoList todo(Map<Long, MemberCourse> byId, List<Long> courseIds, LocalDateTime now, Position after,
			int size) {
		// 한 건 더 읽어 다음 쪽이 있는지 안다.
		List<DueActivity> rows = this.dueActivities.due(courseIds, now, after, size + 1);
		List<DueActivity> page = rows.size() > size ? rows.subList(0, size) : rows;
		String next = rows.size() > size ? encode(page.getLast().position()) : null;
		List<TodoItem> items = page.stream().map((a) -> {
			MemberCourse course = byId.get(a.courseId());
			return new TodoItem(a.activityId(), a.courseId(), course.name(), course.code(), course.section(), a.type(),
					a.title(), a.openAt(), a.closeAt());
		}).toList();
		return new TodoList(now, items, this.dueActivities.count(courseIds, now), next);
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
	 */
	record HomeView(LocalDateTime serverTime, List<HomeCourse> courses, List<HomeNotice> notices, TodoList todo) {
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
	 * @param total 지금 남은 할 일 전체 수(헤더 배지)
	 * @param nextCursor 다음 쪽이 있으면 그 커서, 없으면 null
	 */
	record TodoList(LocalDateTime serverTime, List<TodoItem> items, int total, String nextCursor) {
	}

}
