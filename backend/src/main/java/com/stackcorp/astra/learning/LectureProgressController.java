package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserDirectory;
import com.stackcorp.astra.identity.api.UserDirectory.UserCard;
import com.stackcorp.astra.learning.LearningAccess.Activity;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 온라인 강의 진도와 출석인정 현황(EP-0052, EP-0057 조회 부분의 대체 계약, ADR-0014). 조회만 하고 아무것도 바꾸지 않는다(SC-04).
 * 진도는 서버 적립 원장에서만 계산한다. 재생 위치는 이어보기 힌트로만 돌려준다.
 */
@RestController
class LectureProgressController {

	private static final String COLUMNS = """
			SELECT w.week_no, a.activity_id, a.title AS activity_title, a.published_yn, a.open_at, a.close_at,
			       i.online_lecture_item_id, i.title, i.duration_sec, i.required_sec
			""";

	private static final String FROM = """
			  FROM learning_activities a
			  JOIN course_weeks w ON w.course_week_id = a.course_week_id AND a.deleted_at IS NULL
			  JOIN online_lecture_items i ON i.activity_id = a.activity_id
			""";

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final LearningClock clock;

	private final CourseRosters rosters;

	private final UserDirectory users;

	LectureProgressController(JdbcClient jdbc, LearningAccess access, LearningClock clock, CourseRosters rosters,
			UserDirectory users) {
		this.jdbc = jdbc;
		this.access = access;
		this.clock = clock;
		this.rosters = rosters;
		this.users = users;
	}

	/** 내 진도. 주차별로 묶고, 학생에게는 공개됐고 시작한 활동의 콘텐츠만 보인다. */
	@GetMapping("/api/courses/{courseId}/lecture-progress")
	ResponseEntity<List<WeekProgress>> mine(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		LocalDateTime now = this.clock.now();
		Map<Integer, List<LectureItemProgress>> weeks = new LinkedHashMap<>();
		this.jdbc.sql(COLUMNS + ", p.credited_in_sec, p.met_at, p.last_position_sec\n" + FROM + """
				  LEFT JOIN online_lecture_item_progress p
				         ON p.online_lecture_item_id = i.online_lecture_item_id AND p.user_id = :user
				 WHERE a.course_id = :course
				 ORDER BY w.week_no, a.sort_order, a.activity_id, i.link_seq
				""").param("user", actor.userId()).param("course", courseId).query((rs) -> {
			LectureItem item = item(rs, courseId);
			if (role != CourseRole.INSTRUCTOR && !LearningAccess.open(item.activity(), now)) {
				return;
			}
			ItemProgress progress = ItemProgress.of(item.itemId(), item.requiredSec(), item.durationSec(),
					rs.getInt("credited_in_sec"), rs.getObject("met_at", LocalDateTime.class),
					rs.getObject("last_position_sec", Integer.class));
			weeks.computeIfAbsent(item.weekNo(), (n) -> new ArrayList<>())
				.add(new LectureItemProgress(item.activity().activityId(), item.activityTitle(), item.activity().openAt(),
						item.closeAt(), item.itemId(), item.title(), item.durationSec(), progress));
		});
		List<WeekProgress> body = weeks.entrySet()
			.stream()
			.map((e) -> new WeekProgress(e.getKey(), e.getValue().size(),
					(int) e.getValue().stream().filter((i) -> "met".equals(i.progress().status())).count(), e.getValue()))
			.toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 담당자용 수강생 진도. 공개 여부와 관계없이 과목의 모든 콘텐츠를 보인다. */
	@GetMapping("/api/courses/{courseId}/lecture-progress/students")
	ResponseEntity<Roster> roster(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		List<LectureItem> items = this.jdbc.sql(COLUMNS + FROM + """
				 WHERE a.course_id = :course
				 ORDER BY w.week_no, a.sort_order, a.activity_id, i.link_seq
				""").param("course", courseId).query((rs, n) -> item(rs, courseId)).list();
		Map<Long, Map<Long, Credited>> credited = new HashMap<>();
		this.jdbc.sql("""
				SELECT p.user_id, p.online_lecture_item_id, p.credited_in_sec, p.met_at
				  FROM online_lecture_item_progress p
				  JOIN online_lecture_items i ON i.online_lecture_item_id = p.online_lecture_item_id
				  JOIN learning_activities a ON a.activity_id = i.activity_id AND a.deleted_at IS NULL
				 WHERE a.course_id = :course
				""").param("course", courseId).query((rs) -> {
			credited.computeIfAbsent(rs.getLong(1), (u) -> new HashMap<>())
				.put(rs.getLong(2), new Credited(rs.getInt(3), rs.getObject(4, LocalDateTime.class)));
		});
		List<Long> students = this.rosters.students(courseId);
		Map<Long, UserCard> cards = this.users.find(students);
		List<StudentProgress> rows = new ArrayList<>();
		for (long userId : students) {
			Map<Long, Credited> mine = credited.getOrDefault(userId, Map.of());
			List<ItemProgress> progress = items.stream().map((item) -> {
				Credited c = mine.getOrDefault(item.itemId(), Credited.NONE);
				return ItemProgress.of(item.itemId(), item.requiredSec(), item.durationSec(), c.seconds(), c.metAt(),
						null);
			}).toList();
			UserCard card = cards.get(userId);
			rows.add(new StudentProgress(userId, card == null ? null : card.name(),
					card == null ? null : card.externalRef(),
					(int) progress.stream().filter((p) -> "met".equals(p.status())).count(), progress));
		}
		List<RosterItem> columns = items.stream()
			.map((i) -> new RosterItem(i.weekNo(), i.activity().activityId(), i.activityTitle(), i.itemId(), i.title(),
					ItemProgress.required(i.requiredSec(), i.durationSec()), i.activity().openAt(), i.closeAt(),
					i.activity().published()))
			.toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Roster(columns, rows));
	}

	private static LectureItem item(java.sql.ResultSet rs, long courseId) throws java.sql.SQLException {
		return new LectureItem(rs.getInt("week_no"),
				new Activity(rs.getLong("activity_id"), courseId, rs.getBoolean("published_yn"),
						rs.getObject("open_at", LocalDateTime.class)),
				rs.getString("activity_title"), rs.getObject("close_at", LocalDateTime.class),
				rs.getLong("online_lecture_item_id"), rs.getString("title"), rs.getObject("duration_sec", Integer.class),
				rs.getObject("required_sec", Integer.class));
	}

	private record LectureItem(int weekNo, Activity activity, String activityTitle, LocalDateTime closeAt, long itemId,
			String title, Integer durationSec, Integer requiredSec) {
	}

	private record Credited(int seconds, LocalDateTime metAt) {

		static final Credited NONE = new Credited(0, null);

	}

	/**
	 * @param metCount 출석인정을 채운 콘텐츠 수
	 */
	record WeekProgress(int weekNo, int itemCount, int metCount, List<LectureItemProgress> items) {
	}

	record LectureItemProgress(long activityId, String activityTitle, LocalDateTime openAt, LocalDateTime closeAt,
			long itemId, String title, Integer durationSec, ItemProgress progress) {
	}

	/**
	 * @param items 열 순서. 학생 행의 items가 같은 순서다
	 */
	record Roster(List<RosterItem> items, List<StudentProgress> students) {
	}

	record RosterItem(int weekNo, long activityId, String activityTitle, long itemId, String title, Integer requiredSec,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published) {
	}

	/**
	 * @param studentNo 학사 식별자(학번)
	 */
	record StudentProgress(long userId, String name, String studentNo, int metCount, List<ItemProgress> items) {
	}

}
