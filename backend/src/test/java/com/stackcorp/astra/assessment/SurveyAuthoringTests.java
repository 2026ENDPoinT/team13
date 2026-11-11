package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 담당자의 강의실 설문 등록·수정과 과목 구성원의 조회. 문항 순서와 보기, 학생에게 보이는 범위, 수정 충돌과 응답 뒤 문항 고정,
 * 권한 표(담당자, 수강생, 과목 밖, 다른 과목 경로, 로그인 안 함, CSRF 없음)를 본다(SC-02, SC-03, SC-04).
 */
class SurveyAuthoringTests extends SurveyTestSupport {

	@Test
	void instructorDefinesOrderedQuestionsAndStudentsSeeOnlyWhatIsOpen() throws Exception {
		JsonNode created = createSurvey(this.course, this.professor, "\"anonymous\":true,\"resultsVisible\":true");
		long id = created.get("survey").get("surveyId").asLong();
		assertThat(created.get("revision").asLong()).isEqualTo(1);
		assertThat(created.get("phase").asString()).isEqualTo("open");
		assertThat(created.get("questions")).hasSize(4);

		this.professor.get(survey(this.course, id))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.survey.title").value("중간 강의평가"))
			.andExpect(jsonPath("$.survey.anonymous").value(true))
			.andExpect(jsonPath("$.survey.questionCount").value(4))
			.andExpect(jsonPath("$.questions[0].questionNo").value(1))
			.andExpect(jsonPath("$.questions[0].type").value("single"))
			.andExpect(jsonPath("$.questions[0].options[2].text").value("어려움"))
			.andExpect(jsonPath("$.questions[0].options[2].optionNo").value(3))
			.andExpect(jsonPath("$.questions[1].type").value("multiple"))
			.andExpect(jsonPath("$.questions[1].maxSelect").value(2))
			.andExpect(jsonPath("$.questions[2].type").value("scale"))
			.andExpect(jsonPath("$.questions[2].options.length()").value(5))
			.andExpect(jsonPath("$.questions[3].type").value("essay"))
			.andExpect(jsonPath("$.questions[3].required").value(false))
			.andExpect(jsonPath("$.respondentCount").value(0));

		// 학생은 응답 기간 안이면 문항을 받는다. 수정 번호는 받지 않는다.
		this.student.get(survey(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("not_started"))
			.andExpect(jsonPath("$.phase").value("open"))
			.andExpect(jsonPath("$.resultsAvailable").value(false))
			.andExpect(jsonPath("$.questions.length()").value(4))
			.andExpect(jsonPath("$.revision").doesNotExist());

		// 공개하지 않은 설문과 시작 전 설문은 학생 목록과 상세에 없다.
		long hidden = survey("\"published\":false");
		long future = survey("\"openAt\":\"2998-01-01T00:00:00\"");
		this.student.get(surveys(this.course))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].surveyId").value(id))
			.andExpect(jsonPath("$[0].state").value("not_started"))
			.andExpect(jsonPath("$[0].questionCount").value(4))
			.andExpect(jsonPath("$[0].respondentCount").doesNotExist());
		this.student.get(survey(this.course, hidden)).andExpect(status().isNotFound());
		this.student.get(survey(this.course, future)).andExpect(status().isNotFound());
		this.professor.get(surveys(this.course))
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].respondentCount").value(0))
			.andExpect(jsonPath("$[0].state").doesNotExist());

		// 마감한 설문은 읽기 전용 상태만 준다(N-06). 문항이 없다.
		closeSurvey(id);
		this.student.get(survey(this.course, id))
			.andExpect(jsonPath("$.phase").value("closed"))
			.andExpect(jsonPath("$.questions").doesNotExist());

