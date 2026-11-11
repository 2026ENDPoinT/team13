package com.stackcorp.astra.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;

import jakarta.servlet.http.Cookie;

/**
 * 같은 Idempotency-Key의 재전송은 처음 응답을 돌려받고 아무것도 더 만들지 않는다(SC-05). 강의 자료 등록과 요약 생성 요청이
 * 원장을 쓰는 첫 명령이다.
 */
class IdempotencyApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	IdempotencyPurge purge;

	long course;

	long professorId;

	String professor;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.professorId = this.data.user("PROFESSOR");
		this.data.instructor(this.course, this.professorId);
		this.professor = this.data.session(this.professorId);
	}

	@Test
	void replayReturnsTheFirstResponseAndCreatesNothingMore() throws Exception {
		long upload = new ApiClient(this.mvc, this.professor).uploaded(this.course, "week3.pdf", TestPdfs.pdf(1));
		String body = material("3주차", upload);

		String first = createMaterial(this.professor, "key-1", body).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String second = createMaterial(this.professor, "key-1", body).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(materials()).isEqualTo(1);
		assertThat(ledger(this.professorId, "key-1")).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = 'material.create' AND course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void sameKeyWithAnotherBodyOrScopeIsAConflict() throws Exception {
		long otherCourse = this.data.course();
		this.data.instructor(otherCourse, this.professorId);
		createMaterial(this.professor, "key-2", material("원래 제목", null)).andExpect(status().isCreated());

		createMaterial(this.professor, "key-2", material("바꾼 제목", null)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		perform(this.professor, "key-2", "/api/courses/" + otherCourse + "/materials", material("원래 제목", null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		// 공백만 다른 제목은 검사한 뒤 같은 요청이다.
		createMaterial(this.professor, "key-2", material("  원래 제목 ", null)).andExpect(status().isCreated());

		assertThat(materials()).isEqualTo(1);
	}

	@Test
	void rejectsMalformedKeys() throws Exception {
		createMaterial(this.professor, "has space", material("자료", null)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		createMaterial(this.professor, "k".repeat(101), material("자료", null)).andExpect(status().isBadRequest());

		assertThat(materials()).isZero();
	}

	@Test
	void concurrentDuplicatesCreateExactlyOneMaterial() throws Exception {
		long upload = new ApiClient(this.mvc, this.professor).uploaded(this.course, "week4.pdf", TestPdfs.pdf(1));
		String body = material("4주차", upload);
		List<MockHttpServletResponse> responses;
		// 첨부할 업로드를 잠가 처음 예약한 요청을 명령 도중에 붙잡는다. 나머지는 같은 key를 기다린다.
		try (Connection lock = lockUpload(upload)) {
			ExecutorService pool = Executors.newFixedThreadPool(4);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return createMaterial(this.professor, "key-4", body).andReturn().getResponse();
				}));
			}
			start.countDown();
			Thread.sleep(1000);
			lock.rollback();
			responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(30, TimeUnit.SECONDS));
			}
			pool.shutdown();
		}

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
		assertThat(responses).extracting(IdempotencyApiTests::body).containsOnly(body(responses.getFirst()));
		assertThat(materials()).isEqualTo(1);
		assertThat(ledger(this.professorId, "key-4")).isEqualTo(1);
	}

	@Test
	void duplicateOfACommandInFlightGetsAPendingAnswerAfterTheBoundedWaitAndTheReceiptLater() throws Exception {
		long upload = new ApiClient(this.mvc, this.professor).uploaded(this.course, "week5.pdf", TestPdfs.pdf(1));
		String body = material("5주차", upload);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<MockHttpServletResponse> first;
		try (Connection lock = lockUpload(upload)) {
			first = pool.submit(() -> createMaterial(this.professor, "key-5", body).andReturn().getResponse());
			awaitReservation(this.professorId, "key-5");

			createMaterial(this.professor, "key-5", body).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("idempotency_in_progress"));
			lock.rollback();
		}
		MockHttpServletResponse created = first.get(30, TimeUnit.SECONDS);
		pool.shutdown();

		assertThat(created.getStatus()).isEqualTo(201);
		String replay = createMaterial(this.professor, "key-5", body).andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(replay).isEqualTo(created.getContentAsString());
		assertThat(materials()).isEqualTo(1);
	}

	@Test
	void failedCommandLeavesNoReservationAndCanBeRetriedWithTheSameKey() throws Exception {
		long upload = new ApiClient(this.mvc, this.professor).uploaded(this.course, "week6.pdf", TestPdfs.pdf(1));
		String body = material("6주차", upload);
		setUploadState(upload, "quarantined");

		createMaterial(this.professor, "key-6", body).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		assertThat(ledger(this.professorId, "key-6")).isZero();

		setUploadState(upload, "clean");
		createMaterial(this.professor, "key-6", body).andExpect(status().isCreated());
		assertThat(materials()).isEqualTo(1);
	}

	@Test
	void anotherUsersSameKeyIsIndependent() throws Exception {
		long colleagueId = this.data.user("PROFESSOR");
		this.data.instructor(this.course, colleagueId);
		String body = material("같은 제목", null);

		long mine = ApiClient.json(createMaterial(this.professor, "shared-key", body).andExpect(status().isCreated()))
			.get("activityId")
			.asLong();
		long theirs = ApiClient
			.json(createMaterial(this.data.session(colleagueId), "shared-key", body).andExpect(status().isCreated()))
			.get("activityId")
			.asLong();

		assertThat(theirs).isNotEqualTo(mine);
		assertThat(materials()).isEqualTo(2);
	}

	@Test
	void receiptIsNotReturnedToSomeoneWhoLostAccess() throws Exception {
		String body = material("자료", null);
		createMaterial(this.professor, "key-7", body).andExpect(status().isCreated());
		this.jdbc.sql("DELETE FROM course_instructors WHERE course_id = :c AND user_id = :u")
			.param("c", this.course)
			.param("u", this.professorId)
			.update();

		createMaterial(this.professor, "key-7", body).andExpect(status().isNotFound());
	}

	@Test
	void summaryJobRequestReplaysTheSameJob() throws Exception {
		long file = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		long otherFile = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(file));
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(otherFile));
		String path = "/api/courses/" + this.course + "/files/" + file + "/summary-jobs";

		String first = perform(this.professor, "job-1", path, null).andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String second = perform(this.professor, "job-1", path, null).andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		perform(this.professor, "job-1", "/api/courses/" + this.course + "/files/" + otherFile + "/summary-jobs", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));

		assertThat(second).isEqualTo(first);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM summary_jobs WHERE file_id IN (:f)")
			.param("f", List.of(file, otherFile))
			.query(Long.class)
			.single()).isEqualTo(1);
		this.jdbc.sql("UPDATE summary_jobs SET job_state = 'cancelled' WHERE file_id = :f").param("f", file).update();
	}

	@Test
	void expiredEntriesArePurgedByTwoWorkersAtOnceAndTheirKeysCanBeUsedAgain() throws Exception {
		for (int i = 0; i < 5; i++) {
			ledgerEntry(this.professorId, "old-" + i, "INTERVAL -1 SECOND");
		}
		ledgerEntry(this.professorId, "fresh", "INTERVAL 1 HOUR");

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> purgeOnce = () -> {
			start.await();
			return this.purge.purgeExpired();
		};
		Future<Integer> one = pool.submit(purgeOnce);
		Future<Integer> two = pool.submit(purgeOnce);
		start.countDown();
		one.get(30, TimeUnit.SECONDS);
		two.get(30, TimeUnit.SECONDS);
		pool.shutdown();

		assertThat(this.jdbc.sql("SELECT idempotency_key FROM idempotency_ledger WHERE principal_user_id = :u")
			.param("u", this.professorId)
			.query(String.class)
			.list()).containsExactly("fresh");

		// 지우기 전에 보존기한이 지난 key가 다시 오면 새 명령으로 실행한다.
		ledgerEntry(this.professorId, "reused", "INTERVAL -1 SECOND");
		createMaterial(this.professor, "reused", material("다시 쓴 key", null)).andExpect(status().isCreated());
		assertThat(materials()).isEqualTo(1);
	}

	private ResultActions createMaterial(String session, String key, String body) throws Exception {
		return perform(session, key, "/api/courses/" + this.course + "/materials", body);
	}

	private ResultActions perform(String session, String key, String path, String json) throws Exception {
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		var request = MockMvcRequestBuilders.post(path)
			.cookie(new Cookie("ASTRA_SESSION", session), csrf)
			.header("X-XSRF-TOKEN", csrf.getValue())
			.header(Idempotency.HEADER, key);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return this.mvc.perform(request);
	}

	private static String material(String title, Long uploadId) {
		return "{\"weekNo\":2,\"title\":\"" + title + "\""
				+ (uploadId != null ? ",\"uploadIds\":[" + uploadId + "]" : "") + "}";
	}

	private long materials() {
		return this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
	}

	private long ledger(long userId, String key) {
		return this.jdbc.sql("SELECT COUNT(*) FROM idempotency_ledger WHERE principal_user_id = :u AND idempotency_key = :k")
			.param("u", userId)
			.param("k", key)
			.query(Long.class)
			.single();
	}

	private void ledgerEntry(long userId, String key, String expiresIn) {
		this.data.insert("""
				INSERT INTO idempotency_ledger (principal_user_id, operation, idempotency_key, request_fingerprint,
				                                entry_state, receipt_json, expires_at, completed_at)
				VALUES (:u, 'material.create', :k, REPEAT('0', 64), 'completed', '{}',
				        DATE_ADD(CURRENT_TIMESTAMP(3), %s), CURRENT_TIMESTAMP(3))
				""".formatted(expiresIn), Map.of("u", userId, "k", key));
	}

	private void setUploadState(long uploadId, String state) {
		this.jdbc.sql("UPDATE upload_sessions SET upload_state = :s WHERE upload_id = :id")
			.param("s", state)
			.param("id", uploadId)
			.update();
	}

	// 다른 connection의 transaction으로 업로드 행을 잠근다. 닫을 때 rollback으로 푼다.
	private Connection lockUpload(long uploadId) throws Exception {
		Connection connection = this.dataSource.getConnection();
		connection.setAutoCommit(false);
		try (PreparedStatement statement = connection
			.prepareStatement("SELECT upload_id FROM upload_sessions WHERE upload_id = ? FOR UPDATE")) {
			statement.setLong(1, uploadId);
			statement.executeQuery().close();
		}
		return connection;
	}

	// 아직 commit하지 않은 예약은 READ UNCOMMITTED로만 보인다.
	private void awaitReservation(long userId, String key) throws Exception {
		try (Connection connection = this.dataSource.getConnection()) {
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
			for (int i = 0; i < 100; i++) {
				try (PreparedStatement statement = connection.prepareStatement(
						"SELECT entry_state FROM idempotency_ledger WHERE principal_user_id = ? AND idempotency_key = ?")) {
					statement.setLong(1, userId);
					statement.setString(2, key);
					try (ResultSet rs = statement.executeQuery()) {
						if (rs.next() && "reserved".equals(rs.getString(1))) {
							return;
						}
					}
				}
				Thread.sleep(50);
			}
			throw new AssertionError("reservation not seen");
		}
	}

	private static String body(MockHttpServletResponse response) {
		return new String(response.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
	}

}
