package com.stackcorp.astra.identity.api;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 학번·비밀번호 확인({@link SchoolPasswordVerifier#verify})으로 열린 학교 e-Class session으로 학생의 강의실을 읽는 손잡이. 학교
 * session 쿠키는 이 객체의 메모리에만 있고 저장하거나 로그·예외·다른 객체로 내보내지 않는다(SC-07).
 * <ul>
 * <li>한 객체는 한 주인만 쓴다. 쓰려는 쪽은 먼저 {@link #claim()}이 true인지 본다. 주인은 다 쓰면 {@link #close()}로 쿠키를 버린다.
 * 로그인 요청을 받은 쪽은 응답 전에 {@code claim()}이 true면(아무도 가져가지 않았으면) 바로 닫는다.</li>
 * <li>학교 강의실은 session에 과목 하나가 묶이는 구조라 요청을 차례로 보낸다. 동시에 부르지 않는다.</li>
 * </ul>
 */
public interface SchoolCourseRooms {

	/** @return 처음 부른 쪽만 true. 그쪽이 이 객체를 닫을 책임을 진다 */
	boolean claim();

	/**
	 * 과목 하나의 강의실 첫 화면(주차와 학습활동 목록)을 읽는다.
	 * @param courseKey 학교 과목 키(KJKEY). 로그인 때 읽은 수강과목의 키
	 * @param requestTimeout HTTP 요청 하나의 제한 시간
	 * @param remaining 이 읽기 전체가 넘지 않을 시간
	 * @throws SchoolLoginException 학교 서버를 쓸 수 없거나 화면을 알아보지 못할 때. message는 이유 코드뿐이다
	 */
	SchoolCourseRoom read(String courseKey, Duration requestTimeout, Duration remaining);

	/** 쿠키를 버린다. 여러 번 불러도 된다. 닫은 뒤 {@link #read}는 실패한다. */
	void close();

	/**
	 * 강의실 첫 화면의 주차들. 화면 순서다.
	 */
	record SchoolCourseRoom(List<SchoolWeek> weeks) {

		public SchoolCourseRoom {
			weeks = List.copyOf(weeks);
		}

		public int activityCount() {
			return this.weeks.stream().mapToInt((w) -> w.activities().size()).sum();
		}

	}

	/**
	 * @param weekNo 주차(비정규는 회차) 번호
	 * @param startsOn 화면의 주 기간 시작일. 없으면(비정규) null
	 * @param endsOn 화면의 주 기간 끝일. 없으면 null
	 * @param activities 화면 순서의 학습활동
	 */
	record SchoolWeek(int weekNo, LocalDate startsOn, LocalDate endsOn, List<SchoolActivity> activities) {

		public SchoolWeek {
			activities = List.copyOf(activities);
		}

	}

	/**
	 * 강의실 화면의 학습활동 행 하나. 시각은 학교 화면의 벽시계(기관 시간대)다.
	 * @param type 학교 메뉴 ID(링크의 {@code m}). 예: lecture_weeks, lecture_material, report, test, survey, discuss, zoom
	 * @param kind 행 class의 세부 유형이 {@code type}과 다를 때. 예: report1, test1(퀴즈), test2(중간), test3(기말). 없으면 null
	 * @param itemId 학교 활동 ID(링크의 {@code n})
	 * @param title 제목
	 * @param openAt 시작 일시. 화면에 날짜가 없으면 null
	 * @param closeAt 마감 일시. 화면에 날짜가 없으면 null
	 * @param expired 마감이 "만료됨"
	 * @param attendance 출석 표시(영상). 예: {@code 3 주 1차시}. 없으면 null
	 * @param learningTimeSec 학습시간(영상, 초). 없으면 null
	 * @param offline 오프라인 시험 표시
	 * @param done 로그인한 학생의 완료 표시(체크)
	 * @param url 이 활동을 학교 e-Class에서 여는 주소(과목을 묶고 활동으로 넘기는 학교 연결 주소)
	 */
	record SchoolActivity(String type, String kind, String itemId, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean expired, String attendance, Integer learningTimeSec, boolean offline,
			boolean done, String url) {
	}

}
