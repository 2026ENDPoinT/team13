package com.stackcorp.astra.identity.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * {@link SchoolCourseRooms}가 학교 강의실의 영상 상세, 강의계획서, 출석, 성적 화면에서 읽어 오는 값. 학교 화면의 값이며 시각은 학교 화면의
 * 벽시계(기관 시간대)다. 출석과 성적은 그 학교 session 학생 본인의 값이다.
 */
public final class SchoolCourseScreens {

	private SchoolCourseScreens() {
	}

	/**
	 * 영상 활동 하나({@code online_view_form.acl}). 재생 주소나 영상 ID는 담지 않는다.
	 * @param attendanceWeek 출석부 반영 차시. 정규 {@code 4주 1차시}, 비정규 {@code 1회차 2차시}(화면 언어와 관계없이 같은 모양). 없으면 null
	 * @param periodStartAt 출석인정기간 시작. 읽지 못했으면 null
	 * @param periodEndAt 출석인정기간 끝. 읽지 못했으면 null
	 * @param contents 콘텐츠. 화면 순서
	 */
	public record SchoolVideo(String attendanceWeek, LocalDateTime periodStartAt, LocalDateTime periodEndAt,
			List<SchoolVideoContent> contents) {

		public SchoolVideo {
			contents = List.copyOf(contents);
		}

	}

	/**
	 * 영상 콘텐츠 카드 하나.
	 * @param no 활동 안 콘텐츠 순번(학교의 {@code link_seq}). 없으면 화면 순번
	 * @param percent 썸네일의 진도율 표시(정수 %). 없으면 null
	 * @param inPeriodPercent 출석인정기간 안의 진도율(막대 폭, %). 없으면 null
	 * @param outOfPeriodPercent 기간 밖의 진도율(막대 폭, %). 없으면 null
	 * @param learningTimeSec 학습시간(누적, 초). 첫 배지. 없으면 null
	 * @param attendanceTimeSec 출석인정 시간(초). 둘째 배지. 없으면 null
	 */
	public record SchoolVideoContent(int no, String title, Integer percent, BigDecimal inPeriodPercent,
			BigDecimal outOfPeriodPercent, Integer learningTimeSec, Integer attendanceTimeSec) {
	}

	/**
	 * 정규과목 강의계획서({@code regular_view_plan.acl}). 칸은 화면의 자리로 읽는다(칸 이름이 영어로 그려지기도 한다). 값은 글자다.
	 */
	public record SchoolSyllabus(String termName, String courseName, String codeSection, String completionType,
			String credit, String classTimeRoom, List<SchoolSyllabusInstructor> instructors, String overview,
			String objectives, String outcomeRelevance, String textbooks, String equipment, String gradingPolicy,
			List<SchoolSyllabusWeek> weeks) {

		public SchoolSyllabus {
			instructors = List.copyOf(instructors);
			weeks = List.copyOf(weeks);
		}

	}

	public record SchoolSyllabusInstructor(String name, String department, String email, String officeHours,
			String phone, String office, String homepage) {
	}

	public record SchoolSyllabusWeek(int weekNo, String content, String note) {
	}

	/**
	 * 출석 화면({@code attendance_list.acl})의 주차 하나. 비정규 과정은 회차다.
	 */
	public record SchoolAttendanceWeek(int weekNo, List<SchoolAttendanceSession> sessions) {

		public SchoolAttendanceWeek {
			sessions = List.copyOf(sessions);
		}

	}

	/**
	 * 차시 하나와 학생 본인의 상태.
	 * @param classDate 수업일. 화면에 없으면 null
	 * @param startTime 수업 시작. 없으면 null
	 * @param endTime 수업 끝. 없으면 null
	 * @param method 학교 화면의 출석 방식 표시(예 {@code 온라인}, {@code Smart}). 표시용 글자다. 출석 전·휴강 차시는 화면이 방식 대신
	 * 상태를 쓰므로 null
	 * @param makeup 보강 차시
	 * @param cancelled 휴강
	 * @param status 학교 상태 class: {@code attend}·{@code tardy}·{@code absence}·{@code ex_absence}, 그 밖의 class는 그대로.
	 * 아직 처리되지 않았으면 null
	 * @param statusLabel 학교 화면의 상태 표시(예 {@code 결석 (0%)}, 출석 전 차시 {@code 출석 전}, 휴강 {@code 휴강}). 없으면 null
	 */
	public record SchoolAttendanceSession(int sessionNo, LocalDate classDate, LocalTime startTime, LocalTime endTime,
			String method, boolean makeup, boolean cancelled, String status, String statusLabel) {
	}

	/**
	 * 성적 화면 평가 활동 목록({@code eval3_result_view_detail_list.acl})의 행 하나.
	 * @param type 학교 메뉴 ID(report, test 등)
	 * @param kind 아이콘 class의 세부 유형(report1, test1 등). 없으면 null
	 * @param itemId 학교 활동 번호(링크의 {@code n})
	 * @param closeAt 마감. 없으면 null
	 * @param submitted 제출·응시 완료 표시
	 * @param offline 오프라인 표시
	 * @param released 점수를 공개했는지. 공개 전이면 {@code score}는 null이다
	 * @param score 공개된 점수. 공개됐지만 점수가 없으면 null
	 * @param maxScore 배점. 없으면 null
	 */
	public record SchoolGradeItem(String type, String kind, String itemId, String title, LocalDateTime closeAt,
			boolean submitted, boolean offline, boolean released, BigDecimal score, BigDecimal maxScore) {
	}

}
