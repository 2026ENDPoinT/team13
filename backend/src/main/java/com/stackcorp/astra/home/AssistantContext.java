package com.stackcorp.astra.home;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.assessment.api.ActivityCompletions;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.communication.api.CourseNoticeDigest;
import com.stackcorp.astra.communication.api.CourseNoticeDigest.NoticeHeadline;
import com.stackcorp.astra.home.HomeService.TodoItem;
import com.stackcorp.astra.home.HomeService.TodoList;
import com.stackcorp.astra.learning.api.ActivityTargets;
import com.stackcorp.astra.learning.api.ActivityTargets.Target;
import com.stackcorp.astra.learning.api.CalendarActivities;
import com.stackcorp.astra.learning.api.CalendarActivities.CalendarActivity;
import com.stackcorp.astra.learning.api.SchoolCourseItems;
import com.stackcorp.astra.learning.api.SchoolCourseItems.SchoolActivity;
import com.stackcorp.astra.learning.api.SchoolCourseItems.SchoolNotice;

/**
 * 도우미가 쓰는 로그인한 사용자 본인의 자료(ADR-0044). 화면이 보낸 과목이나 활동 목록은 쓰지 않는다. 모든 값은 이 사용자의 과목
 * ({@link MemberCourses})에서만 나온다.
 * <ul>
 * <li>기본({@link #base}): 끝나지 않은 내 과목과 할 일(서비스 활동과 학교에서 가져온 활동, 할 일 목록과 같은 계산). 규칙 답은 이것만 쓴다.</li>
 * <li>모델용({@link #extend}): 앞으로 {@value #DEADLINE_DAYS}일 안의 마감(끝낸 것 포함), 지금 과목이나 문장에 나온 과목의 이번 주와
 * 다음 주 마감 활동과 그 과목에서 가져온 학교 활동(영상, 자료, 과제 등, 학교 주차 기간 기준), 최근 공지(서비스 공지와 가져온 학교
 * 공지).</li>
 * </ul>
 * 항목마다 화면이 그대로 따라갈 수 있는 주소를 함께 정한다({@link AssistantLinks}).
 */
@Service
class AssistantContext {

	static final int MAX_COURSES = 30;

	static final int MAX_TODO = 30;

	static final int MAX_ITEMS = 60;

	static final int DEADLINE_DAYS = 7;

	static final int NOTICES = 5;

	private static final int PER_SOURCE = 15;

	private final HomeService home;

	private final MemberCourses courses;

	private final CalendarActivities calendar;

	private final ActivityCompletions completions;

	private final ActivityTargets targets;

	private final CourseNoticeDigest notices;

	private final SchoolCourseItems school;

	AssistantContext(HomeService home, MemberCourses courses, CalendarActivities calendar,
			ActivityCompletions completions, ActivityTargets targets, CourseNoticeDigest notices,
			SchoolCourseItems school) {
		this.home = home;
		this.courses = courses;
		this.calendar = calendar;
		this.completions = completions;
		this.targets = targets;
		this.notices = notices;
		this.school = school;
	}

	/** 과목 하나. ref는 이 요청 안에서만 쓰는 이름(C1, C2 …)이다. */
	record CourseRef(String ref, MemberCourse course) {

		AssistantLinks.Action room() {
			return AssistantLinks.room(this.course);
		}

	}

	/**
	 * 활동이나 공지 하나. ref는 이 요청 안에서만 쓰는 이름(A1, A2 …)이다.
	 *
	 * @param kind video, material, report, test, survey, zoom, notice, other
	 * @param status todo(마감 전이고 끝내지 않았다), done(끝냈다), open(담당 과목이거나 판정하지 않는다), notice
	 * @param activityKeys 화면이 보내는 활동 값 중 이 항목을 가리키는 것(활동 ID, {@code srv-<n>}, {@code school-<n>})
	 */
	record ItemRef(String ref, long courseId, String kind, String type, String title, LocalDateTime closeAt,
			String status, AssistantLinks.Action action, Set<String> activityKeys) {
	}

	/** 한 요청에서 쓰는 자료. 항목은 할 일, 마감, 과목 활동, 공지 순서로 쌓인다. */
	static final class Snapshot {

		final LocalDateTime now;

		final List<CourseRef> courses = new ArrayList<>();

		final Map<Long, CourseRef> byId = new LinkedHashMap<>();

		final List<ItemRef> items = new ArrayList<>();

		/** 할 일(마감 임박순). items의 앞부분과 같다. */
		final List<ItemRef> todo = new ArrayList<>();

