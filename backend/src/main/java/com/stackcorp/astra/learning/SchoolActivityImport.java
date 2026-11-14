package com.stackcorp.astra.learning;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginSucceeded;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 학번·학교 비밀번호 로그인 뒤 학교 강의실의 주차·학습활동 목록을 가져온다(ADR-0042). 로그인 transaction이 commit되고 DB 연결을
 * 돌려준 뒤 {@code catalog}가 과목과 수강을 맞춘 다음 같은 thread에서 받는다.
 * <ul>
 * <li>로그인으로 열린 학교 session으로 과목마다 강의실 화면을 차례로 읽는다(과목이 session에 묶이는 구조라 동시에 읽지 않는다). 읽은
 * 과목은 바로 저장한다.</li>
 * <li>가져오기는 동시에 {@code concurrency}개까지 돈다. 자리가 없으면 {@code queue-capacity}개까지 줄에서 기다렸다가 자리가 나면
 * 들어온 순서대로 시작한다. 줄도 가득 차면 그 로그인은 가져오지 않는다. 같은 학생의 가져오기 간격({@code interval})은 가져오기가 실제로
 * 시작할 때만 적으므로, 건너뛴 학생은 다음 로그인이 가져온다.</li>
 * <li>로그인 응답은 빈 자리에서 바로 시작한 가져오기만 {@code wait}까지 기다린다. 줄에 선 가져오기는 기다리지 않으므로 응답 시간이 줄
 * 길이에 따라 늘지 않는다. 나머지는 이 component의 thread가 이어서 읽고, 전체는 로그인부터 {@code budget}을 넘지 않는다.</li>
 * <li>학교 session은 닫지 않는다. identity가 STack session에 붙여 두고 그 session이 끝날 때(로그아웃, 폐기, 만료) 닫는다(ADR-0043).
 * 줄에서 기다리는 사이에 닫혔거나 {@code budget}이 지났으면 시작하지 않고 버린다.</li>
 * <li>과목 하나가 실패하면 로그(과목 ID와 이유 코드뿐)만 남기고 다음 과목으로 간다. 가져오기는 로그인을 실패시키지 않는다.</li>
 * <li>같은 학생이 {@code interval} 안에 가져오기를 시작했으면 학교 강의실을 읽지 않는다.</li>
 * <li>학생으로 수강 중인 과목 가운데 학교 메인 화면에 있던 과목만 읽는다. 담당 과목, 학교 화면에 없는 과목은 읽지 않는다.</li>
 * <li>과목마다 강의실 화면 다음에 공지 목록(학교 쪽 읽음을 바꾸지 않는 목록 화면)을 읽어 둔다(ADR-0043). 안 읽은 공지 수가 이것으로
 * 정해진다. 공지 목록을 읽지 못해도 그 과목의 가져오기는 실패로 세지 않는다.</li>
 * </ul>
 * 지표: {@code astra.learning.school.imports.queued}(줄에서 기다리는 수), {@code astra.learning.school.imports.running}(도는 수),
 * {@code astra.learning.school.imports.skipped{reason}}(queue_full, closed, budget, shutdown).
 */
