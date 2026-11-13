package com.stackcorp.astra.learning;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.catalog.api.CourseProfiles;
import com.stackcorp.astra.catalog.api.CourseProfiles.CourseProfile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceSession;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusInstructor;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideoContent;
import com.stackcorp.astra.learning.api.SchoolGrades;
import com.stackcorp.astra.shared.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 학교 강의실의 영상 상세, 강의계획서, 출석, 성적을 그 학생이 STack에서 그 화면을 열 때 그 학생의 학교 session으로 읽어 서비스 화면의
 * 모양으로 돌려준다(ADR-0043 4·5단계).
 * <ul>
 * <li>영상 상세는 {@code online_view_form.acl} 하나만 읽는다. 재생 주소, 영상 ID, 학습창 요청이 없고 STack은 재생하지 않는다.
 * {@code lectureItems}는 비어 있고 {@code playable}은 false다.</li>
 * <li>강의계획서, 출석, 성적은 학교에서 읽는 과목(학생에게 학교 과목 키가 있고 STack 담당자가 없는 과목)의 학생에게만 학교 값을 준다.
 * 그 밖의 과목과 사람에게는 empty를 돌려 서비스 화면이 그대로 답한다.</li>
 * <li>출석과 성적은 학생 본인의 값이다. 저장하거나 캐시하지 않으므로 다른 학생에게 갈 길이 없다. 로그에는 과목 ID, 수, 시간만 남긴다.</li>
 * </ul>
 */
@Component
class SchoolScreens implements SchoolGrades {

	static final String VIDEO = "lecture_weeks";

	private static final Logger log = LoggerFactory.getLogger(SchoolScreens.class);

	private final JdbcClient jdbc;

	private final SchoolContent content;

	private final CourseProfiles profiles;

	private final SchoolContentProperties properties;

	SchoolScreens(JdbcClient jdbc, SchoolContent content, CourseProfiles profiles,
			SchoolContentProperties properties) {
		this.jdbc = jdbc;
		this.content = content;
		this.profiles = profiles;
		this.properties = properties;
	}

	/**
	 * 학교 영상 활동의 상세. 영상 활동이 아니면 empty다(자료 등은 {@link SchoolContent#activity}).
	 */
	Optional<SchoolVideoView> video(Actor actor, long courseId, long schoolActivityId, LocalDateTime now) {
		this.content.requireMember(actor, courseId);
		Optional<VideoRow> found = this.jdbc.sql("""
				SELECT school_activity_id, school_item_id, week_no, title, open_at, close_at, expired_yn,
				       attendance_label, school_url
				  FROM school_activities
				 WHERE course_id = :course AND school_activity_id = :id AND school_type = :type
				""")
			.param("course", courseId)
			.param("id", schoolActivityId)
			.param("type", VIDEO)
			.query((rs, n) -> new VideoRow(rs.getLong("school_activity_id"), rs.getString("school_item_id"),
					rs.getInt("week_no"), rs.getString("title"), rs.getObject("open_at", LocalDateTime.class),
					rs.getObject("close_at", LocalDateTime.class), rs.getBoolean("expired_yn"),
					rs.getString("attendance_label"), rs.getString("school_url")))
			.optional();
		if (found.isEmpty()) {
			return Optional.empty();
		}
		VideoRow row = found.get();
		long started = System.nanoTime();
		SchoolVideo video = this.content.read(actor, courseId, (rooms, key) -> rooms.video(key, row.itemId(),
				this.properties.requestTimeout(), this.properties.readBudget()));
		log.info("School video read: course={} contents={} ms={}", courseId, video.contents().size(), ms(started));
		List<VideoContent> contents = video.contents().stream().map(VideoContent::of).toList();
		boolean schoolOnly = contents.stream().anyMatch(VideoContent::attendanceInSchoolOnly);
		String phase = row.expired() ? "closed" : ActivityPhase.of(row.openAt(), row.closeAt(), null, now);
		return Optional.of(new SchoolVideoView(SchoolContent.ACTIVITY_PREFIX + row.id(), SchoolContent.ORIGIN, VIDEO,
				row.weekNo(), row.title(), null, row.openAt(), row.closeAt(), true, phase, now, List.of(), List.of(),
				null, row.attendance(), false, new Video(video.attendanceWeek(), video.periodStartAt(),
						video.periodEndAt(), schoolOnly, contents),
				row.schoolUrl()));
	}

