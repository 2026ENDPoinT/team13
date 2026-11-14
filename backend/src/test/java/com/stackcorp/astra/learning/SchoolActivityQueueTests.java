package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolAssignment;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolDownload;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;
import com.stackcorp.astra.identity.api.SchoolLoginSucceeded;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;
import com.stackcorp.astra.identity.session.SchoolSessionRegistry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 수업 시작처럼 로그인이 몰릴 때의 학교 강의실 가져오기 줄(ADR-0042). 자리를 다 쓰면 줄에서 기다렸다 시작하고, 줄에서 기다리는 사이 로그아웃한
 * 학생의 가져오기는 버리고, 줄까지 가득 차 건너뛴 학생은 다음 로그인이 가져온다. 로그인 응답은 줄 길이와 상관없이 짧다. 설정을 바꿔 쓰려고
 * component를 직접 만들고 로그인 사건을 직접 넘긴다.
 */
class SchoolActivityQueueTests extends IntegrationTest {

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SchoolActivities store;

	@Autowired
	MemberCourses memberCourses;

	@Autowired
	SchoolNotices notices;

	@Autowired
	SchoolSessionRegistry registry;

	final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	final CountDownLatch gate = new CountDownLatch(1);

	long course;

	String key;

	SchoolActivityImport imports;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.key = "QUEUE" + this.course;
		this.jdbc.sql("UPDATE courses SET legacy_kjkey = :key WHERE course_id = :id")
			.param("key", this.key)
			.param("id", this.course)
			.update();
	}

	@AfterEach
	void stop() {
		this.gate.countDown();
		if (this.imports != null) {
			this.imports.destroy();
		}
	}

	@Test
	void tenSignInsBehindFourBusySlotsAllCompleteTheirImports() throws Exception {
		this.imports = imports(4, 200, Duration.ofSeconds(3), Duration.ofSeconds(60));
		List<Long> students = new ArrayList<>();
		List<School> schools = new ArrayList<>();
		ExecutorService busySignIns = Executors.newFixedThreadPool(4);
		try {
			for (int i = 0; i < 4; i++) {
				School school = new School(this.gate);
				long student = student();
				busySignIns.execute(() -> this.imports.on(login(student, school)));
				students.add(student);
				schools.add(school);
			}
			until(() -> schools.stream().allMatch((s) -> s.reads.get() == 1));

			for (int i = 0; i < 10; i++) {
				School school = new School(null);
				long started = System.nanoTime();
				students.add(signIn(school));
				// 줄에 선 로그인은 wait(3초)를 기다리지 않는다.
				assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
				schools.add(school);
			}
			assertThat(gauge("astra.learning.school.imports.queued")).isEqualTo(10);
			assertThat(schools.subList(4, 14)).allMatch((s) -> s.reads.get() == 0);

			this.gate.countDown();

			until(() -> students.stream().allMatch((student) -> imported(student) == 1));
		}
		finally {
			busySignIns.shutdownNow();
		}
		assertThat(schools).allMatch((s) -> s.reads.get() == 1);
		assertThat(gauge("astra.learning.school.imports.queued")).isZero();
		assertThat(this.meters.find("astra.learning.school.imports.skipped").counters()).isEmpty();
	}

	@Test
	void aQueuedImportOfAStudentWhoSignsOutIsDropped() throws Exception {
		this.imports = imports(1, 10, Duration.ZERO, Duration.ofSeconds(60));
		School busy = new School(this.gate);
		long first = signIn(busy);
		until(() -> busy.reads.get() == 1);
		School leaving = new School(null);
		long sessionId = Long.MAX_VALUE - this.course;
		assertThat(this.registry.keep(sessionId, leaving)).isTrue();
		long student = signIn(leaving);

		// 로그아웃·session 만료는 registry가 학교 session을 닫는다(SessionStore.revoke, sweep).
		this.registry.forget(sessionId);
		this.gate.countDown();

		until(() -> skipped("closed") == 1);
		assertThat(leaving.reads.get()).isZero();
		assertThat(imported(first)).isEqualTo(1);
		// 간격을 적지 않았으므로 다음 로그인이 가져온다.
		assertThat(claimed(student)).isFalse();
	}

	@Test
	void aSignInSkippedOnAFullQueueIsImportedByTheNextSignIn() throws Exception {
		this.imports = imports(1, 1, Duration.ZERO, Duration.ofSeconds(60));
		School busy = new School(this.gate);
		signIn(busy);
		until(() -> busy.reads.get() == 1);
		long queued = signIn(new School(null));
		long student = student();
		School skipped = new School(null);

		this.imports.on(login(student, skipped));

		assertThat(skipped("queue_full")).isEqualTo(1);
		assertThat(claimed(student)).isFalse();
		this.gate.countDown();
		until(() -> imported(queued) == 1);
		assertThat(skipped.reads.get()).isZero();

		School again = new School(null);
		this.imports.on(login(student, again));

		until(() -> imported(student) == 1);
		assertThat(again.reads.get()).isEqualTo(1);
	}

	@Test
	void signInWaitsOnlyForAnImportThatHasStarted() throws Exception {
		this.imports = imports(1, 10, Duration.ofSeconds(1), Duration.ofSeconds(60));
		long started = System.nanoTime();
		signIn(new School(this.gate));
		// 빈 자리에서 시작했지만 wait(1초) 안에 끝나지 않았다.
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(900))
			.isLessThan(Duration.ofSeconds(3));

		started = System.nanoTime();
		long behind = signIn(new School(null));
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
		this.gate.countDown();
		until(() -> imported(behind) == 1 && gauge("astra.learning.school.imports.running") == 0);

		// 빈 자리에서 바로 시작해 wait 안에 끝난 가져오기는 로그인 응답 전에 끝나 있다.
		long student = signIn(new School(null));
		assertThat(imported(student)).isEqualTo(1);
	}

	@Test
	void aQueuedImportWhoseBudgetRanOutIsDropped() throws Exception {
		this.imports = imports(1, 10, Duration.ZERO, Duration.ofSeconds(1));
		School busy = new School(this.gate);
		signIn(busy);
		until(() -> busy.reads.get() == 1);
		School late = new School(null);
		long student = signIn(late);
		Thread.sleep(1_100);

		this.gate.countDown();

		until(() -> skipped("budget") == 1);
		assertThat(late.reads.get()).isZero();
		assertThat(claimed(student)).isFalse();
	}

	private SchoolActivityImport imports(int concurrency, int queue, Duration wait, Duration budget) {
		return new SchoolActivityImport(new SchoolActivityProperties(true, wait, budget, Duration.ofSeconds(8), 20,
				Duration.ofMinutes(10), concurrency, queue), this.store, this.memberCourses, this.notices, this.meters);
	}

	private long signIn(School school) {
		long student = student();
		this.imports.on(login(student, school));
		return student;
	}

	private long student() {
		long student = this.data.user("STUDENT");
		this.data.enroll(this.course, student);
		return student;
	}

	private SchoolLoginSucceeded login(long student, School school) {
		return new SchoolLoginSucceeded(student, List.of(new SchoolCourse(this.key, "학교 과목", true, 2026, "3", "100001",
				"01", "담당교수", List.of(), BigDecimal.ONE, null, null, null)), school);
	}

	/** @return 끝난 가져오기가 읽은 과목 수. 끝나지 않았으면 -1 */
	private int imported(long student) {
		return this.jdbc.sql("""
				SELECT courses_read FROM school_activity_imports WHERE user_id = :user AND finished_at IS NOT NULL
				""").param("user", student).query(Integer.class).optional().orElse(-1);
	}

	private boolean claimed(long student) {
		return this.jdbc.sql("SELECT COUNT(*) FROM school_activity_imports WHERE user_id = :user")
			.param("user", student)
			.query(Integer.class)
			.single() > 0;
	}

	private double gauge(String name) {
		Gauge gauge = this.meters.get(name).gauge();
		return gauge.value();
	}

	private double skipped(String reason) {
		Counter counter = this.meters.find("astra.learning.school.imports.skipped").tag("reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

	private static void until(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition within 20s").isLessThan(deadline);
			Thread.sleep(10);
		}
	}

	/** 학교 강의실 대역. gate가 있으면 강의실 화면 읽기가 그 latch가 열릴 때까지 걸린다. */
	static final class School implements SchoolCourseRooms {

		final AtomicInteger reads = new AtomicInteger();

		private final CountDownLatch gate;

		private final AtomicBoolean claimed = new AtomicBoolean();

		private volatile boolean open = true;

		School(CountDownLatch gate) {
			this.gate = gate;
		}

		@Override
		public boolean claim() {
			return this.claimed.compareAndSet(false, true);
		}

		@Override
		public boolean isOpen() {
			return this.open;
		}

		@Override
		public SchoolCourseRoom read(String courseKey, Duration requestTimeout, Duration remaining) {
			this.reads.incrementAndGet();
			if (this.gate != null) {
				try {
					this.gate.await();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			return new SchoolCourseRoom(List.of(new SchoolWeek(1, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7),
					List.of(new SchoolActivity("report", null, "901", "학교 과제", null, null, false, null, null, false,
							false, "https://eclass.example.ac.kr/connect?SEQ=901")))));
		}

		@Override
		public List<SchoolNotice> notices(String courseKey, Duration requestTimeout, Duration remaining) {
			return List.of();
		}

		@Override
		public void close() {
			this.open = false;
		}

		@Override
		public List<SchoolFile> files(String courseKey, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SchoolPost material(String courseKey, String itemId, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SchoolPost notice(String courseKey, String itemId, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SchoolAssignment assignment(String courseKey, String itemId, Duration requestTimeout,
				Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SchoolDownload download(String courseKey, SchoolFile file, long maxBytes, Path target,
				Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SchoolVideo video(String courseKey, String itemId, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SchoolSyllabus syllabus(String courseKey, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<SchoolAttendanceWeek> attendance(String courseKey, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<SchoolGradeItem> grades(String courseKey, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

	}

}
