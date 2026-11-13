package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 담당자 메인(GET /api/me/teaching). 담당 과목만 세고, 수와 판정은 각 모듈의 규칙을 따르며, 과목 수와 관계없이 쿼리 수가 같다.
 */
class TeachingApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long prof;

	ApiClient professor;

	@BeforeEach
	void professor() {
		this.prof = this.data.user("PROFESSOR");
		this.professor = new ApiClient(this.mvc, this.data.session(this.prof));
	}

	@Test
	void countsTasksAndUpcomingOfTaughtCoursesOnly() throws Exception {
		long c1 = this.data.course();
		this.data.instructor(c1, this.prof);
		// 조교로 맡은 과목. 신청을 처리할 수 없으므로 신청 수가 null이다.
		long c2 = this.data.course();
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, 'TA')")
			.param("c", c2)
			.param("u", this.prof)
			.update();
		long alice = this.data.user("STUDENT");
		long bob = this.data.user("STUDENT");
		long carol = this.data.user("STUDENT");
		this.data.enroll(c1, alice);
		this.data.enroll(c1, bob);
		this.data.enroll(c1, carol);
		this.data.enroll(c2, alice);

		// 과제: alice는 제출만, bob은 제출하고 점수를 받았고, carol은 제출 뒤 수강을 취소했다. 기다리는 것은 alice 하나다.
		long assignment = assignment(c1, "과제 1");
		submit(assignment, alice);
		submit(assignment, bob);
		submit(assignment, carol);
		score(c1, "assignment", bob);
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", c1)
			.param("u", carol)
			.update();
		// 지운 과제의 제출은 세지 않는다.
		long deleted = assignment(c1, "지운 과제");
		submit(deleted, alice);
		this.jdbc.sql("""
				UPDATE learning_activities SET deleted_at = CURRENT_TIMESTAMP(3)
				 WHERE activity_id = (SELECT activity_id FROM assignments WHERE assignment_id = :a)
				""").param("a", deleted).update();
		// 퀴즈: 수동 채점을 기다리는 응시 하나, 채점을 마친 응시 하나.
		long quiz = quiz(c1);
		attempt(quiz, alice, "submitted");
		attempt(quiz, bob, "graded");
		// 영상: 실패, 변환 중, 준비됨 콘텐츠가 하나씩. 지운 콘텐츠는 세지 않는다.
		long video = this.data.activity(c1, "lecture_weeks", false, null, null);
		item(video, 1, "failed", false);
		item(video, 2, "processing", false);
		item(video, 3, "ready", false);
		item(video, 4, "failed", true);
		// 질문: 답을 기다리는 것 하나. 답글이 달린 질문, 해결로 표시한 질문은 빠진다.
		ApiClient aliceClient = new ApiClient(this.mvc, this.data.session(alice));
		long open = question(aliceClient, c1, "조인 질문");
		long answered = question(aliceClient, c1, "답한 질문");
		this.professor.postJson("/api/courses/" + c1 + "/qna/" + answered + "/answers", "{\"body\":\"답\"}")
			.andExpect(status().isCreated());
		long solved = question(aliceClient, c1, "해결한 질문");
		this.jdbc.sql("UPDATE posts SET pro_status = 'Y' WHERE post_id = :p").param("p", solved).update();
		// 청강 신청 하나.
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
				VALUES (:c, :u, 'AUDIT', 'AUDITOR', 'REQUESTED')
				""").param("c", c1).param("u", this.data.user("STUDENT")).update();
		// 다가오는 일정: 하루 뒤 마감, 사흘 뒤 시작(공개 전)은 들고, 스무날 뒤 마감은 빠진다.
		long closing = activity(c1, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		long opening = activity(c2, false, "CURRENT_TIMESTAMP + INTERVAL 3 DAY", "CURRENT_TIMESTAMP + INTERVAL 20 DAY");
		activity(c1, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 20 DAY");

		// 다른 교수의 과목. 같은 종류의 일이 있어도 나오지 않는다.
		long otherProf = this.data.user("PROFESSOR");
		long c3 = this.data.course();
		this.data.instructor(c3, otherProf);
		this.data.enroll(c3, alice);
		question(aliceClient, c3, "다른 과목 질문");
		activity(c3, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");

		this.professor.get("/api/me/teaching")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.serverTime").exists())
			.andExpect(jsonPath("$.courses[*].courseId").value(contains((int) c1, (int) c2)))
			.andExpect(jsonPath("$.courses[0].assignmentsToGrade").value(1))
			.andExpect(jsonPath("$.courses[0].submissionsToGrade").value(1))
			.andExpect(jsonPath("$.courses[0].quizzesToGrade").value(1))
			.andExpect(jsonPath("$.courses[0].attemptsToGrade").value(1))
			.andExpect(jsonPath("$.courses[0].videosFailed").value(1))
			.andExpect(jsonPath("$.courses[0].videosProcessing").value(1))
			.andExpect(jsonPath("$.courses[0].unansweredQuestions").value(1))
			.andExpect(jsonPath("$.courses[0].pendingRequests").value(1))
			.andExpect(jsonPath("$.courses[1].submissionsToGrade").value(0))
			.andExpect(jsonPath("$.courses[1].unansweredQuestions").value(0))
			.andExpect(jsonPath("$.courses[1].pendingRequests").value(nullValue()))
			.andExpect(jsonPath("$.tasks[*].type").value(contains("assignment_grading", "quiz_grading", "video_failed",
					"video_processing", "enrollment_requests", "question")))
			.andExpect(jsonPath("$.tasks[0].assignmentId").value(assignment))
			.andExpect(jsonPath("$.tasks[0].activityId").exists())
			.andExpect(jsonPath("$.tasks[0].title").value("과제 1"))
			.andExpect(jsonPath("$.tasks[0].count").value(1))
			.andExpect(jsonPath("$.tasks[0].quizId").doesNotExist())
			.andExpect(jsonPath("$.tasks[1].quizId").value(quiz))
			.andExpect(jsonPath("$.tasks[2].activityId").value(video))
			.andExpect(jsonPath("$.tasks[4].courseId").value(c1))
			.andExpect(jsonPath("$.tasks[4].count").value(1))
			.andExpect(jsonPath("$.tasks[5].questionId").value(open))
			.andExpect(jsonPath("$.tasks[5].title").value("조인 질문"))
			.andExpect(jsonPath("$.tasks[5].createdAt").exists())
			.andExpect(jsonPath("$.tasksTruncated").value(false))
			.andExpect(jsonPath("$.upcoming[*].activityId").value(contains((int) closing, (int) opening)))
			.andExpect(jsonPath("$.upcoming[*].event").value(contains("close", "open")))
			.andExpect(jsonPath("$.upcoming[0].role").value("INSTRUCTOR"))
			.andExpect(jsonPath("$.upcoming[1].published").value(false))
			.andExpect(jsonPath("$.upcoming[1].courseId").value(c2))
			.andExpect(jsonPath("$.upcoming[0].completed").doesNotExist())
			.andExpect(jsonPath("$.upcomingTruncated").value(false));

		// 점수를 넣으면 채점할 일에서 빠진다.
		score(c1, "assignment", alice);
		this.professor.get("/api/me/teaching")
			.andExpect(jsonPath("$.courses[0].assignmentsToGrade").value(0))
			.andExpect(jsonPath("$.courses[0].submissionsToGrade").value(0))
			.andExpect(jsonPath("$.tasks[0].type").value("quiz_grading"));

		// 학생(담당 과목 없음)은 빈 목록을 받는다. 수강 과목의 일은 보이지 않는다.
		aliceClient.get("/api/me/teaching")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.serverTime").exists())
			.andExpect(jsonPath("$.courses").isEmpty())
			.andExpect(jsonPath("$.tasks").isEmpty())
			.andExpect(jsonPath("$.upcoming").isEmpty());
		new ApiClient(this.mvc, null).get("/api/me/teaching").andExpect(status().isUnauthorized());
	}

	@Test
	void statementCountDoesNotGrowWithTheNumberOfCourses() throws Exception {
		ApiClient few = professorWithCourses(2);
		ApiClient many = professorWithCourses(8);
		few.get("/api/me/teaching").andExpect(jsonPath("$.courses.length()").value(2));
		many.get("/api/me/teaching")
			.andExpect(jsonPath("$.courses.length()").value(8))
			.andExpect(jsonPath("$.courses[*].submissionsToGrade").value(contains(1, 1, 1, 1, 1, 1, 1, 1)))
			.andExpect(jsonPath("$.courses[*].attemptsToGrade").value(contains(1, 1, 1, 1, 1, 1, 1, 1)))
			.andExpect(jsonPath("$.courses[*].videosFailed").value(contains(1, 1, 1, 1, 1, 1, 1, 1)))
			.andExpect(jsonPath("$.courses[*].unansweredQuestions").value(contains(1, 1, 1, 1, 1, 1, 1, 1)))
			.andExpect(jsonPath("$.courses[*].pendingRequests").value(contains(1, 1, 1, 1, 1, 1, 1, 1)))
			.andExpect(jsonPath("$.tasks.length()").value(8 * 5))
			.andExpect(jsonPath("$.upcoming.length()").value(8));

		long fewStatements = statements(few);
		long manyStatements = statements(many);
		// 시계, 과목, 채점 대기 5, 영상 1, 질문 2, 신청 1, 마감 1, 시작 1, 평가 ID 1(애플리케이션 14)과 session 확인, transaction 문장뿐이다.
		assertThat(manyStatements).isEqualTo(fewStatements);
		assertThat(manyStatements).isLessThanOrEqualTo(23);
	}

	/** 과목마다 기다리는 과제 제출, 퀴즈 응시, 실패한 영상, 질문, 청강 신청, 하루 뒤 마감 활동을 하나씩 둔다. */
	private ApiClient professorWithCourses(int count) throws Exception {
		long professorId = this.data.user("PROFESSOR");
		ApiClient client = new ApiClient(this.mvc, this.data.session(professorId));
		for (int i = 0; i < count; i++) {
			long c = this.data.course();
			this.data.instructor(c, professorId);
			long stu = this.data.user("STUDENT");
			this.data.enroll(c, stu);
			submit(assignment(client, c, "과제"), stu);
			attempt(quiz(client, c), stu, "submitted");
			item(this.data.activity(c, "lecture_weeks", true, null, null), 1, "failed", false);
			question(new ApiClient(this.mvc, this.data.session(stu)), c, "질문 " + i);
			this.jdbc.sql("""
					INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
					VALUES (:c, :u, 'AUDIT', 'AUDITOR', 'REQUESTED')
					""").param("c", c).param("u", this.data.user("STUDENT")).update();
			activity(c, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		}
		return client;
	}

	/** GET /api/me/teaching 한 번 동안 DB가 받은 문장 수. 서버 전체 counter라 세 번 재어 가장 작은 값을 쓴다. */
	private long statements(ApiClient client) throws Exception {
		long min = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = questions();
			client.get("/api/me/teaching").andExpect(status().isOk());
			min = Math.min(min, questions() - before);
		}
		return min;
	}

	private long questions() {
		return this.jdbc.sql("SHOW GLOBAL STATUS LIKE 'Questions'").query((rs, n) -> rs.getLong(2)).single();
	}

	private long assignment(long courseId, String title) throws Exception {
		return assignment(this.professor, courseId, title);
	}

	private static long assignment(ApiClient client, long courseId, String title) throws Exception {
		return ApiClient.json(client.postJson("/api/courses/" + courseId + "/assignments",
				"{\"weekNo\":1,\"title\":\"" + title + "\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":10}")
			.andExpect(status().isCreated())).get("assignment").get("assignmentId").asLong();
	}

	private long quiz(long courseId) throws Exception {
		return quiz(this.professor, courseId);
	}

	private static long quiz(ApiClient client, long courseId) throws Exception {
		return ApiClient.json(client.postJson("/api/courses/" + courseId + "/quizzes", """
				{"weekNo":1,"title":"퀴즈","closeAt":"2999-01-01T00:00:00",
				 "questions":[{"type":"short","text":"정규화를 설명하시오","points":5}]}
				""").andExpect(status().isCreated())).get("quiz").get("quizId").asLong();
	}

	/** 최종 제출(현재 버전 1). */
	private void submit(long assignmentId, long userId) {
		this.jdbc.sql("""
				INSERT INTO assignment_submissions (assignment_id, owner_type, user_id, current_version_no, first_submitted_at,
				                                    submitted_at)
				VALUES (:a, 'user', :u, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
				""").param("a", assignmentId).param("u", userId).update();
	}

	private void score(long courseId, String itemType, long userId) {
		this.jdbc.sql("""
				INSERT INTO grade_scores (grade_item_id, user_id, score, graded_at)
				SELECT grade_item_id, :u, 8, CURRENT_TIMESTAMP(3) FROM grade_items WHERE course_id = :c AND item_type = :t
				""").param("u", userId).param("c", courseId).param("t", itemType).update();
	}

	private void attempt(long quizId, long userId, String state) {
		this.jdbc.sql("""
				INSERT INTO quiz_attempts (quiz_id, user_id, attempt_no, status, started_at, deadline_at, submitted_at)
				VALUES (:q, :u, 1, :s, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
				""").param("q", quizId).param("u", userId).param("s", state).update();
	}

	private void item(long activityId, int seq, String videoState, boolean deleted) {
		this.jdbc.sql("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, video_state, deleted_at)
				VALUES (:a, :seq, '영상', 'video', :state, CASE WHEN :deleted THEN CURRENT_TIMESTAMP(3) END)
				""").param("a", activityId).param("seq", seq).param("state", videoState).param("deleted", deleted).update();
	}

	private static long question(ApiClient client, long courseId, String title) throws Exception {
		return ApiClient.json(client.postJson("/api/courses/" + courseId + "/qna", "{\"title\":\"" + title + "\"}")
			.andExpect(status().isCreated())).get("postId").asLong();
	}

	/**
	 * @param openAt SQL 식. DB 시계 기준으로 쓴다
	 * @param closeAt SQL 식
	 */
	private long activity(long courseId, boolean published, String openAt, String closeAt) {
		long week = this.data.insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
				Map.of("c", courseId, "n", (int) (Math.random() * 1_000_000) + 1));
		return this.data.insert("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, published_yn, open_at, close_at)
				VALUES (:c, :w, 'report', '일정', :pub, %s, %s)
				""".formatted(openAt, closeAt), Map.of("c", courseId, "w", week, "pub", published));
	}

}