		/** 할 일 전체 수. todo는 앞 {@value AssistantContext#MAX_TODO}개만이다. */
		int todoTotal;

		private final Set<String> keys = new HashSet<>();

		Snapshot(LocalDateTime now) {
			this.now = now;
		}

		LocalDate today() {
			return this.now.toLocalDate();
		}

		private boolean full() {
			return this.items.size() >= MAX_ITEMS;
		}

		// key는 같은 항목이 두 출처(할 일과 마감)에서 두 번 들어가지 않게 한다.
		private ItemRef add(String key, long courseId, String kind, String type, String title, LocalDateTime closeAt,
				String status, AssistantLinks.Action action, Set<String> activityKeys) {
			if (full() || !this.keys.add(key) || !this.byId.containsKey(courseId)) {
				return null;
			}
			ItemRef item = new ItemRef("A" + (this.items.size() + 1), courseId, kind, type, title, closeAt, status,
					action, activityKeys);
			this.items.add(item);
			return item;
		}

	}

	/** 내 과목(끝난 과목 제외)과 할 일. */
	@Transactional(readOnly = true)
	Snapshot base(long userId) {
		TodoList todo = this.home.todo(userId, null, null, MAX_TODO);
		Snapshot snapshot = new Snapshot(todo.serverTime());
		for (MemberCourse course : this.courses.of(userId)) {
			if (snapshot.courses.size() >= MAX_COURSES) {
				break;
			}
			if (course.endDate() != null && course.endDate().isBefore(snapshot.today())) {
				continue;
			}
			CourseRef ref = new CourseRef("C" + (snapshot.courses.size() + 1), course);
			snapshot.courses.add(ref);
			snapshot.byId.put(course.courseId(), ref);
		}
		snapshot.todoTotal = todo.total();
		for (TodoItem t : todo.items()) {
			CourseRef course = snapshot.byId.get(t.courseId());
			if (course == null) {
				continue;
			}
			ItemRef item;
			if (t.activityId() instanceof String school) {
				long schoolId = Long.parseLong(school.substring("school-".length()));
				item = snapshot.add(school, t.courseId(), AssistantLinks.kindOf(t.type()), t.type(), t.title(),
						t.closeAt(), "todo",
						AssistantLinks.school(course.course(), t.type(), schoolId, t.title(), t.schoolUrl()),
						Set.of(school));
			}
			else {
				long activityId = ((Number) t.activityId()).longValue();
				Long target = t.assignmentId() != null ? t.assignmentId()
						: t.quizId() != null ? t.quizId() : t.surveyId();
				item = snapshot.add("n" + activityId, t.courseId(), AssistantLinks.kindOf(t.type()), t.type(),
						t.title(), t.closeAt(), "todo",
						AssistantLinks.activity(course.course(), t.type(), activityId, target, t.title()),
						nativeKeys(activityId, target));
			}
			if (item != null) {
				snapshot.todo.add(item);
			}
		}
		return snapshot;
	}