	/**
	 * 학교에서 읽는 과목의 강의계획서. 정규과목만 학교 계획서가 있다. 비정규 과정은 404 {@code syllabus_not_provided}다.
	 * @return 학교에서 읽는 과목의 학생이 아니면 empty
	 */
	Optional<SyllabusView> syllabus(Actor actor, long courseId) {
		Optional<CourseProfile> profile = schoolCourse(actor, courseId);
		if (profile.isEmpty()) {
			return Optional.empty();
		}
		if (!profile.get().regular()) {
			throw notProvided();
		}
		long started = System.nanoTime();
		SchoolSyllabus plan;
		try {
			plan = this.content.read(actor, courseId,
					(rooms, key) -> rooms.syllabus(key, this.properties.requestTimeout(), this.properties.readBudget()));
		}
		catch (ApiException ex) {
			if (ex.status() == HttpStatus.NOT_FOUND) {
				throw notProvided();
			}
			throw ex;
		}
		log.info("School syllabus read: course={} weeks={} ms={}", courseId, plan.weeks().size(), ms(started));
		CourseProfile p = profile.get();
		return Optional.of(new SyllabusView(courseId, SchoolContent.ORIGIN, p.name(), p.code(), p.section(),
				p.termName(), p.completionType(), p.credit(), plan.classTimeRoom(), plan.overview(), plan.objectives(),
				plan.outcomeRelevance(), plan.textbooks(), plan.equipment(), plan.gradingPolicy(),
				plan.instructors().stream().map(InstructorView::of).toList(), plan.weeks(), plan.termName(),
				plan.courseName(), plan.codeSection(), plan.completionType(), plan.credit()));
	}

