package com.stackcorp.astra.integration.eclass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;
import com.stackcorp.astra.identity.api.SchoolLoginException;

/** 학교 강의실 첫 화면 해석. 화면은 구조만 남긴 합성 fixture다(값은 지어낸 것). */
class CourseRoomPageTests {

	static final URI BASE = URI.create("https://eclass.example.ac.kr/");

	static final String REGULAR = "A2026310900011001";

	static final String NONREGULAR = "비2026B00001";

	@Test
	void regularRoomGivesWeeksInPageOrderIncludingEmptyOnes() {
		SchoolCourseRoom room = CourseRoomPage.read(fixture("room-regular.html"), REGULAR, 2026, BASE);

		assertThat(room.weeks()).extracting(SchoolWeek::weekNo).containsExactly(1, 2, 3);
		SchoolWeek first = room.weeks().get(0);
		assertThat(first.startsOn()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(first.endsOn()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(room.weeks().get(1).activities()).isEmpty();
		assertThat(room.weeks().get(1).startsOn()).isEqualTo(LocalDate.of(2026, 9, 8));
		assertThat(room.activityCount()).isEqualTo(7);
	}

	@Test
	void eachActivityTypeKeepsItsInfoPairsAndOwnStatus() {
		SchoolCourseRoom room = CourseRoomPage.read(fixture("room-regular.html"), REGULAR, 2026, BASE);
		List<SchoolActivity> week1 = room.weeks().get(0).activities();
		List<SchoolActivity> week3 = room.weeks().get(2).activities();

		SchoolActivity material = week1.get(0);
		assertThat(material.type()).isEqualTo("lecture_material");
		assertThat(material.kind()).isNull();
		assertThat(material.itemId()).isEqualTo("7000001");
		assertThat(material.title()).isEqualTo("강의 안내 자료");
		assertThat(material.done()).isTrue();
		assertThat(material.openAt()).isNull();
		assertThat(material.closeAt()).isNull();
		assertThat(material.url()).isEqualTo(
				"https://eclass.example.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=7000001&gubun=lecture_material&KJKEY="
						+ REGULAR);

		SchoolActivity video = week1.get(1);
		assertThat(video.type()).isEqualTo("lecture_weeks");
		assertThat(video.attendance()).isEqualTo("1 주 1차시");
		assertThat(video.learningTimeSec()).isEqualTo(25 * 60 + 5);
		assertThat(video.openAt()).isNull();
		assertThat(video.expired()).isTrue();
		assertThat(video.closeAt()).isNull();
		assertThat(video.done()).isFalse();

		SchoolActivity report = week3.get(0);
		assertThat(report.type()).isEqualTo("report");
		assertThat(report.kind()).isEqualTo("report1");
		assertThat(report.closeAt()).isEqualTo(LocalDateTime.of(2026, 9, 18, 23, 59));
		assertThat(report.expired()).isFalse();
		assertThat(report.done()).isTrue();

		SchoolActivity exam = week3.get(1);
		assertThat(exam.type()).isEqualTo("test");
		assertThat(exam.kind()).isEqualTo("test2");
		assertThat(exam.openAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 10, 30));
		assertThat(exam.closeAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 12, 0));
		assertThat(exam.offline()).isTrue();
		assertThat(exam.done()).isTrue();

		SchoolActivity quiz = week3.get(2);
		assertThat(quiz.kind()).isEqualTo("test1");
		assertThat(quiz.openAt()).isNull();
		assertThat(quiz.closeAt()).isNull();
		assertThat(quiz.offline()).isFalse();

		SchoolActivity survey = week3.get(3);
		assertThat(survey.type()).isEqualTo("survey");
		assertThat(survey.closeAt()).isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 59));
		assertThat(survey.attendance()).isNull();

		SchoolActivity discussion = week3.get(4);
		assertThat(discussion.type()).isEqualTo("discuss");
		assertThat(discussion.title()).isEqualTo("토론 1");
	}

	@Test
	void unexpectedRowsAreSkippedNotFatal() {
		SchoolCourseRoom room = CourseRoomPage.read(fixture("room-regular.html"), REGULAR, 2026, BASE);

		assertThat(room.weeks().get(2).activities()).extracting(SchoolActivity::title)
			.containsExactly("과제 1", "중간시험", "퀴즈 1", "강의 설문", "토론 1");
	}

	@Test
	void nonRegularRoomHasRoundsWithoutPeriods() {
		SchoolCourseRoom room = CourseRoomPage.read(fixture("room-nonregular.html"), NONREGULAR, 2026, BASE);

		assertThat(room.weeks()).hasSize(1);
		SchoolWeek round = room.weeks().get(0);
		assertThat(round.weekNo()).isEqualTo(1);
		assertThat(round.startsOn()).isNull();
		assertThat(round.endsOn()).isNull();
		SchoolActivity video = round.activities().get(0);
		assertThat(video.attendance()).isEqualTo("1 회차 1차시");
		assertThat(video.learningTimeSec()).isEqualTo(3600 + 38 * 60 + 27);
		assertThat(video.closeAt()).isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 59));
		assertThat(video.url()).contains("KJKEY=%EB%B9%842026B00001");
		SchoolActivity survey = round.activities().get(1);
		assertThat(survey.itemId()).isEqualTo("1");
		assertThat(survey.expired()).isTrue();
	}

	@Test
	void englishRoomsReadTheSameFields() {
		SchoolCourseRoom room = CourseRoomPage.read(fixture("room-english.html"), "A2026310900039001", 2026, BASE);

		SchoolWeek week = room.weeks().get(0);
		assertThat(week.weekNo()).isEqualTo(11);
		assertThat(week.startsOn()).isEqualTo(LocalDate.of(2026, 11, 9));
		assertThat(week.endsOn()).isEqualTo(LocalDate.of(2026, 11, 15));
		SchoolActivity open = week.activities().get(0);
		assertThat(open.attendance()).isEqualTo("WeekWeek 11 Session1");
		assertThat(open.learningTimeSec()).isEqualTo(3723);
		assertThat(open.openAt()).isNull();
		assertThat(open.expired()).isFalse();
		assertThat(open.closeAt()).isEqualTo(LocalDateTime.of(2026, 11, 18, 23, 59));
		SchoolActivity makeUp = week.activities().get(1);
		assertThat(makeUp.expired()).isTrue();
		assertThat(makeUp.attendance()).isEqualTo("Week 3 Session 2 make up class");
		assertThat(makeUp.done()).isTrue();
		assertThat(CourseRoomPage.dateTime("Sep 18 (Fri) 11:59 PM", 2026, LocalTime.MIDNIGHT))
			.isEqualTo(LocalDateTime.of(2026, 9, 18, 23, 59));
		assertThat(CourseRoomPage.dateTime("Tue, January 5 12:30 AM", 2026, LocalTime.MIDNIGHT))
			.isEqualTo(LocalDateTime.of(2027, 1, 5, 0, 30));
	}

	@Test
	void anotherCoursesRoomOrAnUnknownPageIsAnError() {
		assertThatThrownBy(() -> CourseRoomPage.read(fixture("room-regular.html"), "A2026310900029001", 2026, BASE))
			.isInstanceOf(SchoolLoginException.class)
			.hasMessage("room_mismatch");
		assertThatThrownBy(() -> CourseRoomPage.read(Jsoup.parse("<html><body><p>점검 중</p></body></html>"), REGULAR,
				2026, BASE))
			.isInstanceOf(SchoolLoginException.class)
			.hasMessage("room_unrecognized");
	}

	@Test
	void roomWithoutWeeksIsReadAsEmpty() {
		SchoolCourseRoom room = CourseRoomPage.read(
				Jsoup.parse("<input id=\"KJ_KEY\" value=\"" + REGULAR + "\"><div class=\"activity_list_container\"></div>"),
				REGULAR, 2026, BASE);

		assertThat(room.weeks()).isEmpty();
	}

	@Test
	void weekPeriodsCrossingTheYearRollOver() {
		Document doc = Jsoup.parse("""
				<input id="KJ_KEY" value="A2026410900011001">
				<div id="week_list_wrap_1" class="week_list_container"><div class="week_period">12월 28일 ~ 1월 3일</div></div>
				<div id="week_list_wrap_2" class="week_list_container"><div class="week_period">1월 4일 ~ 1월 10일</div></div>
				""");

		SchoolCourseRoom room = CourseRoomPage.read(doc, "A2026410900011001", 2026, BASE);

		assertThat(room.weeks().get(0).endsOn()).isEqualTo(LocalDate.of(2027, 1, 3));
		assertThat(room.weeks().get(1).startsOn()).isEqualTo(LocalDate.of(2027, 1, 4));
	}

	@Test
	void datesAndDurations() {
		assertThat(CourseRoomPage.dateTime("D-2 1월 5일 (화) 오전 12:30", 2026, LocalTime.MIDNIGHT))
			.isEqualTo(LocalDateTime.of(2027, 1, 5, 0, 30));
		assertThat(CourseRoomPage.dateTime("9월 3일", 2026, LocalTime.of(23, 59)))
			.isEqualTo(LocalDateTime.of(2026, 9, 3, 23, 59));
		assertThat(CourseRoomPage.dateTime("2026.09.22 23:59", 2026, LocalTime.MIDNIGHT))
			.isEqualTo(LocalDateTime.of(2026, 9, 22, 23, 59));
		assertThat(CourseRoomPage.dateTime("시작됨", 2026, LocalTime.MIDNIGHT)).isNull();
		assertThat(CourseRoomPage.dateTime("2월 30일", 2026, LocalTime.MIDNIGHT)).isNull();
		assertThat(CourseRoomPage.seconds("0:00")).isZero();
		assertThat(CourseRoomPage.seconds("12분")).isNull();
		assertThat(CourseRoomPage.yearOf(NONREGULAR)).isEqualTo(2026);
		assertThat(CourseRoomPage.yearOf("../x")).isNull();
	}

	static Document fixture(String name) {
		try (InputStream in = CourseRoomPageTests.class.getResourceAsStream(name)) {
			return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), BASE.toString());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