@Component
class SchoolActivityImport implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(SchoolActivityImport.class);

	private final SchoolActivityProperties properties;

	private final SchoolActivities store;

	private final MemberCourses memberCourses;

	private final ThreadPoolExecutor executor;

	private final SchoolNotices notices;

	private final MeterRegistry meters;

	/** 과목 저장을 과목마다 차례로 하는 잠금(과목 ID로 나눈다). */
	private final ReentrantLock[] courseLocks = new ReentrantLock[64];

	/** 돌거나 줄에서 기다리는 가져오기 수. 자리와 줄의 상한을 이것으로 지킨다. */
	private final AtomicInteger pending = new AtomicInteger();

	SchoolActivityImport(SchoolActivityProperties properties, SchoolActivities store, MemberCourses memberCourses,
			SchoolNotices notices, MeterRegistry meters) {
		this.properties = properties;
		this.store = store;
		this.memberCourses = memberCourses;
		this.notices = notices;
		this.meters = meters;
		for (int i = 0; i < this.courseLocks.length; i++) {
			this.courseLocks[i] = new ReentrantLock();
		}
		// 줄의 상한은 pending으로 지키므로 executor의 줄은 묶지 않는다. thread는 concurrency개뿐이라 들어온 순서대로 시작한다.
		this.executor = new ThreadPoolExecutor(properties.concurrency(), properties.concurrency(), 30, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon().name("school-activity-import-", 1).factory(),
				new ThreadPoolExecutor.AbortPolicy());
		this.executor.allowCoreThreadTimeOut(true);
		Gauge.builder("astra.learning.school.imports.queued", this.executor, (e) -> e.getQueue().size())
			.description("자리가 날 때까지 줄에서 기다리는 학교 강의실 가져오기 수")
			.baseUnit("imports")
			.strongReference(true)
			.register(meters);
		Gauge.builder("astra.learning.school.imports.running", this.executor, ThreadPoolExecutor::getActiveCount)
			.description("지금 도는 학교 강의실 가져오기 수")
			.baseUnit("imports")
			.strongReference(true)
			.register(meters);
	}

	@EventListener
	@Order(SchoolLoginSucceeded.LEARNING_ORDER)
	public void on(SchoolLoginSucceeded event) {
		SchoolCourseRooms rooms = event.rooms();
		if (!this.properties.enabled() || rooms == null || event.courses().isEmpty()) {
			return;
		}
		long signedIn = System.nanoTime();
		int ahead = this.pending.incrementAndGet();
		if (ahead > this.properties.concurrency() + this.properties.queueCapacity()) {
			this.pending.decrementAndGet();
			// 간격을 적지 않았으므로 다음 로그인이 가져온다. 학교 session은 STack session에 붙어 있으므로 닫지 않는다.
			skipped("queue_full");
			return;
		}
		Future<?> job;
		try {
			job = this.executor.submit(() -> {
				try {
					start(event.userId(), event.courses(), rooms, signedIn);
				}
				finally {
					this.pending.decrementAndGet();
				}
			});
		}
		catch (RejectedExecutionException ex) {
			this.pending.decrementAndGet();
			skipped("shutdown");
			return;
		}
		if (ahead > this.properties.concurrency()) {
			// 줄에 섰다. 로그인 응답은 기다리지 않으므로 응답 시간이 줄 길이에 따라 늘지 않는다.
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

	/** 줄에서 기다리는 사이에 학교 session이 닫혔거나 budget이 지났으면 간격을 적지 않고 버린다. */
	private void start(long userId, List<SchoolCourse> courses, SchoolCourseRooms rooms, long signedIn) {
		if (System.nanoTime() - signedIn >= this.properties.budget().toNanos()) {
			skipped("budget");
			return;
		}
		if (!rooms.isOpen()) {
			skipped("closed");
			return;
		}
		run(userId, courses, rooms, signedIn);
	}

	private void skipped(String reason) {
		log.warn("School activity import skipped: reason={}", reason);
		Counter.builder("astra.learning.school.imports.skipped")
			.description("시작하지 못하고 건너뛴 학교 강의실 가져오기")
			.tag("reason", reason)
			.register(this.meters)
			.increment();
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
					saveCourse(courseId, () -> this.store.save(courseId, userId, room));
					read++;
				}
				catch (RuntimeException ex) {
					failed++;
					log.warn("School course room save failed: course={} type={}", courseId, ex.getClass().getSimpleName());
				}
				readNotices(rooms, keys.get(courseId), courseId, userId, deadline);
			}
			log.info("School activity import finished: user={} read={} failed={} ms={}", userId, read, failed,
					(System.nanoTime() - started) / 1_000_000);
		}
		catch (RuntimeException ex) {
			log.warn("School activity import failed: type={}", ex.getClass().getSimpleName());
		}
		finally {
			// 학교 session은 닫지 않는다(STack session에 붙어 있다).
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

	/** 과목의 학교 공지 목록(목록 화면)을 읽어 둔다. 실패는 로그만 남긴다. */
	private void readNotices(SchoolCourseRooms rooms, String courseKey, long courseId, long userId, long deadline) {
		long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			return;
		}
		try {
			List<SchoolNotice> listed = rooms.notices(courseKey, this.properties.requestTimeout(),
					Duration.ofNanos(remaining));
			saveCourse(courseId, () -> this.notices.save(courseId, userId, listed));
		}
		catch (SchoolLoginException ex) {
			log.warn("School notice list read failed: course={} reason={}", courseId, ex.getMessage());
		}
		catch (RuntimeException ex) {
			log.warn("School notice list read failed: course={} type={}", courseId, ex.getClass().getSimpleName());
		}
	}

	/**
	 * 수업 시작 때처럼 같은 과목 학생들의 가져오기가 동시에 그 과목을 맞추면 MySQL이 서로 교착한다(같은 주차·활동 행을 고치고 화면에 없는 행을
	 * 지운다). 이 process 안에서는 과목마다 차례로 저장하고, 다른 process와의 교착은 다시 실행한다. 저장 하나는 짧아 기다림도 짧다.
	 */
	private void saveCourse(long courseId, Runnable save) {
		ReentrantLock lock = this.courseLocks[Math.floorMod(Long.hashCode(courseId), this.courseLocks.length)];
		lock.lock();
		try {
			DeadlockRetry.run(save);
		}
		finally {
			lock.unlock();
		}
	}

	@Override
	public void destroy() {
		this.executor.shutdownNow();
	}

}
