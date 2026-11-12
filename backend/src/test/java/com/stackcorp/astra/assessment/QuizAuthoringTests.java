package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 담당자의 퀴즈 출제와 수정, 학생이 보는 퀴즈 정보. 문항은 응시가 생기면 바꾸지 않고, 정답은 담당자 응답에만 있다(ADR-0021, SEC-14).
 */
class QuizAuthoringTests extends QuizTestSupport {

	@Test
	void instructorCreatesReadsAndEditsAQuizWithOrderedQuestions() throws Exception {
		JsonNode created = createQuiz("\"timeLimitMinutes\":30,\"attemptLimit\":2,\"shuffleQuestions\":true");
		long id = created.get("quiz").get("quizId").asLong();
		assertThat(created.get("revision").asLong()).isEqualTo(1);
		assertThat(created.get("quiz").get("examType").asString()).isEqualTo("online");
		assertThat(created.get("quiz").get("maxScore").decimalValue()).isEqualByComparingTo("10");
		assertThat(created.get("quiz").get("questionCount").asInt()).isEqualTo(3);
		assertThat(created.get("questionsLocked").asBoolean()).isFalse();
		this.professor.get(quiz(this.course, id))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.phase").value("open"))
			.andExpect(jsonPath("$.quiz.timeLimitMinutes").value(30))
			.andExpect(jsonPath("$.quiz.attemptLimit").value(2))
			.andExpect(jsonPath("$.quiz.shuffleQuestions").value(true))
			.andExpect(jsonPath("$.questions.length()").value(3))
			.andExpect(jsonPath("$.questions[0].number").value(1))
			.andExpect(jsonPath("$.questions[0].type").value("single"))
			.andExpect(jsonPath("$.questions[0].options[0].correct").value(true))
			.andExpect(jsonPath("$.questions[0].options[1].correct").value(false))
			.andExpect(jsonPath("$.questions[1].type").value("multiple"))
			.andExpect(jsonPath("$.questions[1].explanation").value("2와 4"))
			.andExpect(jsonPath("$.questions[2].type").value("short"))
			.andExpect(jsonPath("$.questions[2].options.length()").value(0));
		// 활동은 learning의 test 활동, 배점과 공개는 그 퀴즈의 성적 항목 한 행이다.
		assertThat(this.jdbc.sql("SELECT activity_type FROM learning_activities WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.query(String.class)
			.single()).isEqualTo("test");
		assertThat(this.jdbc.sql("SELECT CONCAT(item_type, ':', max_score, ':', score_visibility) FROM grade_items WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.query(String.class)
			.single()).isEqualTo("quiz:10.00:hidden");

		String edit = """
				{"weekNo":3,"title":"퀴즈 1 (수정)","closeAt":"2999-02-01T00:00:00","timeLimitMinutes":20,
				 "resultReleaseAt":"2999-03-01T09:00:00","answersVisible":true,"revision":1}
				""";
		this.professor.putJson(quiz(this.course, id), edit)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2))
			.andExpect(jsonPath("$.quiz.title").value("퀴즈 1 (수정)"))
			.andExpect(jsonPath("$.quiz.weekNo").value(3))
			.andExpect(jsonPath("$.quiz.timeLimitMinutes").value(20))
			.andExpect(jsonPath("$.quiz.attemptLimit").doesNotExist())
			.andExpect(jsonPath("$.quiz.resultReleaseAt").value("2999-03-01T09:00:00"))
			.andExpect(jsonPath("$.quiz.answersVisible").value(true));
		// 다른 수정을 덮어쓰지 않는다.
		this.professor.putJson(quiz(this.course, id), edit)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));

		String questions = """
				{"revision":2,"questions":[{"type":"short","text":"새 문항","points":7.5}]}
				""";
		this.professor.putJson(quiz(this.course, id) + "/questions", questions)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(3))
			.andExpect(jsonPath("$.quiz.maxScore").value(7.5))
			.andExpect(jsonPath("$.questions.length()").value(1))
			.andExpect(jsonPath("$.questions[0].text").value("새 문항"));
		this.professor.putJson(quiz(this.course, id) + "/questions", questions)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));

		this.professor.get(quizzes(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].quizId").value(id))
			.andExpect(jsonPath("$[0].weekNo").value(3))
			.andExpect(jsonPath("$[0].finishedCount").value(0))
			.andExpect(jsonPath("$[0].status").doesNotExist());
		assertThat(auditCount("quiz.create", id)).isEqualTo(1);
		assertThat(auditCount("quiz.update", id)).isEqualTo(1);
		assertThat(auditCount("quiz.questions.update", id)).isEqualTo(1);
	}

	@Test
	void sameIdempotencyKeyCreatesOneQuiz() throws Exception {
		String body = """
				{"weekNo":1,"title":"재전송","closeAt":"2999-01-01T00:00:00","questions":%s}
				""".formatted(QUESTIONS);
		var request = MockMvcRequestBuilders.post(quizzes(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("Idempotency-Key", "quiz-create-1");
		String first = this.professor.send(request).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		String second = this.professor.send(MockMvcRequestBuilders.post(quizzes(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("Idempotency-Key", "quiz-create-1")).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(ApiClient.json(this.professor.get(quizzes(this.course)))).hasSize(1);
		assertThat(second).isEqualTo(first);
	}

	@Test
	void studentsSeeOnlyOpenPublishedQuizzesAndNeverTheQuestionsOrAnswers() throws Exception {
		long id = quiz("\"timeLimitMinutes\":30");
		long hidden = quiz("\"published\":false");
		long future = quiz("\"openAt\":\"2998-12-31T00:00:00\"");
		String body = this.student.get(quiz(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quiz.title").value("퀴즈 1"))
			.andExpect(jsonPath("$.quiz.questionCount").value(3))
			.andExpect(jsonPath("$.quiz.maxScore").value(10.0))
			.andExpect(jsonPath("$.phase").value("open"))
			.andExpect(jsonPath("$.canStart").value(true))
			.andExpect(jsonPath("$.attemptsUsed").value(0))
			.andExpect(jsonPath("$.attempts.length()").value(0))
			.andExpect(jsonPath("$.questions").doesNotExist())
			.andExpect(jsonPath("$.revision").doesNotExist())
			.andExpect(jsonPath("$.score").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("correct").doesNotContain("explanation").doesNotContain("정규화");
		this.student.get(quiz(this.course, hidden)).andExpect(status().isNotFound());
		this.student.get(quiz(this.course, future)).andExpect(status().isNotFound());
		this.student.post(attempts(this.course, hidden)).andExpect(status().isNotFound());
		this.student.post(attempts(this.course, future)).andExpect(status().isNotFound());
		this.student.get(quizzes(this.course))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].quizId").value(id))
			.andExpect(jsonPath("$[0].status").value("not_started"))
			.andExpect(jsonPath("$[0].attemptsUsed").value(0))
			.andExpect(jsonPath("$[0].finishedCount").doesNotExist());
	}

	@Test
	void questionsFreezeOnceAnAttemptExistsButSettingsStillChange() throws Exception {
		long id = quiz("\"timeLimitMinutes\":30");
		long firstQuestion = questionId(id, 0);
		long attemptId = start(this.student, id);
		this.professor.putJson(quiz(this.course, id) + "/questions",
				"{\"revision\":1,\"questions\":[{\"type\":\"short\",\"text\":\"바꾼 문항\",\"points\":1}]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("questions_locked"));
		this.professor.get(quiz(this.course, id))
			.andExpect(jsonPath("$.questionsLocked").value(true))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.inProgressCount").value(1));
		this.professor.putJson(quiz(this.course, id),
				"{\"weekNo\":2,\"title\":\"제목만 수정\",\"closeAt\":\"2999-01-01T00:00:00\",\"timeLimitMinutes\":60,\"revision\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2));
		// 진행 중 응시는 같은 문항을 보고, 시작할 때 고정한 마감 일시는 제한 시간을 바꿔도 그대로다.
		JsonNode attempt = ApiClient.json(this.student.get(attempt(this.course, id, attemptId)).andExpect(status().isOk()));
		assertThat(attempt.get("questions")).hasSize(3);
		assertThat(List.of(attempt.get("questions").get(0).get("questionId").asLong(),
				attempt.get("questions").get(1).get("questionId").asLong(),
				attempt.get("questions").get(2).get("questionId").asLong())).contains(firstQuestion);
		assertThat(Duration.between(LocalDateTime.parse(attempt.get("startedAt").asString()),
				LocalDateTime.parse(attempt.get("deadlineAt").asString()))).isEqualTo(Duration.ofMinutes(30));
	}

	@Test
	void offlineQuizIsShownButCannotBeTaken() throws Exception {
		String body = """
				{"weekNo":8,"title":"중간고사","examType":"offline","openAt":"2000-01-01T00:00:00",
				 "closeAt":"2999-01-01T00:00:00","maxScore":90,"resultReleaseAt":"2000-01-02T00:00:00"}
				""";
		long id = ApiClient.json(this.professor.postJson(quizzes(this.course), body).andExpect(status().isCreated()))
			.get("quiz")
			.get("quizId")
			.asLong();
		this.student.get(quiz(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quiz.examType").value("offline"))
			.andExpect(jsonPath("$.quiz.maxScore").value(90.0))
			.andExpect(jsonPath("$.quiz.questionCount").value(0))
			.andExpect(jsonPath("$.canStart").value(false))
			.andExpect(jsonPath("$.score").doesNotExist());
		this.student.post(attempts(this.course, id))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("offline_quiz"));
		assertThat(this.jdbc.sql("SELECT offline_yn FROM learning_activities WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.query(Boolean.class)
			.single()).isTrue();
		// 오프라인 시험에 들어온 점수는 결과 공개 뒤에 평가 카드로 보인다(66 화면).
		long gradeItem = this.jdbc.sql("SELECT grade_item_id FROM grade_items WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.query(Long.class)
			.single();
		this.jdbc.sql("INSERT INTO grade_scores (grade_item_id, user_id, score) VALUES (:item, :user, 82)")
			.param("item", gradeItem)
			.param("user", this.studentId)
			.update();
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.score").value(82.0));
		this.classmate.get(quiz(this.course, id)).andExpect(jsonPath("$.score").doesNotExist());
		this.professor.putJson(quiz(this.course, id) + "/questions", "{\"revision\":1,\"questions\":[]}")
			.andExpect(status().isBadRequest());
		this.professor.putJson(quiz(this.course, id),
				"{\"weekNo\":8,\"title\":\"중간고사\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":80,\"revision\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("score_exceeds_max"));
	}

	@Test
	void onlyDocumentedAndValidFieldsAreAccepted() throws Exception {
		long id = quiz("");
		String base = "{\"weekNo\":1,\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\"";
		List<String> invalid = List.of(
				base + ",\"questions\":" + QUESTIONS + ",\"ownerId\":1}",
				base + ",\"questions\":[{\"type\":\"essay\",\"text\":\"q\",\"points\":1}]}",
				base + ",\"questions\":[{\"type\":\"single\",\"text\":\"q\",\"points\":1,\"options\":[{\"text\":\"a\",\"correct\":true},{\"text\":\"b\",\"correct\":true}]}]}",
				base + ",\"questions\":[{\"type\":\"multiple\",\"text\":\"q\",\"points\":1,\"options\":[{\"text\":\"a\"},{\"text\":\"b\"}]}]}",
				base + ",\"questions\":[{\"type\":\"single\",\"text\":\"q\",\"points\":1,\"options\":[{\"text\":\"a\",\"correct\":true}]}]}",
				base + ",\"questions\":[{\"type\":\"short\",\"text\":\"q\",\"points\":1,\"options\":[{\"text\":\"a\"},{\"text\":\"b\"}]}]}",
				base + ",\"questions\":[{\"type\":\"short\",\"text\":\"q\",\"points\":0}]}",
				base + ",\"questions\":[{\"type\":\"short\",\"text\":\"q\",\"points\":1.005}]}",
				base + ",\"questions\":[{\"type\":\"short\",\"text\":\" \",\"points\":1}]}",
				base + ",\"questions\":[{\"type\":\"short\",\"text\":\"q\",\"points\":1,\"correct\":true}]}",
				base + ",\"questions\":[]}",
				base + ",\"questions\":" + QUESTIONS + ",\"maxScore\":10}",
				base + ",\"questions\":" + QUESTIONS + ",\"timeLimitMinutes\":0}",
				base + ",\"questions\":" + QUESTIONS + ",\"attemptLimit\":0}",
				base + ",\"questions\":" + QUESTIONS + ",\"openAt\":\"2999-01-01T00:00:00\"}",
				base + ",\"questions\":" + QUESTIONS + ",\"examType\":\"paper\"}",
				base + ",\"examType\":\"offline\",\"maxScore\":10,\"questions\":" + QUESTIONS + "}",
				base + ",\"examType\":\"offline\"}",
				base + ",\"examType\":\"offline\",\"maxScore\":10,\"timeLimitMinutes\":30}",
				"{\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\",\"questions\":" + QUESTIONS + "}");
		for (String bad : invalid) {
			this.professor.postJson(quizzes(this.course), bad).andExpect(status().isBadRequest());
		}
		// 시험형태는 등록한 뒤 바꾸지 않는다. 수정 본문에는 그 필드가 없다.
		this.professor.putJson(quiz(this.course, id), base + ",\"examType\":\"offline\",\"revision\":1}")
			.andExpect(status().isBadRequest());
		this.professor.putJson(quiz(this.course, id), base + "}").andExpect(status().isBadRequest());
		assertThat(ApiClient.json(this.professor.get(quizzes(this.course)))).hasSize(1);
		assertThat(revision(id)).isEqualTo(1);
	}

	@Test
	void authoringEndpointsFollowTheMembershipMatrix() throws Exception {
		long id = quiz("");
		String create = "{\"weekNo\":1,\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\",\"questions\":" + QUESTIONS
				+ "}";
		String edit = "{\"weekNo\":1,\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\",\"revision\":1}";
		String questions = "{\"revision\":1,\"questions\":[{\"type\":\"short\",\"text\":\"q\",\"points\":1}]}";
		// 같은 사람들이 담당자와 수강생인 다른 과목의 경로로는 이 퀴즈가 없다.
		long other = this.data.course();
		long prof = this.jdbc.sql("SELECT user_id FROM course_instructors WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
		this.data.instructor(other, prof);
		this.data.enroll(other, this.studentId);

		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.postJson(quizzes(this.course), create).andExpect(status().isNotFound());
			denied.putJson(quiz(this.course, id), edit).andExpect(status().isNotFound());
			denied.putJson(quiz(this.course, id) + "/questions", questions).andExpect(status().isNotFound());
		}
		this.outsider.get(quizzes(this.course)).andExpect(status().isNotFound());
		this.outsider.get(quiz(this.course, id)).andExpect(status().isNotFound());
		this.professor.get(quiz(other, id)).andExpect(status().isNotFound());
		this.student.get(quiz(other, id)).andExpect(status().isNotFound());
		this.professor.putJson(quiz(other, id), edit).andExpect(status().isNotFound());
		this.professor.putJson(quiz(other, id) + "/questions", questions).andExpect(status().isNotFound());
		this.professor.get(quizzes(other)).andExpect(jsonPath("$.length()").value(0));

		this.anonymous.get(quizzes(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.get(quiz(this.course, id)).andExpect(status().isUnauthorized());
		this.anonymous.postJson(quizzes(this.course), create).andExpect(status().isUnauthorized());
		this.anonymous.putJson(quiz(this.course, id), edit).andExpect(status().isUnauthorized());
		this.professor.postWithoutCsrf(quizzes(this.course), create).andExpect(status().isForbidden());
		this.professor.putWithoutCsrf(quiz(this.course, id), edit).andExpect(status().isForbidden());
		this.professor.putWithoutCsrf(quiz(this.course, id) + "/questions", questions).andExpect(status().isForbidden());

		assertThat(ApiClient.json(this.professor.get(quizzes(this.course)))).hasSize(1);
		assertThat(revision(id)).isEqualTo(1);
		this.professor.get(quiz(this.course, id)).andExpect(jsonPath("$.quiz.maxScore").value(10.0));
	}

}
