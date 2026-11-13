package com.stackcorp.astra.learning;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginSucceeded;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;

/**
 * 학번·학교 비밀번호 로그인 뒤 학교 강의실의 주차·학습활동 목록을 가져온다(ADR-0042). 로그인 transaction이 commit되고
 * {@code catalog}가 과목과 수강을 맞춘 다음 같은 thread에서 받는다.
 * <ul>
 * <li>학교 session은 로그인 요청 안에만 있으므로 그 session으로 과목마다 강의실 화면을 차례로 읽는다(과목이 session에 묶이는 구조라
 * 동시에 읽지 않는다). 읽은 과목은 바로 저장한다.</li>
 * <li>로그인 응답은 {@code wait}까지만 기다린다. 나머지는 이 component의 thread가 이어서 읽고, 전체는 {@code budget}을 넘지 않는다.
 * 끝나면(실패해도) 학교 session을 버린다. 학교 session은 이 작업의 메모리에만 있다.</li>
 * <li>과목 하나가 실패하면 로그(과목 ID와 이유 코드뿐)만 남기고 다음 과목으로 간다. 가져오기는 로그인을 실패시키지 않는다.</li>
 * <li>같은 학생이 {@code interval} 안에 다시 로그인하면 학교 강의실을 읽지 않는다. 동시에 도는 가져오기가 {@code concurrency}를 넘으면 그
 * 로그인은 가져오지 않는다.</li>
 * <li>학생으로 수강 중인 과목 가운데 학교 메인 화면에 있던 과목만 읽는다. 담당 과목, 학교 화면에 없는 과목은 읽지 않는다.</li>
 * </ul>
 */
@Component
class SchoolActivityImport implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SchoolActivityImport.class);

	private final SchoolActivityProperties properties;

	private final SchoolActivities store;

	private final MemberCourses memberCourses;

	private final ThreadPoolExecutor executor;

	SchoolActivityImport(SchoolActivityProperties properties, SchoolActivities store, MemberCourses memberCourses) {
		this.properties = properties;
		this.store = store;
		this.memberCourses = memberCourses;
		this.executor = new ThreadPoolExecutor(properties.concurrency(), properties.concurrency(), 30, TimeUnit.SECONDS,
				new ArrayBlockingQueue<>(properties.concurrency()),
				Thread.ofPlatform().daemon().name("school-activity-import-", 1).factory(),
				new ThreadPoolExecutor.AbortPolicy());
		this.executor.allowCoreThreadTimeOut(true);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Order(SchoolLoginSucceeded.LEARNING_ORDER)
	public void on(SchoolLoginSucceeded event) {
		SchoolCourseRooms rooms = event.rooms();
		if (!this.properties.enabled() || rooms == null || event.courses().isEmpty() || !rooms.claim()) {
			return;
		}
		long started = System.nanoTime();
		Future<?> job;
		try {
			job = this.executor.submit(() -> run(event.userId(), event.courses(), rooms, started));
		}
		catch (RejectedExecutionException ex) {
			rooms.close();
			log.warn("School activity import skipped: reason=busy");
			return;
		}
		try {
			job.get(this.properties.signInWait().toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			// 나머지는 뒤에서 이어 간다. 로그인 응답은 지금 나간다.
		}
		catch (ExecutionException ex) {
			log.warn("School activity import failed: type={}", ex.getCause().getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	void run(long userId, List<SchoolCourse> schoolCourses, SchoolCourseRooms rooms, long started) {
		long deadline = started + this.properties.budget().toNanos();
		int read = 0;
		int failed = 0;
		boolean claimed = false;
		try {
			claimed = this.store.claim(userId, this.properties.interval().toSeconds());
			if (!claimed) {
				return;
			}
			Map<String, Long> mine = new LinkedHashMap<>();
			for (MemberCourse course : this.memberCourses.of(userId)) {
				if (course.role() == CourseRole.STUDENT && course.key() != null) {
					mine.put(course.key(), course.courseId());
				}
			}
			List<Long> targets = schoolCourses.stream()
				.map(SchoolCourse::key)
				.filter(mine::containsKey)
				.map(mine::get)
				.distinct()
				.limit(this.properties.maxCourses())
				.toList();
			Map<Long, String> keys = new LinkedHashMap<>();
			mine.forEach((key, id) -> keys.putIfAbsent(id, key));
			for (long courseId : targets) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
					int left = targets.size() - read - failed;
					failed += left;
					log.warn("School activity import stopped: reason=budget left={}", left);
					break;
				}
				SchoolCourseRoom room;
				try {
					room = rooms.read(keys.get(courseId), this.properties.requestTimeout(), Duration.ofNanos(remaining));
				}
				catch (SchoolLoginException ex) {
					failed++;
					log.warn("School course room read failed: course={} reason={}", courseId, ex.getMessage());
					continue;
				}
				catch (RuntimeException ex) {
					failed++;
					log.warn("School course room read failed: course={} type={}", courseId, ex.getClass().getSimpleName());
					continue;
				}
				try {
					this.store.save(courseId, userId, room);
					read++;
				}
				catch (RuntimeException ex) {
					failed++;
					log.warn("School course room save failed: course={} type={}", courseId, ex.getClass().getSimpleName());
				}
			}
			log.info("School activity import finished: user={} read={} failed={} ms={}", userId, read, failed,
					(System.nanoTime() - started) / 1_000_000);
		}
		catch (RuntimeException ex) {
			log.warn("School activity import failed: type={}", ex.getClass().getSimpleName());
		}
		finally {
			rooms.close();
			if (claimed) {
				try {
					this.store.finish(userId, read, failed);
				}
				catch (RuntimeException ex) {
					log.warn("School activity import record failed: type={}", ex.getClass().getSimpleName());
				}
			}
		}
	}

	@Override
	public void destroy() {
		this.executor.shutdownNow();
	}

}
