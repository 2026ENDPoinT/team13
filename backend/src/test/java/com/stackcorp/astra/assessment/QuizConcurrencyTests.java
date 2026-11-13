package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 실제 thread로 겹친 시작, 제출, worker 마감 처리. 이웃한 사용자와 응시를 함께 돌려도 학생마다 응시 하나, 종료 한 번이다(SC-05).
 */
class QuizConcurrencyTests extends QuizTestSupport {

	private static final int THREADS = 6;

	@Autowired
	QuizAttemptCloser closer;

	@Test
	void concurrentStartsCreateOneAttemptPerStudent() throws Exception {
		long id = quiz("\"timeLimitMinutes\":30");
		List<Client> clients = List.of(this.student, this.classmate);
		List<MockHttpServletResponse> responses = race(THREADS * 2,
				(n) -> clients.get(n % 2).post(attempts(this.course, id)).andReturn().getResponse());
		for (int who = 0; who < 2; who++) {
			List<Long> ids = new ArrayList<>();
			int created = 0;
			for (int n = who; n < responses.size(); n += 2) {
				MockHttpServletResponse response = responses.get(n);
				assertThat(response.getStatus()).isIn(200, 201);
				created += response.getStatus() == 201 ? 1 : 0;
				ids.add(ApiClient.json(response).get("attemptId").asLong());
			}
			assertThat(created).isEqualTo(1);
			assertThat(ids).containsOnly(ids.getFirst());
		}
		assertThat(attemptCount(id, this.studentId)).isEqualTo(1);
		assertThat(attemptCount(id, this.classmateId)).isEqualTo(1);
	}

	@Test
	void concurrentSubmitsCloseEachAttemptOnce() throws Exception {
		long id = quiz("");
		List<Client> clients = List.of(this.student, this.classmate);
		List<Long> attemptIds = List.of(start(this.student, id), start(this.classmate, id));
		for (int who = 0; who < 2; who++) {
			clients.get(who)
				.putJson(answer(this.course, id, attemptIds.get(who), questionId(id, 0)), choice(0, optionId(id, 0, 0)));
		}
		List<MockHttpServletResponse> responses = race(THREADS * 2,
				(n) -> clients.get(n % 2)
					.submit(submitPath(this.course, id, attemptIds.get(n % 2)), "key-" + n, "{}")
					.andReturn()
					.getResponse());
		for (MockHttpServletResponse response : responses) {
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat(ApiClient.json(response).get("status").asString()).isEqualTo("graded");
		}
		for (long attemptId : attemptIds) {
			assertThat(auditCount("quiz.attempt.submit", attemptId)).isEqualTo(1);
			assertThat(this.jdbc.sql("SELECT COUNT(*) FROM quiz_attempt_answers WHERE quiz_attempt_id = :a")
				.param("a", attemptId)
				.query(Long.class)
				.single()).isEqualTo(3);
		}
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.activity_id = :activity AND s.score = 2
				""").param("activity", activityOfQuiz(id)).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void twoWorkersCloseEachExpiredAttemptOnceWithTheSavedAnswers() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("\"timeLimitMinutes\":30");
		window(id, null, t.plusHours(2));
		List<Client> clients = new ArrayList<>(List.of(this.student, this.classmate));
		for (int i = 0; i < 4; i++) {
			long user = this.data.user("STUDENT");
			this.data.enroll(this.course, user);
			clients.add(client(user));
		}
		List<Long> attemptIds = new ArrayList<>();
		for (int i = 0; i < clients.size(); i++) {
			long a = start(clients.get(i), id);
			attemptIds.add(a);
			clients.get(i).putJson(answer(this.course, id, a, questionId(id, 0)), choice(0, optionId(id, 0, i % 2)));
		}
		// 마지막 학생은 마감이 더 늦다. worker는 그 응시를 닫지 않는다.
		long later = attemptIds.getLast();
		deadline(later, t.plusHours(1));

		this.clock.set(t.plusMinutes(30));
		List<Integer> closed = race(2, (n) -> this.closer.closeExpired());
		assertThat(closed.stream().mapToInt(Integer::intValue).sum()).isGreaterThanOrEqualTo(attemptIds.size() - 1);
		for (int i = 0; i < attemptIds.size() - 1; i++) {
			long a = attemptIds.get(i);
			assertThat(auditCount("quiz.attempt.finalize", a)).isEqualTo(1);
			assertThat(this.jdbc.sql("""
					SELECT CONCAT(status, ':', finish_reason, ':', total_score, ':', submitted_at = deadline_at)
					  FROM quiz_attempts WHERE quiz_attempt_id = :a
					""").param("a", a).query(String.class).single())
				.isEqualTo("graded:deadline:" + (i % 2 == 0 ? "2.00" : "0.00") + ":1");
		}
		assertThat(this.jdbc.sql("SELECT status FROM quiz_attempts WHERE quiz_attempt_id = :a")
			.param("a", later)
			.query(String.class)
			.single()).isEqualTo("in_progress");
		// 닫힌 뒤에는 저장을 받지 않는다.
		clients.getFirst()
			.putJson(answer(this.course, id, attemptIds.getFirst(), questionId(id, 1)), choice(0, optionId(id, 1, 0)))
			.andReturn();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM quiz_attempt_answer_options o JOIN quiz_attempt_answers a "
				+ "ON a.quiz_attempt_answer_id = o.quiz_attempt_answer_id WHERE a.quiz_attempt_id = :a")
			.param("a", attemptIds.getFirst())
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	private <T> List<T> race(int threads, Work<T> work) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				Callable<T> call = () -> {
					start.await();
					return work.run(n);
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	private interface Work<T> {

		T run(int n) throws Exception;

	}

}
