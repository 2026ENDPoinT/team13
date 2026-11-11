package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 학생의 설문 완료. 답 전체를 서버의 문항 정의로 검사하고 한 transaction으로 참여와 응답 세트를 쓴다. 일부만 답한 세트는 남지
 * 않고, 같은 사람의 완료는 key와 상관없이 한 번뿐이다. 기간은 DB 시계로 정한다(SC-05, SC-05-A, SC-06, CHG-04).
 */
class SurveyCompletionTests extends SurveyTestSupport {

	@Test
	void aCompleteValidatedAnswerSetCompletesTheSurveyOnce() throws Exception {
		long id = survey("");
		Form form = form(id);
		this.student.submit(completion(this.course, id), "k-1", form.answers(1, 4, "좋았습니다"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.surveyId").value(id))
			.andExpect(jsonPath("$.state").value("completed"))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.completedAt").doesNotExist());
		assertThat(participations(id)).isEqualTo(1);
		assertThat(responses(id)).isEqualTo(1);
		assertThat(answers(id)).isEqualTo(4);
		assertThat(chosenOptions(id)).isEqualTo(4);

		this.student.get(survey(this.course, id))
			.andExpect(jsonPath("$.state").value("completed"))
			.andExpect(jsonPath("$.questions").doesNotExist());
		this.student.get(surveys(this.course)).andExpect(jsonPath("$[0].state").value("completed"));
		this.classmate.get(surveys(this.course)).andExpect(jsonPath("$[0].state").value("not_started"));
		this.professor.get(survey(this.course, id)).andExpect(jsonPath("$.respondentCount").value(1));

		// 선택 문항은 비워도 된다.
		this.classmate.submit(completion(this.course, id), "k-2", form.answers(0, 0, null)).andExpect(status().isCreated());
		assertThat(answers(id)).isEqualTo(7);
	}

	@Test
	void invalidAnswerSetsAreRejectedAndStoreNothing() throws Exception {
		long id = survey("");
		long otherSurvey = survey("");
		Form form = form(id);
		Form other = form(otherSurvey);
		String single = Form.answer(form.q(0), List.of(form.o(0, 0)), null);
		String multiple = Form.answer(form.q(1), List.of(form.o(1, 0)), null);
		String scale = Form.answer(form.q(2), List.of(form.o(2, 2)), null);
		List<String> invalid = List.of(
				// 필수 문항(척도)을 빼먹었다.
				"{\"answers\":[" + single + "," + multiple + "]}",
				// 다른 문항의 보기를 골랐다.
				"{\"answers\":[" + Form.answer(form.q(0), List.of(form.o(1, 0)), null) + "," + multiple + "," + scale + "]}",
				// 다른 설문의 보기를 골랐다.
				"{\"answers\":[" + Form.answer(form.q(0), List.of(other.o(0, 0)), null) + "," + multiple + "," + scale + "]}",
				// 다른 설문의 문항에 답했다.
				"{\"answers\":[" + single + "," + multiple + "," + scale + ","
						+ Form.answer(other.q(3), List.of(), "남의 문항") + "]}",
				// 단일 선택에 보기 둘.
				"{\"answers\":[" + Form.answer(form.q(0), List.of(form.o(0, 0), form.o(0, 1)), null) + "," + multiple + ","
						+ scale + "]}",
				// 복수 선택의 최대(2개)를 넘었다. hidden ans_max 변조와 같다(N-14).
				"{\"answers\":[" + single + ","
						+ Form.answer(form.q(1), List.of(form.o(1, 0), form.o(1, 1), form.o(1, 2)), null) + "," + scale + "]}",
				// 같은 문항을 두 번 답했다.
				"{\"answers\":[" + single + "," + single + "," + multiple + "," + scale + "]}",
				// 보기 문항에 글, 서술형에 보기.
				"{\"answers\":[" + Form.answer(form.q(0), List.of(form.o(0, 0)), "글") + "," + multiple + "," + scale + "]}",
				"{\"answers\":[" + single + "," + multiple + "," + scale + ","
						+ Form.answer(form.q(3), List.of(form.o(0, 0)), "글") + "]}",
				// 빈 서술형, 제어 문자, 2000자 초과.
				"{\"answers\":[" + single + "," + multiple + "," + scale + "," + Form.answer(form.q(3), List.of(), " ") + "]}",
				"{\"answers\":[" + single + "," + multiple + "," + scale + ","
						+ Form.answer(form.q(3), List.of(), "a\\u0000b") + "]}",
				"{\"answers\":[" + single + "," + multiple + "," + scale + ","
						+ Form.answer(form.q(3), List.of(), "가".repeat(2001)) + "]}",
				// 응답자를 본문으로 지정하려는 필드(SC-02).
				"{\"userId\":" + this.classmateId + ",\"answers\":[" + single + "," + multiple + "," + scale + "]}",
				"{}");
		for (int i = 0; i < invalid.size(); i++) {
			this.student.submit(completion(this.course, id), "bad-" + i, invalid.get(i)).andExpect(status().isBadRequest());
		}
		this.student.submit(completion(this.course, id), null, form.answers(0, 0, null)).andExpect(status().isBadRequest());
		this.student.submit(completion(this.course, id), "not a key!", form.answers(0, 0, null))
			.andExpect(status().isBadRequest());
		assertThat(participations(id) + responses(id) + answers(id) + chosenOptions(id)).isZero();

		// 실패한 key로 고쳐 다시 보내면 받는다. 구분자 문자열은 다른 문항으로 밀리지 않는다(N-13).
		this.student.submit(completion(this.course, id), "bad-0", form.answers(0, 0, "a|$b^c")).andExpect(status().isCreated());
		assertThat(this.jdbc.sql("""
				SELECT a.answer_text FROM survey_answers a JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :s AND a.survey_question_id = :q
				""").param("s", id).param("q", form.q(3)).query(String.class).single()).isEqualTo("a|$b^c");
	}

	@Test
	void theResponseWindowFollowsTheDbClock() throws Exception {
		// openAt = now: 열려 있다(openAt <= now).
		long atOpen = survey("");
		scheduleSurvey(atOpen, "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL 1 HOUR");
		this.student.submit(completion(this.course, atOpen), "open", form(atOpen).answers(0, 0, null))
			.andExpect(status().isCreated());

		// openAt 전: 학생에게 설문이 없다.
		long beforeOpen = survey("");
		Form early = form(beforeOpen);
		scheduleSurvey(beforeOpen, "CURRENT_TIMESTAMP + INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 2 HOUR");
		this.student.get(survey(this.course, beforeOpen)).andExpect(status().isNotFound());
		this.student.submit(completion(this.course, beforeOpen), "before", early.answers(0, 0, null))
			.andExpect(status().isNotFound());

		// closeAt = now: 닫혀 있다(now < closeAt가 아니다). 마감된 폼을 직접 보내도 받지 않는다(ASSESS-02).
		long atClose = survey("");
		Form late = form(atClose);
		scheduleSurvey(atClose, "NULL", "CURRENT_TIMESTAMP");
		this.student.submit(completion(this.course, atClose), "close", late.answers(0, 0, null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("deadline_passed"));
		this.student.get(survey(this.course, atClose)).andExpect(jsonPath("$.phase").value("closed"));

		// 공개를 끄면 학생에게 없다.
		long unpublished = survey("\"published\":false");
		this.student.submit(completion(this.course, unpublished), "hidden", form(unpublished).answers(0, 0, null))
			.andExpect(status().isNotFound());
		assertThat(participations(beforeOpen) + participations(atClose) + participations(unpublished)).isZero();
		assertThat(responses(beforeOpen) + responses(atClose) + responses(unpublished)).isZero();
	}

	@Test
	void anonymousRetriesAndRacesYieldOneParticipationAndOneAnswerSet() throws Exception {
		long id = survey("");
		Form form = form(id);
		String body = form.answers(0, 0, "처음 답");
		this.student.submit(completion(this.course, id), "anon-key", body).andExpect(status().isCreated());
		// SC-05-A: 같은 key는 본문이 달라도 처음 결과를 돌려주고 답을 바꾸지 않는다. 마감 뒤에도 같다.
		closeSurvey(id);
		this.student.submit(completion(this.course, id), "anon-key", form.answers(2, 4, "바꾼 답"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.state").value("completed"))
			.andExpect(jsonPath("$.revision").value(1));
		scheduleSurvey(id, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 HOUR");
		// 다른 key는 두 번째 응답이 아니다.
		this.student.submit(completion(this.course, id), "anon-key-2", body)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_completed"));
		assertThat(participations(id)).isEqualTo(1);
		assertThat(responses(id)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT answer_text FROM survey_answers WHERE survey_question_id = :q")
			.param("q", form.q(3))
			.query(String.class)
			.list()).containsExactly("처음 답");

		long sameKey = survey("");
		String sameBody = form(sameKey).answers(0, 0, null);
		List<MockHttpServletResponse> replays = race(6,
				(i) -> this.student.submit(completion(this.course, sameKey), "same", sameBody).andReturn().getResponse());
		assertThat(replays).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		assertThat(participations(sameKey)).isEqualTo(1);
		assertThat(responses(sameKey)).isEqualTo(1);

		long differentKeys = survey("");
		String tabBody = form(differentKeys).answers(0, 0, null);
		List<MockHttpServletResponse> tabs = race(6, (i) -> this.student
			.submit(completion(this.course, differentKeys), "tab-" + i, tabBody)
			.andReturn()
			.getResponse());
		assertThat(tabs).extracting(MockHttpServletResponse::getStatus).containsOnly(201, 409);
		assertThat(tabs).filteredOn((r) -> r.getStatus() == 201).hasSize(1);
		assertThat(participations(differentKeys)).isEqualTo(1);
		assertThat(responses(differentKeys)).isEqualTo(1);
		assertThat(answers(differentKeys)).isEqualTo(3);
	}

	@Test
	void namedRetriesUseTheSharedLedgerAndRacesYieldOneAnswerSet() throws Exception {
		long id = survey("\"anonymous\":false");
		Form form = form(id);
		String body = form.answers(0, 0, null);
		String receipt = ApiClient.json(this.student.submit(completion(this.course, id), "named", body)
			.andExpect(status().isCreated())).toString();
		assertThat(ApiClient.json(this.student.submit(completion(this.course, id), "named", body)
			.andExpect(status().isCreated())).toString()).isEqualTo(receipt);
		// 실명 설문은 일반 멱등 규칙(SC-05)이다. 같은 key에 다른 답은 충돌이다.
		this.student.submit(completion(this.course, id), "named", form.answers(1, 0, null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		this.student.submit(completion(this.course, id), "named-2", body)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_completed"));
		assertThat(participations(id)).isEqualTo(1);
		assertThat(responses(id)).isEqualTo(1);

		long differentKeys = survey("\"anonymous\":false");
		String tabBody = form(differentKeys).answers(0, 0, null);
		List<MockHttpServletResponse> tabs = race(6, (i) -> this.student
			.submit(completion(this.course, differentKeys), "named-tab-" + i, tabBody)
			.andReturn()
			.getResponse());
		assertThat(tabs).extracting(MockHttpServletResponse::getStatus).containsOnly(201, 409);
		assertThat(tabs).filteredOn((r) -> r.getStatus() == 201).hasSize(1);
		assertThat(participations(differentKeys)).isEqualTo(1);
		assertThat(responses(differentKeys)).isEqualTo(1);
	}

	@Test
	void manyStudentsCompletingTheSameSurveyAtOnceAreAllAccepted() throws Exception {
		for (String mode : List.of("true", "false")) {
			long id = survey("\"anonymous\":" + mode);
			String body = form(id).answers(0, 0, "동시 응답");
			List<Client> students = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				students.add(enrolledStudent());
			}
			List<MockHttpServletResponse> results = race(8, (i) -> students.get(i)
				.submit(completion(this.course, id), "k", body)
				.andReturn()
				.getResponse());
			assertThat(results).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
			assertThat(participations(id)).isEqualTo(8);
			assertThat(responses(id)).isEqualTo(8);
			assertThat(answers(id)).isEqualTo(32);
		}
	}

	@Test
	void onlyEnrolledStudentsOfThatCourseCompleteIt() throws Exception {
		long id = survey("");
		String body = form(id).answers(0, 0, null);
		this.professor.submit(completion(this.course, id), "prof", body).andExpect(status().isNotFound());
		this.outsider.submit(completion(this.course, id), "out", body).andExpect(status().isNotFound());
		this.anonymous.submit(completion(this.course, id), "anon", body).andExpect(status().isUnauthorized());
		this.student.postWithoutCsrf(completion(this.course, id), body).andExpect(status().isForbidden());
		// 다른 과목 경로: 그 과목의 수강생이라도 이 설문은 없다(SC-03).
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.studentId);
		this.student.submit(completion(otherCourse, id), "other", body).andExpect(status().isNotFound());
		this.student.submit(completion(this.course, id + 1_000_000), "missing", body).andExpect(status().isNotFound());
		assertThat(participations(id) + responses(id)).isZero();
	}

}
