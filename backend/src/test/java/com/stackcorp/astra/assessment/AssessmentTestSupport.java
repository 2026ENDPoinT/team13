package com.stackcorp.astra.assessment;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 과제 테스트의 공통 준비. 테스트마다 새 과목에 담당자, 수강생 둘, 과목 밖 사용자를 만든다.
 */
abstract class AssessmentTestSupport extends IntegrationTest {

	static final String TEXT = "제출 본문\n두 번째 줄";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	Client professor;

	Client student;

	Client classmate;

	Client outsider;

	Client anonymous;

	long studentId;

	long classmateId;

	long professorId;

	@BeforeEach
	void people() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.professorId = prof;
		this.studentId = this.data.user("STUDENT");
		this.classmateId = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, this.studentId);
		this.data.enroll(this.course, this.classmateId);
		this.professor = client(prof);
		this.student = client(this.studentId);
		this.classmate = client(this.classmateId);
		this.outsider = client(this.data.user("STUDENT"));
		this.anonymous = new Client(null);
	}

	Client client(long userId) {
		return new Client(this.data.session(userId));
	}

	/** 담당자가 함께 맡은 다른 과목. 경로의 과목과 객체가 맞지 않는 요청을 볼 때 쓴다. */
	long otherCourseOfProfessor() {
		long other = this.data.course();
		this.data.instructor(other, this.professorId);
		return other;
	}

	/** 과목을 폐강한다. 폐강한 과목에는 구성원이 없다(MembershipRule). */
	void cancelCourse() {
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :id").param("id", this.course).update();
	}

	long auditLines(String operation, long objectId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = :op AND object_id = :id")
			.param("op", operation)
			.param("id", objectId)
			.query(Long.class)
			.single();
	}

	/** 담당자가 과제를 만든다. @return 과제 ID */
	long assignment(String extraFields) throws Exception {
		String body = """
				{"weekNo":3,"title":"과제 1","description":"정규화 보고서","closeAt":"2999-01-01T00:00:00","maxScore":15%s}
				""".formatted(extraFields.isEmpty() ? "" : "," + extraFields);
		return ApiClient.json(this.professor.postJson(assignments(this.course), body).andExpect(status().isCreated()))
			.get("assignment")
			.get("assignmentId")
			.asLong();
	}

	long activityOf(long assignmentId) {
		return this.jdbc.sql("SELECT activity_id FROM assignments WHERE assignment_id = :id")
			.param("id", assignmentId)
			.query(Long.class)
			.single();
	}

	/** 활동 기간을 DB 시계 기준으로 옮긴다. 식은 SQL이고 CURRENT_TIMESTAMP를 쓴다. */
	void schedule(long assignmentId, String openAtSql, String closeAtSql) {
		this.jdbc.sql("UPDATE learning_activities SET open_at = " + openAtSql + ", close_at = " + closeAtSql
				+ " WHERE activity_id = :id").param("id", activityOf(assignmentId)).update();
	}

	void latePolicy(long assignmentId, String policy, String lateUntilSql) {
		this.jdbc.sql("UPDATE assignments SET late_policy = :policy, late_until = " + lateUntilSql
				+ " WHERE assignment_id = :id").param("policy", policy).param("id", assignmentId).update();
	}

	long versionCount(long assignmentId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM assignment_submission_versions v
				  JOIN assignment_submissions s ON s.assignment_submission_id = v.assignment_submission_id
				 WHERE s.assignment_id = :id
				""").param("id", assignmentId).query(Long.class).single();
	}

	static String assignments(long courseId) {
		return "/api/courses/" + courseId + "/assignments";
	}

	static String assignment(long courseId, long assignmentId) {
		return assignments(courseId) + "/" + assignmentId;
	}

	static String submission(long courseId, long assignmentId) {
		return assignment(courseId, assignmentId) + "/submission";
	}

	static String submissions(long courseId, long assignmentId) {
		return assignment(courseId, assignmentId) + "/submissions";
	}

	static String fileContent(long courseId, long fileId) {
		return "/api/courses/" + courseId + "/files/" + fileId + "/content";
	}

	static String submitBody(int expectedVersion, String body, long... uploadIds) {
		StringBuilder ids = new StringBuilder();
		for (long id : uploadIds) {
			ids.append(ids.isEmpty() ? "" : ",").append(id);
		}
		return "{\"expectedVersion\":" + expectedVersion + ",\"body\":" + (body == null ? "null" : "\"" + body.replace("\n", "\\n") + "\"")
				+ ",\"uploadIds\":[" + ids + "]}";
	}

	/** 브라우저처럼 session 쿠키와 CSRF token을 붙여 부른다. */
	final class Client {

		private final Cookie session;

		Client(String token) {
			this.session = token == null ? null : new Cookie("ASTRA_SESSION", token);
		}

		ResultActions get(String path) throws Exception {
			return AssessmentTestSupport.this.mvc.perform(withSession(MockMvcRequestBuilders.get(path)));
		}

		ResultActions post(String path) throws Exception {
			return send(MockMvcRequestBuilders.post(path));
		}

		ResultActions delete(String path) throws Exception {
			return send(MockMvcRequestBuilders.delete(path));
		}

		ResultActions postJson(String path, String json) throws Exception {
			return send(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		ResultActions putJson(String path, String json) throws Exception {
			return send(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json));
		}

		ResultActions submit(String path, String key, String json) throws Exception {
			MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
			if (key != null) {
				request.header("Idempotency-Key", key);
			}
			return send(request);
		}

		/** CSRF token 없이 보낸다. */
		ResultActions postWithoutCsrf(String path, String json) throws Exception {
			return AssessmentTestSupport.this.mvc.perform(withSession(
					MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json)));
		}

		ResultActions putWithoutCsrf(String path, String json) throws Exception {
			return AssessmentTestSupport.this.mvc.perform(withSession(
					MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json)));
		}

		/** If-Match를 붙여 지운다. ifMatch가 null이면 header 없이 보낸다. */
		ResultActions delete(String path, String ifMatch) throws Exception {
			MockHttpServletRequestBuilder request = MockMvcRequestBuilders.delete(path);
			if (ifMatch != null) {
				request.header("If-Match", ifMatch);
			}
			return send(request);
		}

		ResultActions deleteWithoutCsrf(String path, String ifMatch) throws Exception {
			return AssessmentTestSupport.this.mvc
				.perform(withSession(MockMvcRequestBuilders.delete(path).header("If-Match", ifMatch)));
		}

		/** 과제 제출 첨부를 올린다. @return uploadId */
		long upload(long courseId, String name, byte[] content) throws Exception {
			ResultActions result = send(MockMvcRequestBuilders.multipart("/api/uploads")
				.file(new MockMultipartFile("file", name, "application/octet-stream", content))
				.param("courseId", Long.toString(courseId))
				.param("purpose", "assignment_submission"));
			JsonNode json = ApiClient.json(result.andExpect(status().isCreated()));
			return json.get("uploadId").asLong();
		}

		<B extends AbstractMockHttpServletRequestBuilder<B>> ResultActions send(B request) throws Exception {
			MockMvc mvc = AssessmentTestSupport.this.mvc;
			Cookie csrf = mvc.perform(MockMvcRequestBuilders.get("/api/auth/csrf"))
				.andReturn()
				.getResponse()
				.getCookie("XSRF-TOKEN");
			request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
			return mvc.perform(withSession(request));
		}

		private <B extends AbstractMockHttpServletRequestBuilder<B>> B withSession(B request) {
			return this.session == null ? request : request.cookie(this.session);
		}

	}

}
