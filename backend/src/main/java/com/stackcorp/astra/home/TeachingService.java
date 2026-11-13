package com.stackcorp.astra.home;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.assessment.api.GradingQueue;
import com.stackcorp.astra.assessment.api.GradingQueue.PendingGrading;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.EnrollmentRequests;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.communication.api.OpenQuestions;
import com.stackcorp.astra.communication.api.OpenQuestions.OpenQuestion;
import com.stackcorp.astra.learning.api.ActivityTargets;
import com.stackcorp.astra.learning.api.ActivityTargets.Target;
import com.stackcorp.astra.learning.api.CalendarActivities;
import com.stackcorp.astra.learning.api.CalendarActivities.CalendarActivity;
import com.stackcorp.astra.learning.api.PendingVideos;
import com.stackcorp.astra.learning.api.PendingVideos.PendingVideo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담당자 메인(교수 홈). 요청한 사람이 INSTRUCTOR로 있는 과목({@link MemberCourses}, 폐강 과목 제외)마다 채점을 기다리는 과제·퀴즈, 변환
 * 중이거나 실패한 영상, 답을 기다리는 질문, 처리할 수강·청강 신청 수를 모으고, 그 행을 확인할 일 목록으로, 앞으로
 * {@value #UPCOMING_DAYS}일 안에 시작하거나 마감하는 활동을 다가오는 일정으로 돌려준다. 수와 판정은 모두 그 데이터를 가진 모듈의
 * 공개 API가 정한다.
 * <p>
 * 담당 과목이 없는 사용자(학생)는 빈 목록을 받는다. 메인 집계({@code GET /api/me/home})가 담당 과목에 할 일을 빈 목록으로 주는 것과 같은
 * 방식이다. 과목 수와 관계없이 쿼리는 열넷 이하(시계, 과목, 채점 대기 5 이하, 영상 1, 질문 2, 신청 1, 마감 1, 시작 1, 평가 ID 1)이고
 * 읽기 전용 transaction 하나에서 읽는다. 담당 과목이 없으면 둘(시계, 과목)이다.
 */
@Service
class TeachingService {

	static final int UPCOMING_DAYS = 14;

	/** 다가오는 일정의 상한. 넘으면 시각이 늦은 쪽이 빠지고 upcomingTruncated가 true다. */
	static final int MAX_UPCOMING = 50;

	/** 확인할 일에 넣는 질문 수의 상한. 오래 기다린 질문이 먼저다. */
	static final int MAX_QUESTION_TASKS = 20;

	/** 확인할 일의 상한. 넘으면 뒤쪽 유형부터 빠지고 tasksTruncated가 true다. */
	static final int MAX_TASKS = 100;

	static final String ASSIGNMENT_GRADING = "assignment_grading";

	static final String QUIZ_GRADING = "quiz_grading";

	static final String VIDEO_FAILED = "video_failed";

	static final String VIDEO_PROCESSING = "video_processing";

	static final String ENROLLMENT_REQUESTS = "enrollment_requests";

	static final String QUESTION = "question";

	private final JdbcClient jdbc;

	private final MemberCourses courses;

	private final GradingQueue grading;

	private final PendingVideos videos;

	private final OpenQuestions questions;

	private final EnrollmentRequests requests;

	private final CalendarActivities activities;

	private final ActivityTargets targets;

	TeachingService(JdbcClient jdbc, MemberCourses courses, GradingQueue grading, PendingVideos videos,
			OpenQuestions questions, EnrollmentRequests requests, CalendarActivities activities,
			ActivityTargets targets) {
		this.jdbc = jdbc;
		this.courses = courses;
		this.grading = grading;
		this.videos = videos;
		this.questions = questions;
		this.requests = requests;
		this.activities = activities;
		this.targets = targets;
	}

	@Transactional(readOnly = true)
	TeachingView teaching(long userId) {
		LocalDateTime now = this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
		List<MemberCourse> taught = this.courses.of(userId)
			.stream()
			.filter((c) -> c.role() == CourseRole.INSTRUCTOR)
			.toList();
		if (taught.isEmpty()) {
			return new TeachingView(now, List.of(), List.of(), false, List.of(), false);
		}
		List<Long> ids = taught.stream().map(MemberCourse::courseId).toList();
		Map<Long, MemberCourse> byId = taught.stream()
			.collect(Collectors.toMap(MemberCourse::courseId, Function.identity()));
		Map<Long, Integer> order = new HashMap<>();
		ids.forEach((id) -> order.put(id, order.size()));

		List<PendingGrading> pendingGrading = this.grading.of(ids);
		List<PendingVideo> pendingVideos = this.videos.of(ids);
		Map<Long, Integer> questionCounts = this.questions.counts(ids);
		List<OpenQuestion> oldest = this.questions.oldest(ids, MAX_QUESTION_TASKS);
		Map<Long, Integer> requestCounts = this.requests.pendingCounts(userId, ids);

		List<TeachingCourse> courseViews = taught.stream().map((c) -> {
			long id = c.courseId();
			List<PendingGrading> assignments = ofKind(pendingGrading, id, "assignment");
			List<PendingGrading> quizzes = ofKind(pendingGrading, id, "quiz");
			List<PendingVideo> courseVideos = pendingVideos.stream().filter((v) -> v.courseId() == id).toList();
			return new TeachingCourse(id, c.key(), c.name(), c.code(), c.section(), assignments.size(),
					sum(assignments), quizzes.size(), sum(quizzes),
					courseVideos.stream().mapToInt(PendingVideo::failed).sum(),
					courseVideos.stream().mapToInt(PendingVideo::processing).sum(), questionCounts.getOrDefault(id, 0),
					requestCounts.get(id));
		}).toList();

		Comparator<TeachingTask> byCourse = Comparator.comparingInt((t) -> order.get(t.courseId()));
		List<TeachingTask> tasks = new ArrayList<>();
		Stream.of(task(pendingGrading, "assignment", ASSIGNMENT_GRADING, byId),
				task(pendingGrading, "quiz", QUIZ_GRADING, byId),
				pendingVideos.stream()
					.filter((v) -> v.failed() > 0)
					.map((v) -> new TeachingTask(VIDEO_FAILED, v.courseId(), byId.get(v.courseId()).name(), v.title(),
							v.failed(), v.activityId(), null, null, null, null)),
				pendingVideos.stream()
					.filter((v) -> v.processing() > 0)
					.map((v) -> new TeachingTask(VIDEO_PROCESSING, v.courseId(), byId.get(v.courseId()).name(),
							v.title(), v.processing(), v.activityId(), null, null, null, null)),
				requestCounts.entrySet()
					.stream()
					.filter((e) -> e.getValue() > 0)
					.map((e) -> new TeachingTask(ENROLLMENT_REQUESTS, e.getKey(), byId.get(e.getKey()).name(), null,
							e.getValue(), null, null, null, null, null)))
			.forEach((group) -> tasks.addAll(group.sorted(byCourse).toList()));
		oldest.forEach((q) -> tasks.add(new TeachingTask(QUESTION, q.courseId(), byId.get(q.courseId()).name(),
				q.title(), 1, null, null, null, q.questionId(), q.createdAt())));
		int openQuestions = questionCounts.values().stream().mapToInt(Integer::intValue).sum();
		boolean tasksTruncated = tasks.size() > MAX_TASKS || openQuestions > oldest.size();

		LocalDateTime until = now.plusDays(UPCOMING_DAYS);
		List<CalendarActivity> closing = this.activities.closingBetween(ids, List.of(), now, now, until,
				MAX_UPCOMING + 1);
		List<CalendarActivity> opening = this.activities.openingBetween(ids, now, until, MAX_UPCOMING + 1);
		List<Moment> moments = Stream
			.concat(closing.stream().map((a) -> new Moment("close", a.closeAt(), a)),
					opening.stream().map((a) -> new Moment("open", a.openAt(), a)))
			.sorted(Comparator.comparing(Moment::at)
				.thenComparingLong((m) -> m.activity().activityId())
				.thenComparing(Moment::event, Comparator.reverseOrder()))
			.toList();
		List<Moment> shown = moments.stream().limit(MAX_UPCOMING).toList();
		Map<Long, Target> found = this.targets.of(shown.stream().map((m) -> m.activity().activityId()).distinct().toList());
		List<UpcomingItem> upcoming = shown.stream().map((m) -> {
			CalendarActivity a = m.activity();
			MemberCourse course = byId.get(a.courseId());
			Target target = found.get(a.activityId());
			return new UpcomingItem(m.event(), m.at(), a.activityId(), a.courseId(), course.name(), course.code(),
					course.section(), CourseRole.INSTRUCTOR.name(), a.type(), a.title(), a.openAt(), a.closeAt(),
					a.published(), HomeService.targetId(target, "assignment"), HomeService.targetId(target, "quiz"),
					HomeService.targetId(target, "survey"));
		}).toList();
		boolean upcomingTruncated = closing.size() > MAX_UPCOMING || opening.size() > MAX_UPCOMING
				|| moments.size() > MAX_UPCOMING;

		return new TeachingView(now, courseViews, tasks.stream().limit(MAX_TASKS).toList(), tasksTruncated, upcoming,
				upcomingTruncated);
	}

	private static List<PendingGrading> ofKind(List<PendingGrading> all, long courseId, String kind) {
		return all.stream().filter((p) -> p.courseId() == courseId && p.kind().equals(kind)).toList();
	}

	private static int sum(List<PendingGrading> rows) {
		return rows.stream().mapToInt(PendingGrading::count).sum();
	}

	private static Stream<TeachingTask> task(List<PendingGrading> all, String kind, String type,
			Map<Long, MemberCourse> byId) {
		boolean assignment = kind.equals("assignment");
		return all.stream()
			.filter((p) -> p.kind().equals(kind))
			.map((p) -> new TeachingTask(type, p.courseId(), byId.get(p.courseId()).name(), p.title(), p.count(),
					p.activityId(), assignment ? p.targetId() : null, assignment ? null : p.targetId(), null, null));
	}

	/** 활동이 다가오는 일정에 놓이는 한 시점. 같은 활동이 기간 안에 시작하고 마감하면 둘이다. */
	private record Moment(String event, LocalDateTime at, CalendarActivity activity) {
	}

	/**
	 * @param serverTime 판정에 쓴 DB 시각
	 * @param courses 담당 과목. 메인 집계의 과목 순서(현재 학기, 학기 순서, 과목명)다
	 * @param tasks 확인할 일. 유형 순서(과제 채점, 퀴즈 채점, 영상 변환 실패, 영상 변환 중, 수강·청강 신청, 질문)이고 같은 유형은 과목
	 * 순서다. 질문은 오래 기다린 순서로 {@value #MAX_QUESTION_TASKS}개까지, 전체는 {@value #MAX_TASKS}개까지다
	 * @param tasksTruncated 확인할 일이 상한을 넘었거나 답을 기다리는 질문이 목록보다 많은지. 과목별 수는 잘리지 않는다
	 * @param upcoming 지금부터 {@value #UPCOMING_DAYS}일 안에 시작하거나 마감하는 담당 과목의 활동. 시각 순으로 {@value #MAX_UPCOMING}개까지
	 * @param upcomingTruncated 다가오는 일정이 상한을 넘어 늦은 쪽이 빠졌는지
	 */
	record TeachingView(LocalDateTime serverTime, List<TeachingCourse> courses, List<TeachingTask> tasks,
			boolean tasksTruncated, List<UpcomingItem> upcoming, boolean upcomingTruncated) {
	}

	/**
	 * @param assignmentsToGrade 채점을 기다리는 제출이 있는 과제 수
	 * @param submissionsToGrade 채점을 기다리는 과제 제출 수(수강생의 최종 제출 가운데 점수가 빈 것)
	 * @param quizzesToGrade 수동 채점을 기다리는 응시가 있는 퀴즈 수
	 * @param attemptsToGrade 수동 채점을 기다리는 퀴즈 응시 수
	 * @param videosFailed 변환에 실패한 영상 콘텐츠 수
	 * @param videosProcessing 변환을 기다리거나 변환 중인 영상 콘텐츠 수
	 * @param unansweredQuestions 답글이 없고 해결로 표시하지 않은 질의응답 질문 수(FAQ 제외)
	 * @param pendingRequests 처리를 기다리는 수강·청강 신청 수. 요청한 사람이 그 과목의 신청을 처리할 수 없으면(조교) null
	 */
	record TeachingCourse(long courseId, String key, String name, String code, String section, int assignmentsToGrade,
			int submissionsToGrade, int quizzesToGrade, int attemptsToGrade, int videosFailed, int videosProcessing,
			int unansweredQuestions, Integer pendingRequests) {
	}

	/**
	 * 확인할 일 한 줄.
	 *
	 * @param type assignment_grading|quiz_grading|video_failed|video_processing|enrollment_requests|question
	 * @param title 과제·퀴즈·영상 활동 제목 또는 질문 제목. enrollment_requests면 null
	 * @param count 채점을 기다리는 제출·응시 수, 변환 실패·변환 중 콘텐츠 수, 처리를 기다리는 신청 수. question이면 1
	 * @param activityId 과제·퀴즈·영상 활동
	 * @param assignmentId assignment_grading만
	 * @param quizId quiz_grading만
	 * @param questionId question만
	 * @param createdAt question만. 질문 작성 일시
	 */
	record TeachingTask(String type, long courseId, String courseName, String title, int count,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long activityId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long assignmentId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long quizId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long questionId,
			@JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime createdAt) {
	}

	/**
	 * 다가오는 일정 한 줄. {@code GET /api/me/calendar}의 activities 항목과 같은 속성에 event와 at을 더했다. completed는 담당 과목이라 없다.
	 *
	 * @param event open(시작) 또는 close(마감)
	 * @param at event가 open이면 openAt, close면 closeAt
	 * @param role 언제나 INSTRUCTOR
	 * @param published 공개 여부. 공개하지 않은 활동도 나온다
	 */
	record UpcomingItem(String event, LocalDateTime at, long activityId, long courseId, String courseName,
			String courseCode, String section, String role, String type, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published, @JsonInclude(JsonInclude.Include.NON_NULL) Long assignmentId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long quizId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long surveyId) {
	}

}
