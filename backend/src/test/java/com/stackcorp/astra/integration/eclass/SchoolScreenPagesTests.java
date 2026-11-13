package com.stackcorp.astra.integration.eclass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceSession;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusInstructor;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideoContent;
import com.stackcorp.astra.identity.api.SchoolLoginException;

/**
 * 학교 강의실의 영상 상세, 강의계획서, 출석, 성적 화면 해석. fixture는 2026-11 실제 화면에서 구조만 남기고 글자·번호를 합성 값으로 바꾼
 * 것이다.
 */
class SchoolScreenPagesTests {

	@Test
	void videoDetailReadsContentsProgressAndTimesWithoutAnyMediaAddress() {
		SchoolVideo video = SchoolScreenPages.video(Jsoup.parse(text("school-online-view.html")), 2026, false);

		// 영어 화면은 "WeekWeek 1 Session1"로 그린다. 한국어 화면("1 주 1차시")과 같은 모양으로 준다.
		assertThat(video.attendanceWeek()).isEqualTo("1주 1차시");
		assertThat(video.periodStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
		assertThat(video.periodEndAt()).isEqualTo(LocalDateTime.of(2026, 9, 13, 23, 59));
		assertThat(video.contents()).containsExactly(
				new SchoolVideoContent(1, "텍스트11", 100, new BigDecimal("100.0"), new BigDecimal("100.0"), 30 * 60 + 19,
						28 * 60 + 14),
				new SchoolVideoContent(2, "텍스트14", 100, new BigDecimal("100.0"), new BigDecimal("100.0"), 38 * 60 + 15,
						27 * 60 + 9));
	}

	@Test
	void videoCardsWithoutTitleAreSkippedAndOtherPagesAreNotVideos() {
		String html = """
				<div class="online_contents_list_wrap">
				  <div class="online_contents_list"><button class="online_contents_wrap" onclick="learningGo('3');"></button></div>
				  <div class="online_contents_list">
				    <div class="attend_progress_bar" style="width: 42.5%;"></div>
				    <button class="online_contents_wrap" onclick="learningGo('4');">
				      <div class="video_title">Lecture</div>
				      <div class="online_contents_badge"><span>Studying Time</span><span>1:02:03</span></div>
				    </button>
				  </div>
				</div>
				""";
		SchoolVideo video = SchoolScreenPages.video(Jsoup.parse(html), 2026, false);

		assertThat(video.attendanceWeek()).isNull();

		assertThat(video.contents()).containsExactly(
				new SchoolVideoContent(4, "Lecture", null, new BigDecimal("42.5"), null, 3723, null));
		assertThatThrownBy(() -> SchoolScreenPages.video(Jsoup.parse("<div class=\"bbsview\"></div>"), 2026, false))
			.isInstanceOf(SchoolLoginException.class)
			.hasMessage("video_unrecognized");
	}

	@Test
	void attendanceWeekIsTheSameShapeForKoreanAndEnglishPages() {
		String english = text("school-online-view.html");
		String korean = english.replace("WeekWeek 1 Session1", "4 주 1차시");

		assertThat(SchoolScreenPages.video(Jsoup.parse(korean), 2026, false).attendanceWeek()).isEqualTo("4주 1차시");
		assertThat(SchoolScreenPages.video(Jsoup.parse(english.replace("WeekWeek 1 Session1", "WeekWeek 4 Session1")),
				2026, false)
			.attendanceWeek()).isEqualTo("4주 1차시");
		assertThat(SchoolScreenPages.video(Jsoup.parse(english.replace("WeekWeek 1 Session1", "1 회차 2차시")), 2026, true)
			.attendanceWeek()).isEqualTo("1회차 2차시");
		assertThat(SchoolScreenPages.video(Jsoup.parse(english), 2026, true).attendanceWeek()).isEqualTo("1회차 1차시");
		assertThat(SchoolScreenPages.attendanceWeek("1 회차 2차시", false)).isEqualTo("1회차 2차시");
		assertThat(SchoolScreenPages.attendanceWeek("Week 4", false)).isNull();
		assertThat(SchoolScreenPages.attendanceWeek(null, false)).isNull();
	}

	@Test
	void syllabusReadsTablesByPosition() {
		SchoolSyllabus plan = SchoolScreenPages.syllabus(Jsoup.parse(text("school-syllabus.html")));

		assertThat(plan.termName()).isEqualTo("텍스트3");
		assertThat(plan.courseName()).isEqualTo("텍스트4");
		assertThat(plan.codeSection()).isEqualTo("100000/10000");
		assertThat(plan.completionType()).isEqualTo("텍스트5");
		assertThat(plan.credit()).isEqualTo("3");
		assertThat(plan.classTimeRoom()).isEqualTo("텍스트6");
		assertThat(plan.instructors()).containsExactly(new SchoolSyllabusInstructor("텍스트7", "텍스트8", "텍스트9",
				"텍스트10", "텍스트11", "텍스트12", "텍스트13"));
		assertThat(plan.overview()).startsWith("텍스트14\n\n텍스트15\n").endsWith("텍스트20");
		assertThat(plan.objectives()).isEqualTo("텍스트21\n텍스트22\n텍스트23\n텍스트24");
		assertThat(plan.outcomeRelevance()).isEqualTo("텍스트28");
		assertThat(plan.textbooks()).isEqualTo("텍스트29");
		assertThat(plan.equipment()).isEqualTo("텍스트30");
		assertThat(plan.gradingPolicy()).isEqualTo("텍스트31");
		assertThat(plan.weeks()).hasSize(16);
		assertThat(plan.weeks().get(0).weekNo()).isEqualTo(1);
		assertThat(plan.weeks().get(0).content()).isEqualTo("텍스트32");
		assertThat(plan.weeks().get(0).note()).isNull();
		assertThat(plan.weeks().get(15).note()).isEqualTo("텍스트48");
		assertThatThrownBy(() -> SchoolScreenPages.syllabus(Jsoup.parse("<p>없음</p>")))
			.isInstanceOf(SchoolLoginException.class)
			.hasMessage("syllabus_unrecognized");
	}

	@Test
	void syllabusRequestValuesComeFromTheRoomScriptAndNonRegularRoomsHaveNone() {
		String room = """
				<script>
				function lecPlanViewPop(){
				  $.ajax({ url: "/ilos/main/rg/regular_view_pop.acl", type: "POST",
				    data: { KJKEY : "A2026399999901001", ORG_SECT : "A", YEAR : "2026", TERM : "3", LEDG_YEAR : "2026",
				      LEDG_SESSN : "3",        LSSN_CD : "999999", SUBJT_NO : "01001", encoding : "utf-8" } });
				}
				</script>
				""";
		Map<String, String> values = SchoolScreenPages.planParameters(room);

		assertThat(values).containsExactly(Map.entry("KJKEY", "A2026399999901001"), Map.entry("ORG_SECT", "A"),
				Map.entry("YEAR", "2026"), Map.entry("TERM", "3"), Map.entry("LEDG_YEAR", "2026"),
				Map.entry("LEDG_SESSN", "3"), Map.entry("LSSN_CD", "999999"), Map.entry("SUBJT_NO", "01001"));
		assertThat(SchoolScreenPages.planParameters(text("room-nonregular.html"))).isNull();
		assertThat(SchoolScreenPages.planParameters(room.replace("\"999999\"", "\"99<9\""))).isNull();
	}

	@Test
	void attendanceReadsEnglishRegularRoomPerWeekAndSession() {
		List<SchoolAttendanceWeek> weeks = SchoolScreenPages
			.attendance(Jsoup.parse(text("school-attendance-english.html")), 2026);

		assertThat(weeks).hasSize(16);
		assertThat(weeks).extracting(SchoolAttendanceWeek::weekNo).startsWith(1, 2, 3).endsWith(16);
		List<SchoolAttendanceSession> all = weeks.stream().flatMap((w) -> w.sessions().stream()).toList();
		assertThat(all).hasSize(49);
		assertThat(all.stream().filter((s) -> "attend".equals(s.status()))).hasSize(29);
		assertThat(all.stream().filter((s) -> "absence".equals(s.status()))).hasSize(1);
		assertThat(all.stream().filter(SchoolAttendanceSession::cancelled)).hasSize(4);
		assertThat(all.stream().filter(SchoolAttendanceSession::makeup)).hasSize(4);
		assertThat(all.stream().filter((s) -> s.status() == null)).hasSize(19);
		SchoolAttendanceSession first = weeks.get(0).sessions().get(0);
		assertThat(first).isEqualTo(new SchoolAttendanceSession(1, LocalDate.of(2026, 9, 3), LocalTime.of(14, 0),
				LocalTime.of(17, 0), "Smart", false, false, "attend", "Attendance"));
		// 같은 날의 다음 차시는 앞 차시의 날짜를 이어 받는다.
		assertThat(weeks.get(0).sessions().get(2).classDate()).isEqualTo(LocalDate.of(2026, 9, 3));
		SchoolAttendanceSession makeup = all.stream().filter(SchoolAttendanceSession::makeup).findFirst().orElseThrow();
		assertThat(makeup.sessionNo()).isEqualTo(1);
		assertThat(makeup.startTime()).isEqualTo(LocalTime.of(14, 0));
		assertThat(makeup.endTime()).isEqualTo(LocalTime.of(15, 0));
		// 출석 전·휴강 차시의 .attend_dv는 방식이 아니라 상태 글자다. 방식은 null이고 그 글자는 상태 표시로 간다.
		assertThat(makeup.method()).isNull();
		assertThat(makeup.statusLabel()).isEqualTo("Before attendance");
		assertThat(all.stream().filter((s) -> s.method() == null)).hasSize(19);
		assertThat(all.stream().filter((s) -> "Before attendance".equals(s.statusLabel()))).hasSize(15);
		SchoolAttendanceSession cancelled = all.stream().filter(SchoolAttendanceSession::cancelled).findFirst().orElseThrow();
		assertThat(cancelled.method()).isNull();
		assertThat(cancelled.status()).isNull();
		assertThat(cancelled.statusLabel()).isEqualTo("Cancel Class");
		assertThat(all.stream().filter((s) -> s.status() != null))
			.allSatisfy((s) -> assertThat(s.method()).isIn("Smart", "Online"));
	}

	@Test
	void attendanceKoreanRowsKeepMethodForDecidedSessionsAndStateTextAsStatusLabel() {
		String html = """
				<div id="week_list_wrap_3" class="attendance_list"><ul class="attendance_week_time_list">
				  <li><div class="attendance_date_box"><div class="date">2026.09.17 (목)</div><div class="lect_time">14:00 ~ 17:00</div></div>
				    <div class="attend_menu  " title="온라인"><div class="attend_dv">온라인</div><div class="week_time">1차시</div>
				      <div class="attend_stats_list"><div class="attend_stats" title="결석 (0%)">
				        <div class="attend_dv_icon_title absence">결석 (0%)</div><div class="attend_dv_icon absence"></div></div></div></div></li>
				  <li><div class="attend_menu ready " title="출석 전"><div class="attend_dv">출석 전</div><div class="week_time">2차시</div></div></li>
				  <li><div class="attend_menu  cancel" title="휴강"><div class="attend_dv">휴강</div><div class="week_time">3차시</div></div></li>
				</ul></div>
				""";
		List<SchoolAttendanceSession> sessions = SchoolScreenPages.attendance(Jsoup.parse(html), 2026).get(0).sessions();

		assertThat(sessions).containsExactly(
				new SchoolAttendanceSession(1, LocalDate.of(2026, 9, 17), LocalTime.of(14, 0), LocalTime.of(17, 0), "온라인",
						false, false, "absence", "결석 (0%)"),
				new SchoolAttendanceSession(2, LocalDate.of(2026, 9, 17), LocalTime.of(14, 0), LocalTime.of(17, 0), null,
						false, false, null, "출석 전"),
				new SchoolAttendanceSession(3, LocalDate.of(2026, 9, 17), LocalTime.of(14, 0), LocalTime.of(17, 0), null,
						false, true, null, "휴강"));
	}

	@Test
	void attendanceReadsNonRegularRoundsAndSkipsUnknownMarkup() {
		List<SchoolAttendanceWeek> weeks = SchoolScreenPages
			.attendance(Jsoup.parse(text("school-attendance-nonregular.html")), 2026);

		assertThat(weeks).hasSize(1);
		assertThat(weeks.get(0).sessions()).extracting(SchoolAttendanceSession::sessionNo).containsExactly(1, 2, 3);
		assertThat(weeks.get(0).sessions()).allSatisfy((s) -> {
			assertThat(s.status()).isEqualTo("attend");
			assertThat(s.classDate()).isNull();
			assertThat(s.method()).isEqualTo("Online");
		});
		assertThat(SchoolScreenPages.attendance(Jsoup.parse(""), 2026)).isEmpty();
		assertThat(SchoolScreenPages.attendance(
				Jsoup.parse("<div class=\"attendance_list\" id=\"other\"><ul class=\"attendance_week_time_list\">"
						+ "<li><div class=\"attend_menu\"></div></li></ul></div>"),
				2026))
			.isEmpty();
	}

	@Test
	void gradesReadReleasedUnreleasedAndMissingScores() {
		List<SchoolGradeItem> items = SchoolScreenPages.grades(Jsoup.parse(text("school-grades.html")), 2026);

		assertThat(items).hasSize(5);
		assertThat(items.get(0)).isEqualTo(new SchoolGradeItem("report", "report1", "1000019", "텍스트1",
				LocalDateTime.of(2026, 10, 22, 23, 59), true, false, false, null, new BigDecimal("3")));
		assertThat(items.get(2).submitted()).isFalse();
		assertThat(items.get(2).maxScore()).isEqualTo(new BigDecimal("15"));
		SchoolGradeItem exam = items.get(4);
		assertThat(exam.type()).isEqualTo("test");
		assertThat(exam.kind()).isEqualTo("test1");
		assertThat(exam.offline()).isFalse();
		assertThat(exam.released()).isTrue();
		assertThat(exam.score()).isNull();
		assertThat(exam.maxScore()).isEqualTo(new BigDecimal("90"));
	}

	@Test
	void gradesReadEnglishScoresAndSkipRowsWithoutActivity() {
		String html = """
				<a href="/ilos/cls/st/activity/activity_form.acl?m=report&n=77" class="eval_list">
				  <div class="eval_list_title"><div class="list_icon menu_icon report1 font_headline5">Essay</div>
				    <div class="submit_check_outer"><div class="status font_subtitle5">Offline</div></div></div>
				  <div class="eval_view_contents">
				    <div class="eval_view_contents_item"><div>Deadline</div><div class="font_caption2">Oct 22 (Thu) 11:59 PM</div></div>
				    <div class="eval_view_contents_item"><div>Score</div><div class="font_caption2"><span>8.5</span> / 10 points</div></div>
				  </div>
				</a>
				<a href="/ilos/cls/st/activity/activity_form.acl?m=report" class="eval_list">
				  <div class="eval_list_title"><div class="list_icon report1">No id</div></div>
				</a>
				""";
		List<SchoolGradeItem> items = SchoolScreenPages.grades(Jsoup.parse(html), 2026);

		assertThat(items).containsExactly(new SchoolGradeItem("report", "report1", "77", "Essay",
				LocalDateTime.of(2026, 10, 22, 23, 59), false, true, true, new BigDecimal("8.5"), new BigDecimal("10")));
		assertThat(SchoolScreenPages.grades(Jsoup.parse(""), 2026)).isEmpty();
	}

	private static String text(String name) {
		try (InputStream in = SchoolScreenPagesTests.class.getResourceAsStream(name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