	/**
	 * 모델에 줄 자료를 더한다.
	 * @param focus 지금 과목이나 문장에 나온 과목. 없으면 null
	 */
	@Transactional(readOnly = true)
	void extend(Snapshot snapshot, long userId, CourseRef focus) {
		LocalDateTime now = snapshot.now;
		List<Long> taught = new ArrayList<>();
		List<Long> enrolled = new ArrayList<>();
		snapshot.courses.forEach((c) -> (c.course().role() == CourseRole.STUDENT ? enrolled : taught)
			.add(c.course().courseId()));
		List<CalendarActivity> deadlines = this.calendar.closingBetween(taught, enrolled, now, now,
				now.plusDays(DEADLINE_DAYS), PER_SOURCE);
		List<CalendarActivity> week = List.of();
		if (focus != null) {
			long id = focus.course().courseId();
			boolean student = focus.course().role() == CourseRole.STUDENT;
			LocalDateTime from = snapshot.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).atStartOfDay();
			week = this.calendar.closingBetween(student ? List.of() : List.of(id), student ? List.of(id) : List.of(),
					now, from, from.plusDays(14), PER_SOURCE);
		}
		List<SchoolActivity> schoolWeek = List.of();
		LocalDateTime weekStart = snapshot.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).atStartOfDay();
		if (focus != null && importedFor(focus.course())) {
			schoolWeek = this.school.activities(userId, focus.course().courseId(), weekStart, weekStart.plusDays(14),
					PER_SOURCE);
		}
		List<CalendarActivity> all = new ArrayList<>(deadlines);
		all.addAll(week);
		Set<Long> students = new HashSet<>(enrolled);
		List<Long> studentIds = all.stream()
			.filter((a) -> students.contains(a.courseId()))
			.map(CalendarActivity::activityId)
			.distinct()
			.toList();
		Set<Long> done = studentIds.isEmpty() ? Set.of() : this.completions.completedBy(userId, studentIds, now);
		Map<Long, Target> targetIds = this.targets.of(all.stream().map(CalendarActivity::activityId).distinct().toList());
		for (CalendarActivity a : all) {
			CourseRef course = snapshot.byId.get(a.courseId());
			if (course == null) {
				continue;
			}
			Target target = targetIds.get(a.activityId());
			Long targetId = target == null ? null : target.id();
			String status = !students.contains(a.courseId()) ? "open"
					: done.contains(a.activityId()) ? "done" : a.closeAt().isAfter(now) ? "todo" : "closed";
			snapshot.add("n" + a.activityId(), a.courseId(), AssistantLinks.kindOf(a.type()), a.type(), a.title(),
					a.closeAt(), status, AssistantLinks.activity(course.course(), a.type(), a.activityId(), targetId, a.title()),
					nativeKeys(a.activityId(), targetId));
		}
		for (SchoolActivity a : schoolWeek) {
			CourseRef course = snapshot.byId.get(a.courseId());
			String ref = "school-" + a.schoolActivityId();
			snapshot.add(ref, a.courseId(), AssistantLinks.kindOf(a.schoolType()), a.schoolType(), a.title(),
					a.closeAt(), a.done() ? "done" : a.closed() ? "closed" : "todo",
					AssistantLinks.school(course.course(), a.schoolType(), a.schoolActivityId(), a.title(),
							a.schoolUrl()),
					Set.of(ref));
		}
		List<NoticeHeadline> headlines = new ArrayList<>(
				this.notices.latest(userId, snapshot.byId.keySet(), now, NOTICES));
		if (focus != null) {
			headlines.addAll(this.notices.latest(userId, List.of(focus.course().courseId()), now, NOTICES));
		}
		for (NoticeHeadline n : headlines) {
			CourseRef course = snapshot.byId.get(n.courseId());
			if (course != null) {
				snapshot.add("notice" + n.noticeId(), n.courseId(), "notice", "notice", n.title(), null, "notice",
						AssistantLinks.notice(course.course(), n.noticeId(), false, n.title()), Set.of());
			}
		}
		// 학교 공지는 학교 과목 키가 있는 수강 과목의 학생에게만 보인다(SchoolNotices와 같은 조건).
		List<Long> imported = snapshot.courses.stream()
			.filter((c) -> importedFor(c.course()))
			.map((c) -> c.course().courseId())
			.toList();
		for (SchoolNotice n : this.school.notices(imported, NOTICES)) {
			CourseRef course = snapshot.byId.get(n.courseId());
			snapshot.add("school-notice" + n.schoolNoticeId(), n.courseId(), "notice", "notice", n.title(), null,
					"notice", AssistantLinks.notice(course.course(), n.schoolNoticeId(), true, n.title()), Set.of());
		}
	}

	// 학교에서 가져온 행은 학교 과목 키가 있는 과목의 학생에게만 있다(learning SchoolContent.courseKey와 같은 조건).
	private static boolean importedFor(MemberCourse course) {
		return course.role() == CourseRole.STUDENT && course.key() != null;
	}

	/** 지금 과목: 화면이 보낸 과목이 내 과목이면 그것, 아니면 문장에 이름이나 코드가 나온 과목 하나. */
	static CourseRef focus(Snapshot snapshot, Long courseId, String text) {
		if (courseId != null && snapshot.byId.containsKey(courseId)) {
			return snapshot.byId.get(courseId);
		}
		String query = compact(text);
		List<CourseRef> named = snapshot.courses.stream().filter((c) -> {
			String name = compact(c.course().name());
			String code = compact(c.course().code());
			return (name.length() >= 2 && query.contains(name)) || (code.length() >= 2 && query.contains(code));
		}).toList();
		return named.size() == 1 ? named.getFirst() : null;
	}

	static String compact(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
	}

	private static Set<String> nativeKeys(long activityId, Long targetId) {
		Set<String> keys = new HashSet<>(List.of(Long.toString(activityId), "srv-" + activityId));
		if (targetId != null) {
			keys.add("srv-" + targetId);
		}
		return Set.copyOf(keys);
	}

}
