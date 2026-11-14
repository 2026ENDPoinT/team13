package com.stackcorp.astra.identity.api;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolAssignment;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolDownload;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;

/**
 * 학번·비밀번호 확인({@link SchoolPasswordVerifier#verify})으로 열린 학교 e-Class session으로 학생의 강의실을 읽는 손잡이. 학교
 * session 쿠키는 이 객체의 메모리에만 있고 저장하거나 로그·예외·다른 객체로 내보내지 않는다(SC-07).
 * <ul>
 * <li>한 객체는 한 주인만 닫는다. 주인이 되려는 쪽은 먼저 {@link #claim()}이 true인지 본다. 로그인에 성공하면 identity가 STack
 * session에 묶어 가져가고({@link SchoolSessions}) 그 session이 끝날 때 닫는다. 로그인 요청을 받은 쪽은 응답 전에 {@code claim()}이
 * true면(아무도 가져가지 않았으면) 바로 닫는다. 다른 쪽은 닫지 않고 읽기만 한다.</li>
 * <li>학교 강의실은 session에 과목 하나가 묶이는 구조라 요청을 차례로 보낸다. 구현은 한 객체의 읽기를 하나씩 차례로 처리한다.</li>
 * <li>목록 읽기({@link #read}, {@link #files}, {@link #notices})는 학교 쪽 상태를 바꾸지 않는다. 상세 읽기와 파일 받기는 학교에서
 * 조회수나 읽음 표시가 바뀔 수 있으므로, 그 학생이 STack에서 그 항목을 열었을 때만 그 학생의 손잡이로 부른다. 로그인 때, 뒤에서 도는
 * 작업, 미리 읽기로 부르지 않는다.</li>
 * <li>영상 학습창(출석·학습시간을 학교에 보내는 요청)과 2차 인증에 닿는 요청은 보내지 않는다.</li>
 * </ul>
 */
public interface SchoolCourseRooms {

	/** @return 처음 부른 쪽만 true. 그쪽이 이 객체를 닫을 책임을 진다 */
	boolean claim();

	/**
	 * @return {@link #close()} 전이면 true. 줄에서 기다리던 가져오기가 시작하기 전에 학생이 로그아웃했는지(session이 끝나 닫혔는지) 본다
	 */
	boolean isOpen();

	/**
	 * 과목 하나의 강의실 첫 화면(주차와 학습활동 목록)을 읽는다.
	 * @param courseKey 학교 과목 키(KJKEY). 로그인 때 읽은 수강과목의 키
	 * @param requestTimeout HTTP 요청 하나의 제한 시간
	 * @param remaining 이 읽기 전체가 넘지 않을 시간
	 * @throws SchoolLoginException 학교 서버를 쓸 수 없거나 화면을 알아보지 못할 때. message는 이유 코드뿐이다
	 */
	SchoolCourseRoom read(String courseKey, Duration requestTimeout, Duration remaining);

	/**
	 * 과목의 파일모음({@code files_list.acl}): 자료·과제에 붙은 파일 전체. 목록 화면이라 학교 쪽 상태를 바꾸지 않는다.
	 * @throws SchoolLoginException {@code EXPIRED}면 학교 session이 끝났다
	 */
	List<SchoolFile> files(String courseKey, Duration requestTimeout, Duration remaining);

	/**
	 * 자료 하나의 상세와 첨부 목록. 학교에서 그 자료의 조회수가 하나 는다.
	 * @param itemId 학교 자료 번호(강의실 목록 링크의 {@code n})
	 * @throws SchoolLoginException {@code NOT_FOUND}면 학교가 그 자료를 보여 주지 않았다
	 */
	SchoolPost material(String courseKey, String itemId, Duration requestTimeout, Duration remaining);

	/**
	 * 과목의 강의실 공지 목록({@code notice_list.acl}) 전체. 목록 화면이라 학교 쪽 읽음 표시를 바꾸지 않는다. 화면 순서다.
	 * @throws SchoolLoginException {@code EXPIRED}면 학교 session이 끝났다. {@code UNAVAILABLE}이면 목록을 알아보지 못했다
	 */
	List<SchoolNotice> notices(String courseKey, Duration requestTimeout, Duration remaining);

	/**
	 * 공지 하나의 상세와 첨부 목록({@code notice_view_pop.acl}). 학교에서 그 학생이 그 공지를 읽은 것으로 표시되고 조회수가 하나 는다.
	 * "공지를 확인했습니다"({@code notice_confirm.acl})는 보내지 않는다.
	 * @param itemId 학교 공지 번호({@link SchoolNotice#itemId()})
	 * @throws SchoolLoginException {@code NOT_FOUND}면 학교가 그 공지를 보여 주지 않았다
	 */
	SchoolPost notice(String courseKey, String itemId, Duration requestTimeout, Duration remaining);

	/**
	 * 과제 하나의 상세, 첨부, 그 학생 본인의 제출 정보와 제출 파일({@code report_view_form.acl} 다음 {@code efile_list.acl} 둘). 학교에서
	 * 조회수나 읽음 표시가 바뀔 수 있다. 제출·수정·삭제 요청은 보내지 않는다.
	 * @param itemId 학교 과제 번호({@code RT_SEQ}, 강의실 목록 링크의 {@code n})
	 * @throws SchoolLoginException {@code NOT_FOUND}면 학교가 그 과제를 보여 주지 않았다
	 */
	SchoolAssignment assignment(String courseKey, String itemId, Duration requestTimeout, Duration remaining);

	/**
	 * 첨부 파일 하나를 받아 {@code target}에 쓴다.
	 * @param maxBytes 이보다 크면 받지 않는다({@code UNAVAILABLE} {@code too_large})
	 */
	SchoolDownload download(String courseKey, SchoolFile file, long maxBytes, Path target, Duration requestTimeout,
			Duration remaining);

	// 영상 상세, 강의계획서, 출석, 성적(ADR-0043 4단계·5단계). 모두 그 학생이 STack에서 그 화면을 열었을 때만 부른다.

	/**
	 * 영상 활동 하나의 상세({@code online_view_form.acl}): 콘텐츠 목록, 진도율, 학습시간, 출석인정 시간. 학습창, 영상 주소 조회, 시청
	 * 기록 요청은 보내지 않는다.
	 * @param itemId 학교 영상 활동 번호(강의실 목록 링크의 {@code n})
	 * @throws SchoolLoginException {@code NOT_FOUND}면 학교가 그 활동을 보여 주지 않았다
	 */
	SchoolVideo video(String courseKey, String itemId, Duration requestTimeout, Duration remaining);

	/**
	 * 정규과목의 강의계획서({@code regular_view_pop.acl}, {@code regular_view_plan.acl}). 요청 값은 강의실 첫 화면의 script에서 읽는다.
	 * @throws SchoolLoginException {@code NOT_FOUND}면 학교가 계획서를 주지 않았다(비정규 과정 등)
	 */
	SchoolSyllabus syllabus(String courseKey, Duration requestTimeout, Duration remaining);

	/** 학생 본인의 출석({@code attendance_list.acl}, 모든 주차). 주차 순서다. */
	List<SchoolAttendanceWeek> attendance(String courseKey, Duration requestTimeout, Duration remaining);

	/** 학생 본인의 평가 활동과 점수({@code eval3_result_view_detail_list.acl}, 모든 유형). 화면 순서다. */
	List<SchoolGradeItem> grades(String courseKey, Duration requestTimeout, Duration remaining);

	/** 쿠키를 버린다. 여러 번 불러도 된다. 닫은 뒤 읽기는 {@code EXPIRED}로 실패한다. */
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
