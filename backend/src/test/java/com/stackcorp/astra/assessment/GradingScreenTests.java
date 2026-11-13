package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * 담당자 채점 화면 한 장과 응시 시작 응답. 담당자의 응시 조회는 문항마다 출제 순서의 보기, 정답 여부, 학생이 고른 보기를 담는다. 학생
 * 응답에는 이 모양이 없다. 응시 시작은 상태 코드와 같은 구분(created)을 본문에도 담는다.
 */
class GradingScreenTests extends QuizTestSupport {

	@Test
	void theInstructorAttemptCarriesOptionTextsCorrectnessAndTheStudentsChoice() throws Exception {
		long id = quiz("");
		long a = start(this.student, id);
		this.student.putJson(answer(this.course, id, a, questionId(id, 0)), choice(0, optionId(id, 0, 1)))
			.andExpect(status().isOk());
		this.student.putJson(answer(this.course, id, a, questionId(id, 1)),
				choice(0, optionId(id, 1, 0), optionId(id, 1, 2)))
			.andExpect(status().isOk());
		this.student.submit(submitPath(this.course, id, a), "screen", "{}").andExpect(status().isOk());

		this.professor.get(attempt(this.course, id, a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.answers[0].text").value("1+1은?"))
			.andExpect(jsonPath("$.answers[0].points").value(2.0))
			.andExpect(jsonPath("$.answers[0].optionIds[0]").value(optionId(id, 0, 1)))
			.andExpect(jsonPath("$.answers[0].options.length()").value(2))
			.andExpect(jsonPath("$.answers[0].options[0].number").value(1))
			.andExpect(jsonPath("$.answers[0].options[0].text").value("2"))
			.andExpect(jsonPath("$.answers[0].options[0].correct").value(true))
			.andExpect(jsonPath("$.answers[0].options[0].selected").value(false))
			.andExpect(jsonPath("$.answers[0].options[1].text").value("3"))
			.andExpect(jsonPath("$.answers[0].options[1].correct").value(false))
			.andExpect(jsonPath("$.answers[0].options[1].selected").value(true))
			.andExpect(jsonPath("$.answers[0].correct").value(false))
			.andExpect(jsonPath("$.answers[1].options.length()").value(3))
			.andExpect(jsonPath("$.answers[1].options[0].selected").value(true))
			.andExpect(jsonPath("$.answers[1].options[1].selected").value(false))
			.andExpect(jsonPath("$.answers[1].options[2].selected").value(true))
			.andExpect(jsonPath("$.answers[1].correct").value(true))
			.andExpect(jsonPath("$.answers[2].options.length()").value(0));
		// 학생 응답에는 정답 표시도, 채점 화면의 보기 모양도 없다.
		String own = this.student.get(attempt(this.course, id, a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.questions[0].options[0].correct").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(own).doesNotContain("\"selected\"").doesNotContain("\"correct\"");
		this.classmate.get(attempt(this.course, id, a)).andExpect(status().isNotFound());
	}

	@Test
	void startSaysInTheBodyWhetherItCreatedTheAttempt() throws Exception {
		long id = quiz("");
		long a = start(this.student, id);
		this.student.post(attempts(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(a))
			.andExpect(jsonPath("$.created").value(false));
		this.classmate.post(attempts(this.course, id))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.created").value(true))
			.andExpect(jsonPath("$.attemptNo").value(1));
	}

}
