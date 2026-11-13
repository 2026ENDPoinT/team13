package com.stackcorp.astra.home;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
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
import com.stackcorp.astra.learning.api.ActivityTargets;
import com.stackcorp.astra.learning.api.ActivityTargets.Target;
import com.stackcorp.astra.learning.api.AttendanceSummaries;
import com.stackcorp.astra.learning.api.AttendanceSummaries.AttendanceSummary;
import com.stackcorp.astra.learning.api.DueActivities;
import com.stackcorp.astra.learning.api.DueActivities.DueActivity;
import com.stackcorp.astra.learning.api.DueActivities.Position;
import com.stackcorp.astra.learning.api.LectureProgressSummaries;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 화면 집계(ADR-0005)와 할 일 목록. 각 모듈의 공개 API만 부르고 다른 모듈의 테이블을 읽지 않는다.
 * 과목 수와 관계없이 쿼리 수가 같다: 시계 1, 과목 1, 할 일 1(마감 전 활동)과 완료 판정 4 이하와 평가 ID 1, 공지 2(안 읽은 수, 최근
 * 공지), 과목 카드의 영상 진도 1과 출석 1, 알림마당 공지 2(최근 공지, 진입 공지), 안 읽은 쪽지 1, 안 읽은 알림 수 1, 새 성적 5 이하.
 * 읽기 전용 transaction 하나로 묶어 연결 하나와 같은 snapshot에서 읽는다. 시각은 DB 시계 한 번으로 정해 모든 판정에 같이 쓴다(SC-06).
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

	private final ActivityTargets targets;

	private final LectureProgressSummaries progress;

	private final AttendanceSummaries attendance;

	HomeService(JdbcClient jdbc, MemberCourses courses, DueActivities dueActivities, ActivityCompletions completions,
			ReleasedGrades releasedGrades, CourseNoticeDigest notices, MessageCounts messages,
			NotificationCounts notifications, CommunityNoticeDigest communityNotices, ActivityTargets targets,
			LectureProgressSummaries progress, AttendanceSummaries attendance) {
		this.jdbc = jdbc;
		this.courses = courses;
		this.dueActivities = dueActivities;
		this.completions = completions;
		this.releasedGrades = releasedGrades;
		this.notices = notices;
		this.messages = messages;
		this.notifications = notifications;
		this.communityNotices = communityNotices;
		this.targets = targets;
		this.progress = progress;
		this.attendance = attendance;
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
		List<Long> studying = studentCourses(mine);
		Map<Long, Integer> unread = this.notices.unreadCounts(userId, all, now);
		Map<Long, Integer> percents = this.progress.percentOf(userId, studying, now);
		Map<Long, AttendanceSummary> attendances = this.attendance.of(userId, studying);
		List<HomeCourse> courseViews = mine.stream().map((c) -> {
			boolean student = c.role() == CourseRole.STUDENT;
			CourseWeek week = CourseWeek.of(c.startDate(), c.endDate(), now.toLocalDate());
			AttendanceSummary summary = student ? attendances.get(c.courseId()) : null;
			return new HomeCourse(c.courseId(), c.key(), c.name(), c.code(), c.section(), c.role().name(),
					unread.getOrDefault(c.courseId(), 0), week.current(), week.count(),
					student ? percents.get(c.courseId()) : null,
					summary == null ? null : CourseAttendance.of(summary));
		}).toList();
		List<HomeNotice> latest = this.notices.latest(userId, all, now, HOME_NOTICES)
			.stream()
			.map((n) -> new HomeNotice(n.noticeId(), n.courseId(), byId.get(n.courseId()).name(), n.title(), n.pinned(),
					n.publishedAt(), n.read()))
			.toList();
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
	 * @param courseId 이 과목의 할 일만. 없으면 수강하는 모든 과목. 내 과목이 아니면 404이고, 담당 과목이면 할 일이 없다
	 * @param cursor 앞 응답의 nextCursor. 처음이면 null. 과목을 고른 목록의 cursor는 같은 과목으로만 이어서 보낸다
	 */
	@Transactional(readOnly = true)
	TodoList todo(long userId, Long courseId, String cursor, int size) {
		if (size < 1 || size > MAX_TODO_PAGE) {
			throw ApiException.invalid("한 번에 받을 수 있는 할 일은 1개 이상 " + MAX_TODO_PAGE + "개 이하입니다.");
		}
		Position after = decode(cursor);
		LocalDateTime now = now();
		List<MemberCourse> mine = this.courses.of(userId);
		Map<Long, MemberCourse> byId = mine.stream()
			.collect(Collectors.toMap(MemberCourse::courseId, Function.identity()));
		return todo(userId, byId, studying(mine, byId, courseId), now, after, size);
	}

	/** 할 일 수만(헤더·강의실 배지). 목록의 total, truncated와 같은 값이고 쿼리 수도 목록과 같다(항목 조회 제외). */
	@Transactional(readOnly = true)
	TodoCount todoCount(long userId, Long courseId) {
		LocalDateTime now = now();
		List<MemberCourse> mine = this.courses.of(userId);
		Map<Long, MemberCourse> byId = mine.stream()
			.collect(Collectors.toMap(MemberCourse::courseId, Function.identity()));
		Remaining remaining = remaining(userId, studying(mine, byId, courseId), now);
		return new TodoCount(now, remaining.items().size(), remaining.truncated());
	}

	// 할 일을 계산할 과목. 고른 과목이 내 과목이 아니면 404다(SC-03). 담당 과목이면 비어 있다.
	private static List<Long> studying(List<MemberCourse> mine, Map<Long, MemberCourse> byId, Long courseId) {
		if (courseId == null) {
			return studentCourses(mine);
		}
		MemberCourse course = byId.get(courseId);
		if (course == null) {
			throw ApiException.notFound();
		}
		return course.role() == CourseRole.STUDENT ? List.of(courseId) : List.of();
	}

	/**
	 * 마감 전 활동을 상한까지 한 번에 읽고 본인이 끝낸 것을 뺀 뒤, 남은 목록에서 after 다음 size개를 고른다. 건수도 남은 목록으로 정하므로
	 * 끝낸 활동은 배지에도 없다. 쪽의 평가 활동에는 과제·퀴즈·설문 ID를 붙인다. 쿼리 수는 쪽과 관계없다.
	 */
	private TodoList todo(long userId, Map<Long, MemberCourse> byId, List<Long> courseIds, LocalDateTime now,
			Position after, int size) {
		Remaining remaining = remaining(userId, courseIds, now);
		// 한 건 더 골라 다음 쪽이 있는지 안다.
		List<DueActivity> rest = remaining.items()
			.stream()
			.filter((a) -> after == null || a.position().after(after))
			.limit(size + 1L)
			.toList();
		List<DueActivity> page = rest.size() > size ? rest.subList(0, size) : rest;
		String next = rest.size() > size ? encode(page.getLast().position()) : null;
		Map<Long, Target> targets = this.targets.of(page.stream().map(DueActivity::activityId).toList());
		List<TodoItem> items = page.stream().map((a) -> {
			MemberCourse course = byId.get(a.courseId());
			Target target = targets.get(a.activityId());
			return new TodoItem(a.activityId(), a.courseId(), course.name(), course.code(), course.section(), a.type(),
					a.title(), a.openAt(), a.closeAt(), targetId(target, "assignment"), targetId(target, "quiz"),
					targetId(target, "survey"));
		}).toList();
		return new TodoList(now, items, remaining.items().size(), next, remaining.truncated());
	}

	private Remaining remaining(long userId, List<Long> courseIds, LocalDateTime now) {
		List<DueActivity> rows = this.dueActivities.due(courseIds, now, MAX_DUE + 1);
		boolean truncated = rows.size() > MAX_DUE;
		List<DueActivity> due = truncated ? rows.subList(0, MAX_DUE) : rows;
		Set<Long> done = this.completions.completedBy(userId, due.stream().map(DueActivity::activityId).toList(), now);
		return new Remaining(due.stream().filter((a) -> !done.contains(a.activityId())).toList(), truncated);
	}

	static Long targetId(Target target, String kind) {
		return target != null && target.kind().equals(kind) ? target.id() : null;
	}

	private record Remaining(List<DueActivity> items, boolean truncated) {
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
	 * @param currentWeek 수업 기간({@link MemberCourse#startDate}~{@link MemberCourse#endDate})의 몇째 주인지. 시작일부터 7일씩 1주이고,
	 * 시작 전이면 0, 끝난 뒤면 weekCount다. 오늘은 DB 시계(기관 시간대)로 정한다. 기간이 없으면 null
	 * @param weekCount 수업 기간의 주 수(일수를 7로 나눠 올림). 기간이 없으면 null
	 * @param progressPercent 학생 본인의 영상 진도율(0~100, 버림). 진도 화면과 같이 학생에게 열린 영상 콘텐츠 가운데 출석인정을 채운 비율이다.
	 * 열린 콘텐츠가 없거나 담당 과목이면 null
	 * @param attendance 학생 본인의 출석. 담당 과목이면 null
	 */
	record HomeCourse(long courseId, String key, String name, String code, String section, String role,
			int unreadNotices, Integer currentWeek, Integer weekCount, Integer progressPercent,
			CourseAttendance attendance) {
	}

	/**
	 * @param attended 출석, 지각, 공결로 판정한 차시 수
	 * @param total 판정한 차시 수(휴강 제외). 아직 판정하지 않은 차시는 세지 않는다
	 */
	record CourseAttendance(int attended, int total) {

		static CourseAttendance of(AttendanceSummary summary) {
			return new CourseAttendance(summary.present() + summary.late() + summary.excused(),
					summary.sessions() - summary.unmarked());
		}

	}

	/** 수업 기간 안의 주. 시작일부터 7일씩 1주다. */
	record CourseWeek(Integer current, Integer count) {

		static final CourseWeek UNKNOWN = new CourseWeek(null, null);

		static CourseWeek of(LocalDate start, LocalDate end, LocalDate today) {
			if (start == null || end == null || end.isBefore(start)) {
				return UNKNOWN;
			}
			int count = (int) ((ChronoUnit.DAYS.between(start, end) + 7) / 7);
			if (today.isBefore(start)) {
				return new CourseWeek(0, count);
			}
			if (today.isAfter(end)) {
				return new CourseWeek(count, count);
			}
			return new CourseWeek((int) (ChronoUnit.DAYS.between(start, today) / 7) + 1, count);
		}

	}

	record HomeNotice(long noticeId, long courseId, String courseName, String title, boolean pinned,
			LocalDateTime publishedAt, boolean read) {
	}

	/**
	 * @param type 활동 유형(learning_activities.activity_type)
	 * @param assignmentId 과제 활동만. 과제 화면의 ID
	 * @param quizId 퀴즈 활동만
	 * @param surveyId 강의실 설문 활동만
	 */
	record TodoItem(long activityId, long courseId, String courseName, String courseCode, String section, String type,
			String title, LocalDateTime openAt, LocalDateTime closeAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long assignmentId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long quizId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long surveyId) {
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

	/**
	 * @param total 목록의 total과 같다
	 * @param truncated 목록의 truncated와 같다
	 */
	record TodoCount(LocalDateTime serverTime, int total, boolean truncated) {
	}

}
