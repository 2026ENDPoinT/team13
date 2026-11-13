package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.TestPdfs;

import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;

/**
 * 학생의 임시저장과 최종 제출. 최종 제출은 바뀌지 않는 버전이고, 접수 시각과 지각 여부는 DB 시계와 정책으로만 정한다.
 * 같은 Idempotency-Key는 같은 receipt로 수렴하고, 다른 탭의 제출은 버전 번호로 막는다(SC-05, SC-05-B, SC-06, SEC-19).
 */
class SubmissionFlowTests extends AssessmentTestSupport {

	private static final byte[] TXT = "my answer".getBytes(StandardCharsets.UTF_8);

	@Test
	void draftIsNotASubmissionAndEachFinalSubmitIsAnImmutableVersion() throws Exception {
		long id = assignment("");
		this.student.putJson(submission(this.course, id) + "/draft", "{\"body\":\"초안\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("초안"))
			.andExpect(jsonPath("$.savedAt").exists());
		this.student.get(assignment(this.course, id))
			.andExpect(jsonPath("$.submission.status").value("not_submitted"))
			.andExpect(jsonPath("$.submission.version").value(0))
			.andExpect(jsonPath("$.submission.draft.body").value("초안"));
		// 임시저장은 담당자에게 제출로 보이지 않는다.
		this.professor.get(submissions(this.course, id)).andExpect(jsonPath("$.length()").value(0));
		this.professor.get(assignment(this.course, id)).andExpect(jsonPath("$.submittedCount").value(0));

		long pdf = this.student.upload(this.course, "report.pdf", TestPdfs.pdf(1));
		long txt = this.student.upload(this.course, "answer.txt", TXT);
		JsonNode first = ApiClient.json(this.student.submit(submission(this.course, id), "submit-1", submitBody(0, TEXT, pdf, txt))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.late").value(false))
			.andExpect(jsonPath("$.body").value(TEXT))
			.andExpect(jsonPath("$.files.length()").value(2))
			.andExpect(jsonPath("$.files[0].name").value("report.pdf"))
			.andExpect(jsonPath("$.submittedAt").exists()));
		this.student.get("/api/uploads/" + pdf).andExpect(jsonPath("$.state").value("attached"));
		this.student.get(assignment(this.course, id))
			.andExpect(jsonPath("$.submission.status").value("submitted"))
			.andExpect(jsonPath("$.submission.version").value(1))
			.andExpect(jsonPath("$.submission.body").value(TEXT))
			.andExpect(jsonPath("$.submission.files.length()").value(2))
			.andExpect(jsonPath("$.submission.draft").isEmpty());

		this.student.submit(submission(this.course, id), "submit-2", submitBody(1, "수정본"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.files.length()").value(0));
		// 앞 버전은 그대로 남는다.
		long submissionId = first.get("submissionId").asLong();
		this.professor.get(submissions(this.course, id) + "/" + submissionId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.versions.length()").value(2))
			.andExpect(jsonPath("$.versions[0].version").value(2))
			.andExpect(jsonPath("$.versions[0].body").value("수정본"))
			.andExpect(jsonPath("$.versions[1].body").value(TEXT))
			.andExpect(jsonPath("$.versions[1].files.length()").value(2))
			.andExpect(jsonPath("$.versions[1].submittedAt").value(first.get("submittedAt").asString()));
		this.professor.get(submissions(this.course, id))
			.andExpect(jsonPath("$[0].studentId").value(this.studentId))
			.andExpect(jsonPath("$[0].studentName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$[0].versionCount").value(2))
			.andExpect(jsonPath("$[0].status").value("submitted"))
			.andExpect(jsonPath("$[0].late").value(false))
			.andExpect(jsonPath("$[0].firstSubmittedAt").value(first.get("submittedAt").asString()));
	}

	@Test
	void sameKeyReturnsTheSameReceiptAndADifferentPayloadUnderItIsAConflict() throws Exception {
		long id = assignment("");
		String body = submitBody(0, TEXT);
		String receipt = this.student.submit(submission(this.course, id), "key-1", body)
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		this.student.submit(submission(this.course, id), "key-1", body)
			.andExpect(status().isCreated())
			.andExpect(content().json(receipt, org.springframework.test.json.JsonCompareMode.STRICT));
		this.student.submit(submission(this.course, id), "key-1", submitBody(0, "다른 본문"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		// 같은 key를 다른 과제에 쓰면 다른 요청이다.
		long other = assignment("");
		this.student.submit(submission(this.course, other), "key-1", body)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(versionCount(id)).isEqualTo(1);
		assertThat(versionCount(other)).isZero();

		// 마감 뒤에 응답을 잃은 요청을 다시 보내도 처음 receipt를 돌려준다. 새 제출은 받지 않는다.
		schedule(id, "NULL", "CURRENT_TIMESTAMP - INTERVAL 1 MINUTE");
		this.student.submit(submission(this.course, id), "key-1", body)
			.andExpect(status().isCreated())
			.andExpect(content().json(receipt, org.springframework.test.json.JsonCompareMode.STRICT));
		this.student.submit(submission(this.course, id), "key-2", submitBody(1, TEXT))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("deadline_passed"));
		assertThat(versionCount(id)).isEqualTo(1);
	}

	@Test
	void twoTabsSubmittingFromTheSameVersionDoNotOverwriteEachOther() throws Exception {
		long id = assignment("");
		this.student.submit(submission(this.course, id), "tab-a", submitBody(0, "A 탭")).andExpect(status().isCreated());
		this.student.submit(submission(this.course, id), "tab-b", submitBody(0, "B 탭"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.get(assignment(this.course, id)).andExpect(jsonPath("$.submission.body").value("A 탭"));
		assertThat(versionCount(id)).isEqualTo(1);
	}

	@Test
	void concurrentSubmitsCreateExactlyOneVersionPerAcceptedSubmit() throws Exception {
		long sameKey = assignment("");
		String body = submitBody(0, TEXT);
		List<MockHttpServletResponse> replays = race(6,
				(i) -> this.student.submit(submission(this.course, sameKey), "same-key", body).andReturn().getResponse());
		assertThat(replays).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		assertThat(replays).extracting((r) -> ApiClient.json(r).get("submittedAt").asString()).containsOnly(
				ApiClient.json(replays.get(0)).get("submittedAt").asString());
		assertThat(versionCount(sameKey)).isEqualTo(1);

		long differentKeys = assignment("");
		List<MockHttpServletResponse> tabs = race(6, (i) -> this.student
			.submit(submission(this.course, differentKeys), "tab-" + i, submitBody(0, "탭 " + i))
			.andReturn()
			.getResponse());
		assertThat(tabs).extracting(MockHttpServletResponse::getStatus).containsOnly(201, 409);
		assertThat(tabs).filteredOn((r) -> r.getStatus() == 201).hasSize(1);
		assertThat(versionCount(differentKeys)).isEqualTo(1);
	}

	@Test
	void deadlineEdgesFollowTheDbClockAndTheExplicitLatePolicy() throws Exception {
		// openAt = now: 열려 있다(openAt <= now).
		long atOpen = assignment("");
		schedule(atOpen, "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL 1 HOUR");
		this.student.submit(submission(this.course, atOpen), "open", submitBody(0, TEXT))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.late").value(false));

		// openAt 전: 학생에게 과제가 없다.
		long beforeOpen = assignment("");
		schedule(beforeOpen, "CURRENT_TIMESTAMP + INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 2 HOUR");
		this.student.get(assignment(this.course, beforeOpen)).andExpect(status().isNotFound());
		this.student.submit(submission(this.course, beforeOpen), "before", submitBody(0, TEXT))
			.andExpect(status().isNotFound());

		// closeAt = now, 지각 불허: 닫혀 있다(now < closeAt가 아니다). 임시저장도 받지 않는다.
		long atClose = assignment("");
		schedule(atClose, "NULL", "CURRENT_TIMESTAMP");
		this.student.submit(submission(this.course, atClose), "close", submitBody(0, TEXT))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("deadline_passed"));
		this.student.putJson(submission(this.course, atClose) + "/draft", "{\"body\":\"늦은 초안\"}")
			.andExpect(status().isConflict());
		this.student.get(assignment(this.course, atClose))
			.andExpect(jsonPath("$.phase").value("closed"))
			.andExpect(jsonPath("$.submission.status").value("not_submitted"));

		// closeAt = now, allow_until 정책이고 lateUntil 전: 지각으로 받는다.
		long late = assignment("");
		schedule(late, "NULL", "CURRENT_TIMESTAMP");
		latePolicy(late, "allow_until", "CURRENT_TIMESTAMP + INTERVAL 1 HOUR");
		this.student.get(assignment(this.course, late)).andExpect(jsonPath("$.phase").value("late"));
		this.student.submit(submission(this.course, late), "late", submitBody(0, TEXT))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.late").value(true));
		this.student.get(assignment(this.course, late)).andExpect(jsonPath("$.submission.status").value("late"));
		this.professor.get(submissions(this.course, late)).andExpect(jsonPath("$[0].late").value(true));
		this.professor.get(assignment(this.course, late)).andExpect(jsonPath("$.lateCount").value(1));

		// lateUntil = now: 지각 구간도 끝났다.
		long afterLate = assignment("");
		schedule(afterLate, "NULL", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR");
		latePolicy(afterLate, "allow_until", "CURRENT_TIMESTAMP");
		this.student.submit(submission(this.course, afterLate), "after-late", submitBody(0, TEXT))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("deadline_passed"));
		assertThat(versionCount(atClose) + versionCount(afterLate)).isZero();
	}

	@Test
	void anUnusableAttachmentRollsBackTheWholeSubmitWithoutAReceipt() throws Exception {
		long id = assignment("");
		long used = this.student.upload(this.course, "first.txt", TXT);
		this.student.submit(submission(this.course, id), "v1", submitBody(0, TEXT, used)).andExpect(status().isCreated());
		long quarantined = this.student.upload(this.course, "scan.pdf", TestPdfs.pdf(2));
		this.jdbc.sql("UPDATE upload_sessions SET upload_state = 'quarantined' WHERE upload_id = :id")
			.param("id", quarantined)
			.update();
		long theirs = this.classmate.upload(this.course, "theirs.txt", TXT);
		long good = this.student.upload(this.course, "good.txt", TXT);

		for (long bad : new long[] { used, quarantined, theirs, 999_999_999L }) {
			this.student.submit(submission(this.course, id), "bad-" + bad, submitBody(1, TEXT, good, bad))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		}
		assertThat(versionCount(id)).isEqualTo(1);
		this.student.get("/api/uploads/" + good).andExpect(jsonPath("$.state").value("clean"));
		this.student.get(assignment(this.course, id)).andExpect(jsonPath("$.submission.version").value(1));
		// 실패한 key에는 receipt가 없다. 같은 key로 고친 요청을 보내면 새로 접수한다.
		this.student.submit(submission(this.course, id), "bad-" + theirs, submitBody(1, TEXT, good))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(2));
	}

	@Test
	void resubmissionKeepsOnlyFilesOfTheOwnLatestVersionAndHistoryKeepsEachVersionsFiles() throws Exception {
		long id = assignment("");
		byte[] pdf = TestPdfs.pdf(1);
		long report = this.student.upload(this.course, "report.pdf", pdf);
		long notes = this.student.upload(this.course, "notes.txt", TXT);
		JsonNode first = ApiClient.json(this.student.submit(submission(this.course, id), "k1", submitBody(0, TEXT, report, notes))
			.andExpect(status().isCreated()));
		long reportFile = first.get("files").get(0).get("fileId").asLong();
		long notesFile = first.get("files").get(1).get("fileId").asLong();
		long appendix = this.student.upload(this.course, "appendix.txt", TXT);

		// 이어받은 파일과 새 업로드가 새 버전의 첨부다. 앞 버전은 제출했을 때의 파일을 그대로 가진다.
		String keepReport = "{\"expectedVersion\":1,\"body\":\"수정본\",\"uploadIds\":[" + appendix
				+ "],\"keepFileIds\":[" + reportFile + "]}";
		String second = this.student.submit(submission(this.course, id), "k2", keepReport)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.files[*].name").value(org.hamcrest.Matchers.contains("report.pdf", "appendix.txt")))
			.andExpect(jsonPath("$.files[0].fileId").value((int) reportFile))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		this.student.submit(submission(this.course, id), "k2", keepReport)
			.andExpect(status().isCreated())
			.andExpect(content().json(second, org.springframework.test.json.JsonCompareMode.STRICT));
		this.student.submit(submission(this.course, id), "k2", "{\"expectedVersion\":1,\"body\":\"수정본\",\"uploadIds\":["
				+ appendix + "],\"keepFileIds\":[]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		this.student.get(fileContent(this.course, reportFile)).andExpect(status().isOk()).andExpect(content().bytes(pdf));
		this.professor.get(fileContent(this.course, reportFile)).andExpect(status().isOk());
		this.classmate.get(fileContent(this.course, reportFile)).andExpect(status().isNotFound());

		// 지금 버전에 없는 파일(앞 버전에만 있는 파일), 다른 학생의 파일, 없는 파일은 이어받지 못하고 아무것도 남지 않는다.
		long otherAssignment = assignment("");
		long theirs = ApiClient.json(this.classmate.submit(submission(this.course, otherAssignment), "theirs",
				submitBody(0, null, this.classmate.upload(this.course, "theirs.txt", TXT))).andExpect(status().isCreated()))
			.get("files").get(0).get("fileId").asLong();
		for (long bad : new long[] { notesFile, theirs, 999_999_999L }) {
			this.student.submit(submission(this.course, id), "bad-keep-" + bad,
					"{\"expectedVersion\":2,\"body\":\"x\",\"keepFileIds\":[" + bad + "]}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		}
		// 첫 제출에는 이어받을 버전이 없다.
		this.student.submit(submission(this.course, assignment("")), "first-keep",
				"{\"expectedVersion\":0,\"body\":\"x\",\"keepFileIds\":[" + reportFile + "]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		assertThat(versionCount(id)).isEqualTo(2);

		// 본문만 다시 내면 새 버전에 파일이 없고, 앞 두 버전의 파일은 이력에 그대로다.
		this.student.submit(submission(this.course, id), "k3", submitBody(2, "세 번째"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.files.length()").value(0));
		long submissionId = first.get("submissionId").asLong();
		this.professor.get(submissions(this.course, id) + "/" + submissionId)
			.andExpect(jsonPath("$.versions[0].files.length()").value(0))
			.andExpect(jsonPath("$.versions[1].files[*].name").value(org.hamcrest.Matchers.contains("report.pdf", "appendix.txt")))
			.andExpect(jsonPath("$.versions[2].files[*].name").value(org.hamcrest.Matchers.contains("report.pdf", "notes.txt")));

		for (String bad : new String[] { "{\"expectedVersion\":3,\"keepFileIds\":[1,1]}",
				"{\"expectedVersion\":3,\"keepFileIds\":[0]}",
				"{\"expectedVersion\":3,\"uploadIds\":[1,2,3],\"keepFileIds\":[4,5,6]}" }) {
			this.student.submit(submission(this.course, id), "invalid-keep", bad)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_request"));
		}
		long textOnly = assignment("\"allowFile\":false");
		this.student.submit(submission(this.course, textOnly), "text-keep",
				"{\"expectedVersion\":0,\"body\":\"x\",\"keepFileIds\":[" + reportFile + "]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		this.student.submit(submission(this.course, textOnly), "text-upload",
				"{\"expectedVersion\":0,\"body\":\"x\",\"uploadIds\":[" + reportFile + "],\"keepFileIds\":[]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		// 파일을 받지 않는 과제에도 빈 목록은 파일 없음과 같다. 다시 제출할 때도 그렇다.
		this.student.submit(submission(this.course, textOnly), "text-empty",
				"{\"expectedVersion\":0,\"body\":\"첫 글\",\"uploadIds\":[],\"keepFileIds\":[]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.files.length()").value(0));
		this.student.submit(submission(this.course, textOnly), "text-again",
				"{\"expectedVersion\":1,\"body\":\"고친 글\",\"keepFileIds\":[]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.files.length()").value(0));
	}

	@Test
	void classmatesAndOutsidersCannotReachASubmissionOrItsFilesByAnyPath() throws Exception {
		long id = assignment("");
		byte[] pdf = TestPdfs.pdf(1);
		long upload = this.student.upload(this.course, "mine.pdf", pdf);
		JsonNode receipt = ApiClient.json(this.student.submit(submission(this.course, id), "mine", submitBody(0, "SECRET-BODY", upload))
			.andExpect(status().isCreated()));
		long fileId = receipt.get("files").get(0).get("fileId").asLong();
		long submissionId = receipt.get("submissionId").asLong();

		this.student.get(fileContent(this.course, fileId)).andExpect(status().isOk()).andExpect(content().bytes(pdf));
		this.professor.get(fileContent(this.course, fileId)).andExpect(status().isOk());

		this.classmate.get(fileContent(this.course, fileId)).andExpect(status().isNotFound());
		this.classmate.get("/api/courses/" + this.course + "/files/" + fileId + "/summary").andExpect(status().isNotFound());
		this.classmate.post("/api/courses/" + this.course + "/files/" + fileId + "/summary-jobs").andExpect(status().isNotFound());
		this.classmate.get(submissions(this.course, id)).andExpect(status().isNotFound());
		this.classmate.get(submissions(this.course, id) + "/" + submissionId).andExpect(status().isNotFound());
		String classmateView = this.classmate.get(assignment(this.course, id))
			.andExpect(jsonPath("$.submission.status").value("not_submitted"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(classmateView).doesNotContain("SECRET-BODY").doesNotContain("mine.pdf");
		assertThat(this.classmate.get(assignments(this.course)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
			.doesNotContain("SECRET-BODY");

		this.outsider.get(fileContent(this.course, fileId)).andExpect(status().isNotFound());
		this.anonymous.get(fileContent(this.course, fileId)).andExpect(status().isUnauthorized());
		// 제출물 파일로는 요약 같은 파생물을 만들지 않는다. 담당자도, 제출한 학생도.
		this.professor.post("/api/courses/" + this.course + "/files/" + fileId + "/summary-jobs").andExpect(status().isNotFound());
		this.student.post("/api/courses/" + this.course + "/files/" + fileId + "/summary-jobs").andExpect(status().isNotFound());

		// 다른 과목 경로로는 닿지 않는다(SC-03).
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.studentId);
		this.student.get(fileContent(otherCourse, fileId)).andExpect(status().isNotFound());
		long otherProfessor = this.data.user("PROFESSOR");
		this.data.instructor(otherCourse, otherProfessor);
		client(otherProfessor).get(fileContent(otherCourse, fileId)).andExpect(status().isNotFound());
		client(otherProfessor).get(submissions(otherCourse, id) + "/" + submissionId).andExpect(status().isNotFound());
	}

	@Test
	void onlyEnrolledStudentsSubmitTheirOwnWorkThroughTheDocumentedFields() throws Exception {
		long id = assignment("");
		String body = submitBody(0, TEXT);
		this.professor.submit(submission(this.course, id), "p", body).andExpect(status().isNotFound());
		this.outsider.submit(submission(this.course, id), "o", body).andExpect(status().isNotFound());
		this.outsider.putJson(submission(this.course, id) + "/draft", "{\"body\":\"x\"}").andExpect(status().isNotFound());
		this.professor.putJson(submission(this.course, id) + "/draft", "{\"body\":\"x\"}").andExpect(status().isNotFound());
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.studentId);
		this.student.submit(submission(otherCourse, id), "c", body).andExpect(status().isNotFound());
		this.anonymous.submit(submission(this.course, id), "a", body).andExpect(status().isUnauthorized());
		this.student.postWithoutCsrf(submission(this.course, id), body).andExpect(status().isForbidden());
		this.student.putWithoutCsrf(submission(this.course, id) + "/draft", "{\"body\":\"x\"}").andExpect(status().isForbidden());
		// 업로드도 그 과목의 수강생만 한다.
		this.outsider.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/uploads")
			.file(new org.springframework.mock.web.MockMultipartFile("file", "a.txt", "text/plain", TXT))
			.param("courseId", Long.toString(this.course))
			.param("purpose", "assignment_submission")).andExpect(status().isNotFound());

		String[] invalid = { submitBody(0, null), "{\"expectedVersion\":0,\"body\":\"x\",\"userId\":" + this.classmateId + "}",
				"{\"body\":\"x\"}", "{\"expectedVersion\":-1,\"body\":\"x\"}", "{\"expectedVersion\":0,\"body\":\"a\\u0007b\"}",
				"{\"expectedVersion\":0,\"uploadIds\":[1,1]}", "{\"expectedVersion\":0,\"uploadIds\":[1,2,3,4,5,6]}" };
		for (String bad : invalid) {
			this.student.submit(submission(this.course, id), "invalid", bad)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_request"));
		}
		this.student.submit(submission(this.course, id), null, body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("idempotency_key_required"));
		this.student.submit(submission(this.course, id), "bad key!", body).andExpect(status().isBadRequest());
		this.student.putJson(submission(this.course, id) + "/draft", "{\"body\":\"x\",\"submitted\":true}")
			.andExpect(status().isBadRequest());

		// 제출 형태를 따른다.
		long fileOnly = assignment("\"allowText\":false");
		this.student.submit(submission(this.course, fileOnly), "text", body).andExpect(status().isBadRequest());
		long textOnly = assignment("\"allowFile\":false");
		long upload = this.student.upload(this.course, "a.txt", TXT);
		this.student.submit(submission(this.course, textOnly), "file", submitBody(0, null, upload))
			.andExpect(status().isBadRequest());
		assertThat(versionCount(id) + versionCount(fileOnly) + versionCount(textOnly)).isZero();
	}

	private List<MockHttpServletResponse> race(int threads, Attempt attempt) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				Callable<MockHttpServletResponse> call = () -> {
					start.await();
					return attempt.run(n);
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(60, TimeUnit.SECONDS));
			}
			return responses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	private interface Attempt {

		MockHttpServletResponse run(int n) throws Exception;

	}

}