	/**
	 * 학교에서 읽는 과목의 학생 본인 출석. 요약은 언제나 과목 전체다.
	 * @param week 주면 그 주차만
	 * @return 학교에서 읽는 과목의 학생이 아니면 empty
	 */
	Optional<AttendanceView> attendance(Actor actor, long courseId, Integer week, LocalDateTime now) {
		if (schoolCourse(actor, courseId).isEmpty()) {
			return Optional.empty();
		}
		long started = System.nanoTime();
		List<SchoolAttendanceWeek> weeks = this.content.read(actor, courseId,
				(rooms, key) -> rooms.attendance(key, this.properties.requestTimeout(), this.properties.readBudget()));
		int[] counts = new int[6];
		int sequence = 0;
		List<AttendanceWeek> body = new ArrayList<>();
		for (SchoolAttendanceWeek school : weeks) {
			List<AttendanceSession> sessions = new ArrayList<>();
			for (SchoolAttendanceSession session : school.sessions()) {
				String status = status(session.status());
				if (!session.cancelled()) {
					counts[0]++;
					counts[switch (status) {
						case "present" -> 1;
						case "late" -> 2;
						case "absent" -> 3;
						case "excused" -> 4;
						default -> 5;
					}]++;
				}
				sessions.add(new AttendanceSession(SchoolContent.ACTIVITY_PREFIX + (++sequence), school.weekNo(),
						session.sessionNo(), session.classDate(), session.startTime(), session.endTime(), null,
						session.method(), session.cancelled(), session.makeup(), null,
						new AttendanceRecord(status, null, null, session.statusLabel())));
			}
			if (week == null || week.equals(school.weekNo())) {
				body.add(new AttendanceWeek(school.weekNo(), sessions));
			}
		}
		log.info("School attendance read: course={} sessions={} ms={}", courseId, sequence, ms(started));
		return Optional.of(new AttendanceView("STUDENT", SchoolContent.ORIGIN, now, null,
				new AttendanceSummary(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5]), body));
	}

	@Override
	public Optional<List<Item>> of(Actor actor, long courseId) {
		if (schoolCourse(actor, courseId).isEmpty()) {
			return Optional.empty();
		}
		long started = System.nanoTime();
		List<SchoolGradeItem> rows = this.content.read(actor, courseId,
				(rooms, key) -> rooms.grades(key, this.properties.requestTimeout(), this.properties.readBudget()));
		Map<String, Long> activities = new HashMap<>();
		this.jdbc.sql("SELECT school_activity_id, school_type, school_item_id FROM school_activities WHERE course_id = :c")
			.param("c", courseId)
			.query((rs) -> {
				activities.put(rs.getString("school_type") + ":" + rs.getString("school_item_id"),
						rs.getLong("school_activity_id"));
			});
		log.info("School grades read: course={} items={} ms={}", courseId, rows.size(), ms(started));
		return Optional.of(rows.stream().map((row) -> {
			Long activity = activities.get(row.type() + ":" + row.itemId());
			String type = switch (row.type()) {
				case "report" -> "assignment";
				case "test" -> "quiz";
				default -> "manual";
			};
			return new Item(SchoolContent.ACTIVITY_PREFIX + row.itemId(), type, row.type(),
					activity == null ? null : SchoolContent.ACTIVITY_PREFIX + activity, row.title(), row.closeAt(),
					row.submitted(), row.offline(), row.maxScore(), row.released(),
					row.released() ? row.score() : null);
		}).toList());
	}

	/** 학교에서 읽는 과목: 그 사람이 학교 과목 키가 있는 수강생이고 과목에 STack 담당자가 없다. */
	private Optional<CourseProfile> schoolCourse(Actor actor, long courseId) {
		if (this.content.courseKey(actor.userId(), courseId).isEmpty()) {
			return Optional.empty();
		}
		return this.profiles.find(courseId).filter((p) -> p.instructorIds().isEmpty());
	}

	/** 학교 출석 상태 class → 서비스 출석 상태. 처리되지 않은 차시는 unmarked, 모르는 class는 other. */
	private static String status(String school) {
		if (school == null || "none".equals(school)) {
			return "unmarked";
		}
		return switch (school) {
			case "attend" -> "present";
			case "tardy" -> "late";
			case "absence" -> "absent";
			case "ex_absence" -> "excused";
			default -> "other";
		};
	}

	private static ApiException notProvided() {
		return new ApiException(HttpStatus.NOT_FOUND, "syllabus_not_provided",
				"학교 e-Class는 정규과목에만 강의계획서를 제공합니다.");
	}

	private static long ms(long started) {
		return (System.nanoTime() - started) / 1_000_000;
	}

	private record VideoRow(long id, String itemId, int weekNo, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean expired, String attendance, String schoolUrl) {
	}

	/**
	 * 학교 영상 활동 상세. 서비스 활동 상세와 같은 이름의 값에 {@code playable}(언제나 false)과 {@code video}를 더한다.
	 * {@code lectureItems}와 {@code files}는 비어 있다. 재생할 것이 없으므로 학습 session·하트비트 경로로 이어지지 않는다.
	 */
	record SchoolVideoView(String activityId, String origin, String type, int weekNo, String title,
			String description, LocalDateTime openAt, LocalDateTime closeAt, boolean published, String phase,
			LocalDateTime serverTime, List<Object> files, List<Object> lectureItems, Integer version,
			@JsonInclude(JsonInclude.Include.NON_NULL) String attendance, boolean playable, Video video,
			String schoolUrl) {
	}

	/**
	 * @param attendanceWeek 출석부 반영 차시. 정규 {@code 4주 1차시}, 비정규 {@code 1회차 2차시}
	 * @param attendanceInSchoolOnly 출석인정 시간이 있는 콘텐츠가 하나라도 있으면 true: 출석은 학교 학습창에서 본 것만 학교 출석부에
	 * 들어간다
	 */
	record Video(String attendanceWeek, LocalDateTime periodStartAt, LocalDateTime periodEndAt,
			boolean attendanceInSchoolOnly, List<VideoContent> contents) {
	}

	/**
	 * @param no 활동 안 콘텐츠 순번
	 * @param percent 학교 화면의 진도율 표시
	 * @param inPeriodPercent 출석인정기간 안의 진도율
	 * @param outOfPeriodPercent 기간 밖의 진도율
	 * @param learningTimeSec 학생 본인의 학습시간(초)
	 * @param attendanceTimeSec 출석인정 시간(초)
	 * @param attendanceInSchoolOnly 출석인정 시간이 0보다 크면 true
	 */
	record VideoContent(int no, String title, Integer percent, BigDecimal inPeriodPercent,
			BigDecimal outOfPeriodPercent, Integer learningTimeSec, Integer attendanceTimeSec,
			boolean attendanceInSchoolOnly) {

		static VideoContent of(SchoolVideoContent c) {
			return new VideoContent(c.no(), c.title(), c.percent(), c.inPeriodPercent(), c.outOfPeriodPercent(),
					c.learningTimeSec(), c.attendanceTimeSec(),
					c.attendanceTimeSec() != null && c.attendanceTimeSec() > 0);
		}

	}

	/**
	 * 학교 강의계획서. 서비스 계획서({@code SyllabusController.SyllabusView})와 같은 이름의 값에 {@code origin}과 학교 화면 글자
	 * ({@code school*})를 더한다. 학교 계획서는 STack에서 고치지 않으므로 {@code version}이 없다. 강사는 STack 사용자가 아니어서
	 * {@code userId}가 없다.
	 */
	record SyllabusView(long courseId, String origin, String courseName, String courseCode, String section,
			String termName, String completionType, BigDecimal credit, String classTimeRoom, String overview,
			String objectives, String outcomeRelevance, String textbooks, String equipment, String gradingPolicy,
			List<InstructorView> instructors, List<SchoolSyllabusWeek> weeks, String schoolTermName,
			String schoolCourseName, String schoolCodeSection, String schoolCompletionType, String schoolCredit) {
	}

	record InstructorView(String name, String department, String email, String officeHours, String phone,
			String office, String homepage) {

		static InstructorView of(SchoolSyllabusInstructor i) {
			return new InstructorView(i.name(), i.department(), i.email(), i.officeHours(), i.phone(), i.office(),
					i.homepage());
		}

	}

	/**
	 * 학교 출석. 서비스 출석({@code AttendanceController.AttendanceView}의 학생 모양)과 같은 이름의 값이다. 설정은 학교 값이 없어 null이다.
	 */
	record AttendanceView(String role, String origin, LocalDateTime serverTime, Object settings,
			AttendanceSummary summary, List<AttendanceWeek> weeks) {
	}

	record AttendanceSummary(int sessions, int present, int late, int absent, int excused, int unmarked) {
	}

	record AttendanceWeek(int weekNo, List<AttendanceSession> sessions) {
	}

	/**
	 * @param sessionId 이 응답 안에서만 쓰는 이름({@code "school-<순번>"})
	 * @param method 서비스 방식 코드. 학교 방식은 글자로만 있어 null이다
	 * @param schoolMethod 학교 화면의 출석 방식 표시. 출석 전·휴강 차시는 null
	 */
	record AttendanceSession(String sessionId, int weekNo, int sessionNo, LocalDate classDate, LocalTime startTime,
			LocalTime endTime, String method, String schoolMethod, boolean cancelled, boolean makeup,
			Long makeupOfSessionId, AttendanceRecord record) {
	}

	/**
	 * @param status present|late|absent|excused|unmarked|other
	 * @param statusLabel 학교 화면의 상태 표시(예 {@code 결석 (0%)}, {@code 출석 전}, {@code 휴강}). 없으면 null
	 */
	record AttendanceRecord(String status, String source, LocalDateTime decidedAt, String statusLabel) {
	}

}
