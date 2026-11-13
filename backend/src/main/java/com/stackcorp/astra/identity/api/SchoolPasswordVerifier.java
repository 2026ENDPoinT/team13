package com.stackcorp.astra.identity.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 학교 e-Class 학번·비밀번호 확인 port. {@code integration}이 학교 e-Class 로그인 화면에 로그인해 보는 adapter로 구현한다. 학교
 * 주소가 설정되지 않았으면 bean이 없고 그 로그인 수단도 없다.
 * <p>
 * 비밀번호는 확인 요청 한 번에만 쓰고 저장하지 않는다. 구현은 비밀번호와 학교 session 쿠키를 로그·예외 message에 넣지 않는다(SC-07).
 * 학교 session은 돌려준 {@link SchoolAccount#rooms()}의 메모리에만 남고, 그것을 닫으면 버린다.
 */
public interface SchoolPasswordVerifier {

	/**
	 * 학교에 로그인해 보고, 로그인되면 학교가 보여 준 프로필과 수강과목을 돌려준다.
	 * @param studentId 호출자가 형식을 검사한 학번
	 * @throws SchoolLoginException 학번·비밀번호가 틀렸거나({@code REJECTED}) 학교 서버를 쓸 수 없을 때({@code UNAVAILABLE})
	 */
	SchoolAccount verify(String studentId, String password);

	/**
	 * @param studentId 확인한 학번. 요청한 값과 같다
	 * @param name 이름. 학교 화면에서 읽지 못했으면 null
	 * @param departmentName 학과. 읽지 못했으면 null
	 * @param grade 학년(1~6). 읽지 못했으면 null
	 * @param courses 학교 메인 화면의 수강과목. 읽지 못했으면 비어 있다
	 * @param rooms 이 확인으로 열린 학교 session으로 강의실을 읽는 손잡이. 없으면 null. 받은 쪽은 {@link SchoolCourseRooms}의 규칙대로
	 * 닫는다
	 */
	record SchoolAccount(String studentId, String name, String departmentName, Integer grade,
			List<SchoolCourse> courses, SchoolCourseRooms rooms) {

		public SchoolAccount {
			courses = List.copyOf(courses);
		}

	}

	/**
	 * 학교 메인 화면 수강과목 카드 하나.
	 * @param key 학교 과목 키(KJKEY). 예: {@code A2026310982911001}, {@code 비2026B35601}
	 * @param regular 정규과목이면 true, 비정규과목이면 false
	 * @param termYear 학년도. 예: 2026
	 * @param termCode 학교 학기 코드. 정규는 1|2|3|4|5|F3(3=2학기), 비정규는 B 같은 분류
	 * @param code 학수번호·과정번호. 없으면 null
	 * @param section 분반·차수. 없으면 null
	 * @param instructors 화면의 담당자 표시. 예: {@code 손일수}, {@code 신미현 외 2명}. 없으면 null
	 * @param slots 수업 시간(정규과목). 없으면 비어 있다
	 * @param credit 학점(정규과목). 없으면 null
	 * @param learningPeriod 학습기간 표시(비정규과목). 예: {@code 2026.05.13~2026.12.21}. 없으면 null
	 * @param lectureType 수업형태(비정규과목). 예: {@code 이러닝수업}. 없으면 null
	 * @param coverTemplate 기본 표지 템플릿. 예: {@code dynamic_13}. 없으면 null
	 */
	record SchoolCourse(String key, String name, boolean regular, int termYear, String termCode, String code,
			String section, String instructors, List<Slot> slots, BigDecimal credit, String learningPeriod,
			String lectureType, String coverTemplate) {

		public SchoolCourse {
			slots = List.copyOf(slots);
		}

	}

	/**
	 * @param dayOfWeek 0 일요일 ~ 6 토요일
	 * @param periodStart 시작 교시
	 * @param periodEnd 끝 교시
	 */
	record Slot(int dayOfWeek, int periodStart, int periodEnd) {
	}

}
