package com.stackcorp.astra.summary.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import tools.jackson.databind.JsonNode;

/**
 * 교수자의 외부 LLM 처리 동의와 쪽별 요약 생성의 문(ADR-0032). 동의는 지금 배포의 profile을 본 그대로여야 하고, 철회하면 그 동의에
 * 기대던 대기 작업이 취소된다. 동의와 철회는 감사 기록에 남는다.
 */
class ConsentApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	FakeLlm llm;

	@Autowired
	ConsentService consents;

	long course;

	long professorId;

	long fileId;

	ApiClient professor;

	ApiClient student;

	ApiClient otherInstructor;

	@BeforeEach
	void course() {
		this.llm.reset(FakeLlm::honest);
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 앞 테스트가 남긴 작업을 비운다.
		}
		this.course = this.data.course();
		this.professorId = this.data.user("PROFESSOR");
		this.data.instructor(this.course, this.professorId);
		long studentId = this.data.user("STUDENT");
		this.data.enroll(this.course, studentId);
		long other = this.data.user("PROFESSOR");
		this.data.instructor(this.data.course(), other);
		this.fileId = this.data.pdfFile(
				TestPdfs.pdfWithText("Contact kim@example.ac.kr or 010-1234-5678, student 23101234", "Page two rho"),
				"clean");
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(this.fileId));
		this.professor = new ApiClient(this.mvc, this.data.session(this.professorId));
		this.student = new ApiClient(this.mvc, this.data.session(studentId));
		this.otherInstructor = new ApiClient(this.mvc, this.data.session(other));
	}

	@Test
	void policyShowsTheDeclaredProviderProfileToSignedInUsers() throws Exception {
		this.student.get("/api/llm/policy")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.providerName").value("DeepSeek API"))
			.andExpect(jsonPath("$.processingRegion").value("CN"))
			.andExpect(jsonPath("$.retentionDays").doesNotExist())
			.andExpect(jsonPath("$.trainingUse").value(true))
			.andExpect(jsonPath("$.crossBorderTransfer").value(true))
			.andExpect(jsonPath("$.acknowledgementRequired").value(true))
			.andExpect(jsonPath("$.profileDigest").value(this.consents.profile().digest()))
			.andExpect(jsonPath("$.scopes.length()").value(2));
		new ApiClient(this.mvc, null).get("/api/llm/policy").andExpect(status().isUnauthorized());
	}

	@Test
	void aProviderOutageDoesNotLookLikeADisabledDeployment() throws Exception {
		this.llm.reset((request) -> {
			throw new LlmException(LlmException.Code.NETWORK, "connection refused");
		});
		grant("page_summary", true).andExpect(status().isOk());

		this.student.get("/api/llm/policy").andExpect(jsonPath("$.enabled").value(true));
		long job = ApiClient.json(requestJob().andExpect(status().isAccepted())).get("id").asLong();
		SummaryRunnerDriver.runOnce(this.context);

		// 장애는 그 작업의 결과로 남고 배포 상태(enabled)와 새 요청의 거절 사유는 바뀌지 않는다.
		this.professor.get("/api/courses/" + this.course + "/summary-jobs/" + job)
			.andExpect(jsonPath("$.errorCode").value(not("not_configured")));
		this.student.get("/api/llm/policy").andExpect(jsonPath("$.enabled").value(true));
		requestJob().andExpect((result) -> assertThat(result.getResponse().getStatus()).isNotEqualTo(503));
	}

	@Test
	void summaryJobsNeedTheRequestersCurrentConsent() throws Exception {
		requestJob().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("llm_consent_required"));

		grant("page_summary", true).andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(true))
			.andExpect(jsonPath("$.current").value(true))
			.andExpect(jsonPath("$.acknowledged").value(true));
		long queued = ApiClient.json(requestJob().andExpect(status().isAccepted())).get("id").asLong();

		// 철회하면 대기 작업이 취소되고 새 요청과 재시도를 받지 않는다.
		this.professor.delete(consents() + "/page_summary").andExpect(status().isNoContent());
		this.professor.get("/api/courses/" + this.course + "/summary-jobs/" + queued)
			.andExpect(jsonPath("$.state").value("cancelled"))
			.andExpect(jsonPath("$.errorCode").value("consent"));
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isFalse();
		assertThat(this.llm.log).isEmpty();
		requestJob().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("llm_consent_required"));
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + queued + "/retry")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("llm_consent_required"));
		// 동의가 없을 때 철회해도 204다.
		this.professor.delete(consents() + "/page_summary").andExpect(status().isNoContent());
		assertThat(audits("llm_consent.grant")).isEqualTo(1);
		assertThat(audits("llm_consent.revoke")).isEqualTo(1);
	}

	@Test
	void consentMustMatchTheShownProfileAndAcknowledgeTransfer() throws Exception {
		grant("page_summary", false).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("llm_acknowledgement_required"));
		put("page_summary", "{\"noticeVersion\":\"" + this.consents.profile().noticeVersion()
				+ "\",\"profileDigest\":\"" + "0".repeat(64) + "\",\"acknowledged\":true}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("llm_policy_changed"));
		put("page_summary", "{\"noticeVersion\":\"old\",\"profileDigest\":\"" + this.consents.profile().digest()
				+ "\",\"acknowledged\":true}")
			.andExpect(status().isConflict());
		put("page_summary", "{\"noticeVersion\":\"x\"}").andExpect(status().isBadRequest());
		put("page_summary", "{\"noticeVersion\":\"x\",\"profileDigest\":\"y\",\"userId\":1}")
			.andExpect(status().isBadRequest());
		assertThat(rows()).isZero();

		JsonNode first = ApiClient.json(grant("page_summary", true).andExpect(status().isOk()));
		JsonNode again = ApiClient.json(grant("page_summary", true).andExpect(status().isOk()));
		assertThat(again.get("grantedAt")).isEqualTo(first.get("grantedAt"));
		assertThat(rows()).isEqualTo(1);

		// 배포 profile이 바뀐 것과 같다. 예전 동의는 효력을 잃고 다시 동의하면 새 행이 남는다.
		this.jdbc.sql("UPDATE llm_consents SET profile_sha256 = :sha WHERE course_id = :c")
			.param("sha", "f".repeat(64))
			.param("c", this.course)
			.update();
		this.professor.get(consents())
			.andExpect(jsonPath("$[?(@.scope == 'page_summary')].current").value(false));
		requestJob().andExpect(status().isConflict());
		grant("page_summary", true).andExpect(status().isOk()).andExpect(jsonPath("$.current").value(true));
		assertThat(rows()).isEqualTo(2);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM llm_consents WHERE course_id = :c AND revoked_at IS NULL")
			.param("c", this.course)
			.query(Long.class)
			.single()).isEqualTo(1);
		requestJob().andExpect(status().isAccepted());
	}

	@Test
	void onlyInstructorsOfTheCourseManageItsConsentAndUsage() throws Exception {
		for (ApiClient other : new ApiClient[] { this.student, this.otherInstructor }) {
			other.get(consents()).andExpect(status().isNotFound());
			grant(other, "page_summary").andExpect(status().isNotFound());
			other.delete(consents() + "/page_summary").andExpect(status().isNotFound());
			other.get("/api/courses/" + this.course + "/llm-usage").andExpect(status().isNotFound());
		}
		grant(this.professor, "unknown_scope").andExpect(status().isNotFound());
		this.mvc.perform(MockMvcRequestBuilders.put(consents() + "/page_summary")
			.cookie(new jakarta.servlet.http.Cookie("ASTRA_SESSION", this.data.session(this.professorId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isForbidden());
		assertThat(rows()).isZero();
	}

	@Test
	void onlyTheCoursesListedProfessorOrManagerGrantsConsentButAnyInstructorRevokesTheirOwn() throws Exception {
		long listedTa = this.data.user("TA");
		listAs(this.course, listedTa, "TA");
		long enrolledTa = this.data.user("TA");
		enrollAs(enrolledTa, "TA");
		long enrolledProfessor = this.data.user("PROFESSOR");
		enrollAs(enrolledProfessor, "PROFESSOR");
		LlmGate gate = this.context.getBean(LlmGate.class);
		for (long staff : new long[] { listedTa, enrolledTa, enrolledProfessor }) {
			ApiClient client = new ApiClient(this.mvc, this.data.session(staff));
			grant(client, "page_summary").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("llm_decider_required"));
			// 동의는 결정권자의 것이다. 조교도 과목 담당이라 자기 동의 목록과 사용량은 보고, 동의할 수 없다는 것도 목록에서 안다.
			client.get(consents()).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].granted").value(false))
				.andExpect(jsonPath("$[0].canGrant").value(false))
				.andExpect(jsonPath("$[1].canGrant").value(false));
			client.get("/api/courses/" + this.course + "/llm-usage").andExpect(status().isOk());
			// 예전에 남은 동의 행이 있어도 문은 열리지 않는다. current는 처리 조건이 같은지만 말하므로 true이고, 효력이 없다는 것은
			// canGrant=false가 말한다.
			LlmConsents.grant(this.context, this.course, staff, ProcessingScope.PAGE_SUMMARY);
			assertThat(gate.closed(this.course, staff, ProcessingScope.PAGE_SUMMARY)).contains(LlmGate.CONSENT);
			client.get(consents())
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].scope").value("page_summary"))
				.andExpect(jsonPath("$[0].granted").value(true))
				.andExpect(jsonPath("$[0].current").value(true))
				.andExpect(jsonPath("$[0].canGrant").value(false));
			client.post("/api/courses/" + this.course + "/files/" + this.fileId + "/summary-jobs")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("llm_decider_required"));
			// 철회는 처리를 좁히기만 하므로 본인이 직접 거둔다. 감사 기록이 남는다.
			client.delete(consents() + "/page_summary").andExpect(status().isNoContent());
			client.get(consents()).andExpect(jsonPath("$[0].granted").value(false));
		}
		assertThat(audits("llm_consent.revoke")).isEqualTo(3);
		grant(this.student, "page_summary").andExpect(status().isNotFound());
		this.student.delete(consents() + "/page_summary").andExpect(status().isNotFound());
		assertThat(audits("llm_consent.grant")).isZero();
		assertThat(audits("llm_consent.revoke")).isEqualTo(3);

		grant(this.professor, "page_summary").andExpect(status().isOk()).andExpect(jsonPath("$.granted").value(true));
		assertThat(gate.closed(this.course, this.professorId, ProcessingScope.PAGE_SUMMARY)).isEmpty();
		this.professor.get(consents())
			.andExpect(jsonPath("$[0].current").value(true))
			.andExpect(jsonPath("$[0].canGrant").value(true))
			.andExpect(jsonPath("$[1].granted").value(false))
			.andExpect(jsonPath("$[1].canGrant").value(true));
		this.professor.delete(consents() + "/page_summary").andExpect(status().isNoContent());
		assertThat(gate.closed(this.course, this.professorId, ProcessingScope.PAGE_SUMMARY)).contains(LlmGate.CONSENT);
	}

	@Test
	void aListedTaIsToldToAskTheDeciderEvenWhenTheProfessorConsented() throws Exception {
		long taId = this.data.user("TA");
		listAs(this.course, taId, "TA");
		ApiClient ta = new ApiClient(this.mvc, this.data.session(taId));
		grant("page_summary", true).andExpect(status().isOk());
		long cancelled = ApiClient.json(requestJob().andExpect(status().isAccepted())).get("id").asLong();
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + cancelled + "/cancel")
			.andExpect(status().isOk());

		// 동의가 아니라 요청한 사람이 문제다. 조교가 할 수 없는 동의를 안내하지 않는다. 동의, 생성, 재시도 모두 같은 거절이다.
		grant(ta, "page_summary").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("llm_decider_required"));
		ta.post("/api/courses/" + this.course + "/files/" + this.fileId + "/summary-jobs")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("llm_decider_required"));
		ta.post("/api/courses/" + this.course + "/summary-jobs/" + cancelled + "/retry")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("llm_decider_required"));
		ta.get(consents()).andExpect(jsonPath("$[0].canGrant").value(false));
		long running = ApiClient.json(requestJob().andExpect(status().isAccepted())).get("id").asLong();
		// 재시도할 수 없는 상태의 작업이어도 조교에게는 역할 거절이 먼저다.
		ta.post("/api/courses/" + this.course + "/summary-jobs/" + running + "/retry")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("llm_decider_required"));
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + running + "/cancel")
			.andExpect(status().isOk());
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isFalse();
		assertThat(this.llm.log).isEmpty();
		assertThat(audits("llm_consent.grant")).isEqualTo(1);
	}

	@Test
	void theListedProfessorAndManagerGrantStartAndRetry() throws Exception {
		long managerId = this.data.user("STAFF");
		listAs(this.course, managerId, "MANAGER");
		ApiClient manager = new ApiClient(this.mvc, this.data.session(managerId));
		for (ApiClient decider : new ApiClient[] { this.professor, manager }) {
			decider.get(consents()).andExpect(jsonPath("$[0].granted").value(false))
				.andExpect(jsonPath("$[0].canGrant").value(true));
			grant(decider, "page_summary").andExpect(status().isOk())
				.andExpect(jsonPath("$.current").value(true))
				.andExpect(jsonPath("$.canGrant").value(true));
			long job = ApiClient.json(decider.post("/api/courses/" + this.course + "/files/" + this.fileId + "/summary-jobs")
				.andExpect(status().isAccepted())).get("id").asLong();
			decider.post("/api/courses/" + this.course + "/summary-jobs/" + job + "/cancel").andExpect(status().isOk());
			long retried = ApiClient.json(decider.post("/api/courses/" + this.course + "/summary-jobs/" + job + "/retry")
				.andExpect(status().isAccepted())).get("id").asLong();
			decider.post("/api/courses/" + this.course + "/summary-jobs/" + retried + "/cancel")
				.andExpect(status().isOk());
		}
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void aTaWhoIsAlsoListedAsProfessorAndAManagerOfARegularCourseGrantConsent() throws Exception {
		long both = this.data.user("PROFESSOR");
		listAs(this.course, both, "TA");
		listAs(this.course, both, "PROFESSOR");
		long managerId = this.data.user("STAFF");
		listAs(this.course, managerId, "MANAGER");
		LlmGate gate = this.context.getBean(LlmGate.class);
		for (long decider : new long[] { both, managerId }) {
			ApiClient client = new ApiClient(this.mvc, this.data.session(decider));
			grant(client, "page_summary").andExpect(status().isOk()).andExpect(jsonPath("$.current").value(true));
			client.get(consents()).andExpect(jsonPath("$[0].current").value(true));
			assertThat(gate.closed(this.course, decider, ProcessingScope.PAGE_SUMMARY)).isEmpty();
		}
	}

	@Test
	void theManagerOfANonRegularCourseGrantsAndRevokesConsent() throws Exception {
		long nonRegular = this.data.insert("INSERT INTO courses (course_type, course_name) VALUES ('비정규', '특강')",
				Map.of());
		long managerId = this.data.user("STAFF");
		listAs(nonRegular, managerId, "MANAGER");
		ApiClient manager = new ApiClient(this.mvc, this.data.session(managerId));
		LlmGate gate = this.context.getBean(LlmGate.class);

		grant(manager, nonRegular, "page_summary").andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(true));
		assertThat(gate.closed(nonRegular, managerId, ProcessingScope.PAGE_SUMMARY)).isEmpty();
		manager.delete("/api/courses/" + nonRegular + "/llm-consents/page_summary").andExpect(status().isNoContent());
		assertThat(gate.closed(nonRegular, managerId, ProcessingScope.PAGE_SUMMARY)).contains(LlmGate.CONSENT);
		// 폐강한 과목에는 결정권자가 없다. 유효한 동의가 남아 있어도 폐강만으로 문이 닫힌다.
		LlmConsents.grant(this.context, nonRegular, managerId, ProcessingScope.PAGE_SUMMARY);
		assertThat(gate.closed(nonRegular, managerId, ProcessingScope.PAGE_SUMMARY)).isEmpty();
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", nonRegular).update();
		assertThat(gate.closed(nonRegular, managerId, ProcessingScope.PAGE_SUMMARY)).contains(LlmGate.CONSENT);
		grant(manager, nonRegular, "page_summary").andExpect(status().isNotFound());
	}

	@Test
	void aQueuedJobStopsWhenItsRequesterIsNoLongerTheCoursesDecider() throws Exception {
		grant("page_summary", true).andExpect(status().isOk());
		long queued = ApiClient.json(requestJob().andExpect(status().isAccepted())).get("id").asLong();
		// 교수가 조교로 바뀌었다. 남은 동의 행은 조교의 것이 되어 문을 열지 않는다.
		this.jdbc.sql("UPDATE course_instructors SET instructor_role = 'TA' WHERE course_id = :c AND user_id = :u")
			.param("c", this.course)
			.param("u", this.professorId)
			.update();

		SummaryRunnerDriver.runOnce(this.context);

		assertThat(this.llm.log).isEmpty();
		this.professor.get("/api/courses/" + this.course + "/summary-jobs/" + queued)
			.andExpect(jsonPath("$.state").value("cancelled"))
			.andExpect(jsonPath("$.errorCode").value("consent"));
	}

	@Test
	void pageTextIsRedactedBeforeTheSummaryModelSeesIt() throws Exception {
		grant("page_summary", true).andExpect(status().isOk());
		requestJob().andExpect(status().isAccepted());

		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();

		String sent = FakeLlm.text(this.llm.log.getFirst());
		assertThat(sent).contains("[이메일]", "[전화번호]", "[번호]")
			.doesNotContain("kim@example.ac.kr", "010-1234-5678", "23101234");
	}

	@Test
	void revokingWhileAJobRunsStopsItBeforeTheNextModelCall() throws Exception {
		long eight = this.data.pdfFile(TestPdfs.pdf(8), "clean");
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(eight));
		grant("page_summary", true).andExpect(status().isOk());
		long job = ApiClient.json(this.professor.post("/api/courses/" + this.course + "/files/" + eight + "/summary-jobs")
			.andExpect(status().isAccepted())).get("id").asLong();
		AtomicBoolean revoked = new AtomicBoolean();
		this.llm.reset((request) -> {
			if (revoked.compareAndSet(false, true)) {
				try {
					this.professor.delete(consents() + "/page_summary").andExpect(status().isNoContent());
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
			return FakeLlm.honest(request);
		});

		SummaryRunnerDriver.runOnce(this.context);

		assertThat(this.llm.log).hasSize(1);
		this.professor.get("/api/courses/" + this.course + "/summary-jobs/" + job)
			.andExpect(jsonPath("$.state").value("cancelled"))
			.andExpect(jsonPath("$.errorCode").value("consent"));
	}

	@Test
	void instructorsSeeCourseTotalsOfModelUsageWithoutBodies() throws Exception {
		grant("page_summary", true).andExpect(status().isOk());
		long job = ApiClient.json(requestJob().andExpect(status().isAccepted())).get("id").asLong();
		SummaryRunnerDriver.runOnce(this.context);

		this.professor.get("/api/courses/" + this.course + "/llm-usage")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.pageSummary.requests").value(1))
			.andExpect(jsonPath("$.pageSummary.calls").value(2))
			.andExpect(jsonPath("$.pageSummary.inputTokens").value(20))
			.andExpect(jsonPath("$.pageSummary.outputTokens").value(10))
			.andExpect(jsonPath("$.studyChat.requests").value(0))
			.andExpect(jsonPath("$.studyChatTodayTokens").value(0))
			.andExpect(jsonPath("$.studyChatDailyTokenBudget").value(2000000));
		assertThat(this.jdbc.sql("SELECT course_id FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", job)
			.query(Long.class)
			.single()).isEqualTo(this.course);
	}

	private ResultActions requestJob() throws Exception {
		return this.professor.post("/api/courses/" + this.course + "/files/" + this.fileId + "/summary-jobs");
	}

	private ResultActions grant(String scope, boolean acknowledged) throws Exception {
		return put(scope, "{\"noticeVersion\":\"" + this.consents.profile().noticeVersion() + "\",\"profileDigest\":\""
				+ this.consents.profile().digest() + "\",\"acknowledged\":" + acknowledged + "}");
	}

	private ResultActions grant(ApiClient client, String scope) throws Exception {
		return grant(client, this.course, scope);
	}

	private ResultActions grant(ApiClient client, long courseId, String scope) throws Exception {
		return client.send(MockMvcRequestBuilders.put("/api/courses/" + courseId + "/llm-consents/" + scope)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"noticeVersion\":\"" + this.consents.profile().noticeVersion() + "\",\"profileDigest\":\""
					+ this.consents.profile().digest() + "\",\"acknowledged\":true}"));
	}

	private void listAs(long courseId, long userId, String role) {
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, :r)")
			.param("c", courseId)
			.param("u", userId)
			.param("r", role)
			.update();
	}

	private void enrollAs(long userId, String role) {
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
				VALUES (:c, :u, 'REGISTRAR', :r, 'ENROLLED')
				""").param("c", this.course).param("u", userId).param("r", role).update();
	}

	private ResultActions put(String scope, String json) throws Exception {
		return this.professor.send(MockMvcRequestBuilders.put(consents() + "/" + scope)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private String consents() {
		return "/api/courses/" + this.course + "/llm-consents";
	}

	private long rows() {
		return this.jdbc.sql("SELECT COUNT(*) FROM llm_consents WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
	}

	private long audits(String operation) {
		return this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = :op AND course_id = :c")
			.param("op", operation)
			.param("c", this.course)
			.query(Long.class)
			.single();
	}

}
