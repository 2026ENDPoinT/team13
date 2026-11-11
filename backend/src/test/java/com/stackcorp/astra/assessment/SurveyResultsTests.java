package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 결과 집계의 공개 판정(EP-0097, ASSESS-07, ASSESS-08, N-09). 익명 설문은 마감 뒤 응답자가 최소 공개 인원(5명) 이상일 때만,
 * 답한 사람이 5명 미만인 문항은 가려서 내보낸다. 학생은 설문이 결과를 공개했고 마감한 뒤에만 본다. 어떤 결과에도 응답자가 없다.
 */
class SurveyResultsTests extends SurveyTestSupport {

	@Test
	void anonymousAggregatesNeedAClosedWindowAndAtLeastFiveRespondents() throws Exception {
		long id = survey("");
		Form form = form(id);
		List<Client> respondents = new ArrayList<>(List.of(this.student, this.classmate));
		for (int i = 0; i < 3; i++) {
			respondents.add(enrolledStudent());
		}
		// 넷이 응답한다. 둘만 서술형에 답한다.
		for (int i = 0; i < 4; i++) {
			respondents.get(i)
				.submit(completion(this.course, id), "r", form.answers(i % 2, i, i < 2 ? "의견 " + (char) ('d' - i) : null))
				.andExpect(status().isCreated());
		}
		// 기간 중: 결과가 바뀌는 동안은 내보내지 않는다.
		this.professor.get(results(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.disclosed").value(false))
			.andExpect(jsonPath("$.withheldReason").value("not_closed"))
			.andExpect(jsonPath("$.respondentCount").value(4))
			.andExpect(jsonPath("$.questions.length()").value(0));
		closeSurvey(id);
		this.professor.get(results(this.course, id))
			.andExpect(jsonPath("$.disclosed").value(false))
			.andExpect(jsonPath("$.withheldReason").value("below_threshold"))
			.andExpect(jsonPath("$.minRespondents").value(5));
		this.professor.get(results(this.course, id) + "/questions/" + form.q(3) + "/texts")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("results_withheld"));

		// 다섯째가 응답하고 마감하면 집계가 나온다. 서술형은 답한 사람이 둘이라 가린다.
		scheduleSurvey(id, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 HOUR");
		respondents.get(4).submit(completion(this.course, id), "r", form.answers(2, 4, null)).andExpect(status().isCreated());
		closeSurvey(id);
		String body = this.professor.get(results(this.course, id))
			.andExpect(jsonPath("$.disclosed").value(true))
			.andExpect(jsonPath("$.respondentCount").value(5))
			.andExpect(jsonPath("$.questions[0].answerCount").value(5))
			.andExpect(jsonPath("$.questions[0].options[0].count").value(2))
			.andExpect(jsonPath("$.questions[0].options[1].count").value(2))
			.andExpect(jsonPath("$.questions[0].options[2].count").value(1))
			.andExpect(jsonPath("$.questions[1].options[0].count").value(5))
			.andExpect(jsonPath("$.questions[1].options[2].count").value(0))
			.andExpect(jsonPath("$.questions[2].scaleMean").value(3.0))
			.andExpect(jsonPath("$.questions[3].answerCount").value(2))
			.andExpect(jsonPath("$.questions[3].suppressed").value(true))
			.andExpect(jsonPath("$.questions[3].options").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("테스트").doesNotContain("userId").doesNotContain("respondentId");
		this.professor.get(results(this.course, id) + "/questions/" + form.q(3) + "/texts")
			.andExpect(status().isConflict());
	}

	@Test
	void freeTextIsListedInTextOrderNotInSubmissionOrder() throws Exception {
		long id = survey("");
		Form form = form(id);
		List<String> texts = List.of("다", "가", "마", "나", "라");
		List<Client> respondents = new ArrayList<>(List.of(this.student, this.classmate));
		for (int i = 0; i < 3; i++) {
			respondents.add(enrolledStudent());
		}
		for (int i = 0; i < 5; i++) {
			respondents.get(i).submit(completion(this.course, id), "t", form.answers(0, 0, texts.get(i)))
				.andExpect(status().isCreated());
		}
		closeSurvey(id);
		String path = results(this.course, id) + "/questions/" + form.q(3) + "/texts";
		this.professor.get(path + "?size=3")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.items[0]").value("가"))
			.andExpect(jsonPath("$.items[1]").value("나"))
			.andExpect(jsonPath("$.items[2]").value("다"));
		this.professor.get(path + "?size=3&page=2")
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[1]").value("마"));
		this.professor.get(path + "?size=101").andExpect(status().isBadRequest());
		this.professor.get(path + "?page=0").andExpect(status().isBadRequest());
		// 보기 문항에는 문구 목록이 없다.
		this.professor.get(results(this.course, id) + "/questions/" + form.q(0) + "/texts")
			.andExpect(status().isNotFound());
	}

	@Test
	void studentsSeeResultsOnlyWhenTheSurveyReleasesThemAfterClosing() throws Exception {
		long hidden = survey("");
		long released = survey("\"resultsVisible\":true");
		long later = survey("\"resultsVisible\":true,\"resultsReleaseAt\":\"2998-01-01T00:00:00\"");
		List<Client> respondents = new ArrayList<>(List.of(this.student, this.classmate));
		for (int i = 0; i < 3; i++) {
			respondents.add(enrolledStudent());
		}
		for (long id : List.of(hidden, released, later)) {
			Form form = form(id);
			for (Client c : respondents) {
				c.submit(completion(this.course, id), "s", form.answers(0, 0, null)).andExpect(status().isCreated());
			}
		}
		// 기간 중에는 공개 설문도 학생에게 결과가 없다.
		this.student.get(results(this.course, released)).andExpect(status().isNotFound());
		this.student.get(survey(this.course, released)).andExpect(jsonPath("$.resultsAvailable").value(false));
		for (long id : List.of(hidden, released, later)) {
			closeSurvey(id);
		}
		this.student.get(results(this.course, hidden)).andExpect(status().isNotFound());
		this.student.get(results(this.course, later)).andExpect(status().isNotFound());
		this.student.get(survey(this.course, released)).andExpect(jsonPath("$.resultsAvailable").value(true));
		this.student.get(results(this.course, released))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.disclosed").value(true))
			.andExpect(jsonPath("$.questions[0].options[0].count").value(5));
		this.outsider.get(results(this.course, released)).andExpect(status().isNotFound());
	}

	@Test
	void namedSurveysShowInstructorsLiveAggregatesButStudentsStillNeedTheThreshold() throws Exception {
		long id = survey("\"anonymous\":false,\"resultsVisible\":true");
		Form form = form(id);
		this.student.submit(completion(this.course, id), "n", form.answers(2, 1, "실명 의견")).andExpect(status().isCreated());
		String body = this.professor.get(results(this.course, id))
			.andExpect(jsonPath("$.disclosed").value(true))
			.andExpect(jsonPath("$.anonymous").value(false))
			.andExpect(jsonPath("$.questions[0].options[2].count").value(1))
			.andExpect(jsonPath("$.questions[2].scaleMean").value(2.0))
			.andExpect(jsonPath("$.questions[3].suppressed").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("테스트").doesNotContain("userId").doesNotContain("respondentId");
		this.professor.get(results(this.course, id) + "/questions/" + form.q(3) + "/texts")
			.andExpect(jsonPath("$.items[0]").value("실명 의견"));
		closeSurvey(id);
		this.classmate.get(results(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.disclosed").value(false))
			.andExpect(jsonPath("$.withheldReason").value("below_threshold"));
	}

}