		// 설문은 survey 학습활동이다. 할 일과 강의실 목록은 활동으로 이어진다.
		assertThat(this.jdbc.sql("SELECT activity_type FROM learning_activities WHERE activity_id = :a")
			.param("a", activityOfSurvey(id))
			.query(String.class)
			.single()).isEqualTo("survey");
	}

	@Test
	void invalidDefinitionsAreRejectedWithoutStoringAnything() throws Exception {
		long before = surveyRows();
		String[] questionSets = { "[]", "[{\"type\":\"ranking\",\"text\":\"q\",\"options\":[\"a\",\"b\"]}]",
				"[{\"type\":\"single\",\"text\":\"q\",\"options\":[\"a\"]}]",
				"[{\"type\":\"single\",\"text\":\"q\",\"options\":[\"a\",\"b\"],\"maxSelect\":1}]",
				"[{\"type\":\"multiple\",\"text\":\"q\",\"minSelect\":3,\"maxSelect\":2,\"options\":[\"a\",\"b\",\"c\"]}]",
				"[{\"type\":\"multiple\",\"text\":\"q\",\"maxSelect\":4,\"options\":[\"a\",\"b\",\"c\"]}]",
				"[{\"type\":\"essay\",\"text\":\"q\",\"options\":[\"a\",\"b\"]}]",
				"[{\"type\":\"scale\",\"text\":\"q\",\"options\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"11\",\"12\"]}]",
				"[{\"type\":\"single\",\"text\":\"\",\"options\":[\"a\",\"b\"]}]",
				"[{\"type\":\"single\",\"text\":\"q\",\"options\":[\"a\",\"b\\u0007\"]}]" };
		for (String questions : questionSets) {
			this.professor.postJson(surveys(this.course), """
					{"weekNo":1,"title":"t","closeAt":"2999-01-01T00:00:00","questions":%s}
					""".formatted(questions)).andExpect(status().isBadRequest());
		}
		this.professor.postJson(surveys(this.course), "{\"weekNo\":1,\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\"}")
			.andExpect(status().isBadRequest());
		this.professor.postJson(surveys(this.course), """
				{"weekNo":1,"title":"t","openAt":"2999-01-02T00:00:00","closeAt":"2999-01-01T00:00:00","questions":%s}
				""".formatted(QUESTIONS)).andExpect(status().isBadRequest());
		this.professor.postJson(surveys(this.course), """
				{"weekNo":1,"title":"t","closeAt":"2999-01-01T00:00:00","resultsReleaseAt":"2999-02-01T00:00:00","questions":%s}
				""".formatted(QUESTIONS)).andExpect(status().isBadRequest());
		// 문서에 없는 필드(작성자 지정)는 400이다(SC-02).
		this.professor.postJson(surveys(this.course), """
				{"weekNo":1,"title":"t","closeAt":"2999-01-01T00:00:00","createdBy":1,"questions":%s}
				""".formatted(QUESTIONS)).andExpect(status().isBadRequest());
		assertThat(surveyRows()).isEqualTo(before);
	}

	@Test
	void editsNeedTheReadRevisionAndQuestionsFreezeOnceSomeoneCompleted() throws Exception {
		long id = survey("");
		String edit = """
				{"weekNo":2,"title":"바뀐 제목","closeAt":"2999-02-01T00:00:00","revision":%d,
				 "questions":[{"type":"single","text":"하나만","options":["예","아니오"]}]}
				""";
		this.professor.putJson(survey(this.course, id), edit.formatted(1))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2))
			.andExpect(jsonPath("$.survey.title").value("바뀐 제목"))
			.andExpect(jsonPath("$.questions.length()").value(1));
		// 옛 revision으로 다시 보내면 덮어쓰지 않는다.
		this.professor.putJson(survey(this.course, id), edit.formatted(1))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.putJson(survey(this.course, id), "{\"weekNo\":2,\"title\":\"x\",\"closeAt\":\"2999-02-01T00:00:00\"}")
			.andExpect(status().isBadRequest());

		long answered = survey("");
		Form form = form(answered);
		this.student.submit(completion(this.course, answered), "done-1", form.answers(0, 0, null))
			.andExpect(status().isCreated());
		String replaceQuestions = """
				{"weekNo":2,"title":"t","closeAt":"2999-02-01T00:00:00","revision":1,
				 "questions":[{"type":"essay","text":"새 문항"}]}
				""";
		this.professor.putJson(survey(this.course, answered), replaceQuestions)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("survey_has_responses"));
		this.professor.putJson(survey(this.course, answered),
				"{\"weekNo\":2,\"title\":\"t\",\"closeAt\":\"2999-02-01T00:00:00\",\"revision\":1,\"anonymous\":false}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("survey_has_responses"));
		// 거부한 수정은 아무것도 바꾸지 않았다. 문항을 보내지 않으면 제목과 기간은 바꾼다.
		this.professor.putJson(survey(this.course, answered),
				"{\"weekNo\":2,\"title\":\"마감 연장\",\"closeAt\":\"2999-03-01T00:00:00\",\"revision\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2))
			.andExpect(jsonPath("$.survey.anonymous").value(true))
			.andExpect(jsonPath("$.questions.length()").value(4))
			.andExpect(jsonPath("$.questions[0].questionId").value(form.q(0)))
			.andExpect(jsonPath("$.respondentCount").value(1));
	}

	@Test
	void onlyTheCourseInstructorManagesAndOnlyMembersSeeSurveys() throws Exception {
		long id = survey("");
		String edit = "{\"weekNo\":2,\"title\":\"t\",\"closeAt\":\"2999-02-01T00:00:00\",\"revision\":1}";
		String create = "{\"weekNo\":1,\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\",\"questions\":" + QUESTIONS + "}";

		// 수강생: 만들거나 고치지 못하고 담당자 정보(revision)를 받지 못한다.
		this.student.postJson(surveys(this.course), create).andExpect(status().isNotFound());
		this.student.putJson(survey(this.course, id), edit).andExpect(status().isNotFound());
		// 과목 밖 사용자: 목록, 상세, 결과 모두 404.
		this.outsider.get(surveys(this.course)).andExpect(status().isNotFound());
		this.outsider.get(survey(this.course, id)).andExpect(status().isNotFound());
		this.outsider.get(results(this.course, id)).andExpect(status().isNotFound());
		this.outsider.postJson(surveys(this.course), create).andExpect(status().isNotFound());

		// 다른 과목 경로: 그 과목의 담당자라도 이 설문은 없다(SC-03).
		long otherCourse = this.data.course();
		long otherProf = this.data.user("PROFESSOR");
		this.data.instructor(otherCourse, otherProf);
		this.data.enroll(otherCourse, this.studentId);
		Client other = client(otherProf);
		other.get(survey(otherCourse, id)).andExpect(status().isNotFound());
		other.putJson(survey(otherCourse, id), edit).andExpect(status().isNotFound());
		other.get(results(otherCourse, id)).andExpect(status().isNotFound());
		this.student.get(survey(otherCourse, id)).andExpect(status().isNotFound());

		// 로그인하지 않은 요청은 401, CSRF token이 없는 변경은 403이다.
		this.anonymous.get(surveys(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.postJson(surveys(this.course), create).andExpect(status().isUnauthorized());
		this.professor.postWithoutCsrf(surveys(this.course), create).andExpect(status().isForbidden());
		this.professor.putWithoutCsrf(survey(this.course, id), edit).andExpect(status().isForbidden());

		// 같은 Idempotency-Key로 다시 만들면 처음 설문을 돌려준다.
		var request = MockMvcRequestBuilders.post(surveys(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(create)
			.header("Idempotency-Key", "create-1");
		long first = ApiClient.json(this.professor.send(request).andExpect(status().isCreated()))
			.get("survey")
			.get("surveyId")
			.asLong();
		long again = ApiClient.json(this.professor.send(MockMvcRequestBuilders.post(surveys(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(create)
			.header("Idempotency-Key", "create-1")).andExpect(status().isCreated())).get("survey").get("surveyId").asLong();
		assertThat(again).isEqualTo(first);
	}

	private long surveyRows() {
		return this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
	}

}
