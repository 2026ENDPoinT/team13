package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolDownload;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.session.SchoolSessionRegistry;

import tools.jackson.databind.JsonNode;

/** 학교 강의실 자료를 학생의 학교 session으로 필요할 때 읽고 받은 파일을 과목 파일로 함께 쓰기(ADR-0043). */
class SchoolContentTests extends IntegrationTest {

	static final String KEY = "A2026399999901001";

	static final byte[] PDF = TestPdfs.pdf(2);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SchoolActivities activities;

	@Autowired
	SchoolSessionRegistry registry;

	long course;

	long studentId;

	FakeSchool school;

	ApiClient student;

	ApiClient other;

	ApiClient professor;

	ApiClient outsider;

	long studentSession;

	long material;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.jdbc.sql("UPDATE courses SET legacy_kjkey = :key WHERE course_id = :id")
			.param("key", KEY + this.course)
			.param("id", this.course)
			.update();
		long prof = this.data.user("PROFESSOR");
		this.studentId = this.data.user("STUDENT");
		long otherId = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, this.studentId);
		this.data.enroll(this.course, otherId);
		this.student = new ApiClient(this.mvc, this.data.session(this.studentId));
		this.studentSession = this.jdbc.sql("SELECT MAX(session_id) FROM user_sessions WHERE user_id = :user")
			.param("user", this.studentId)
			.query(Long.class)
			.single();
		this.other = new ApiClient(this.mvc, this.data.session(otherId));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.activities.save(this.course, this.studentId, new SchoolCourseRoom(List.of(new SchoolWeek(3,
				LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 21),
				List.of(new SchoolActivity("lecture_material", null, "31", "학교 자료", LocalDateTime.of(2026, 9, 15, 0, 0),
						null, false, null, null, false, true, "https://eclass.example.ac.kr/connect?SEQ=31"))))));
		this.material = this.jdbc
			.sql("SELECT school_activity_id FROM school_activities WHERE course_id = :course AND school_item_id = '31'")
			.param("course", this.course)
			.query(Long.class)
			.single();
		this.school = new FakeSchool(KEY + this.course);
		assertThat(this.registry.keep(this.studentSession, this.school)).isTrue();
	}

	@Test
	void aStudentSeesSchoolMaterialsOpensOneAndItsFileIsKeptForTheCourse() throws Exception {
		JsonNode items = materials(this.student);
		JsonNode item = items.get(items.size() - 1);
		assertThat(item.path("activityId").asText()).isEqualTo("school-" + this.material);
		assertThat(item.path("origin").asText()).isEqualTo("school");
		assertThat(item.path("weekNo").asInt()).isEqualTo(3);
		JsonNode listed = item.path("files").get(0);
		assertThat(listed.path("name").asText()).isEqualTo("슬라이드.pdf");
		assertThat(listed.path("copied").asBoolean()).isFalse();
		assertThat(listed.path("pdf").asBoolean()).isTrue();
		String schoolFileId = listed.path("fileId").asText();
		assertThat(schoolFileId).startsWith("school-file-");
		// 목록은 간격 안에 다시 읽지 않고, 상세·파일은 목록을 열었다고 읽지 않는다.
		materials(this.student);
		assertThat(this.school.lists.get()).isEqualTo(1);
		assertThat(this.school.details.get()).isZero();
		assertThat(this.school.downloads.get()).isZero();

		JsonNode detail = ApiClient.json(this.student
			.get("/api/courses/" + this.course + "/activities/school-" + this.material)
			.andExpect(status().isOk()));
		assertThat(detail.path("type").asText()).isEqualTo("lecture_material");
		assertThat(detail.path("description").asText()).isEqualTo("첫 줄\n둘째 줄");
		assertThat(detail.path("author").asText()).isEqualTo("담당 교수");
		assertThat(detail.path("files").size()).isEqualTo(1);
		assertThat(this.school.details.get()).isEqualTo(1);

		String location = this.student.get("/api/courses/" + this.course + "/files/" + schoolFileId + "/content")
			.andExpect(status().isSeeOther())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		assertThat(location).matches("/api/courses/" + this.course + "/files/[0-9]+/content");
		byte[] body = this.student.get(location).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		assertThat(body).isEqualTo(PDF);
		assertThat(this.school.downloads.get()).isEqualTo(1);

		// 같은 과목의 다른 학생은 학교 session 없이 받아 둔 사본을 연다. 학교에 요청하지 않는다.
		JsonNode otherFile = materials(this.other).get(items.size() - 1).path("files").get(0);
		assertThat(otherFile.path("copied").asBoolean()).isTrue();
		assertThat("/api/courses/" + this.course + "/files/" + otherFile.path("fileId").asLong() + "/content")
			.isEqualTo(location);
		this.other.get(location).andExpect(status().isOk());
		this.other.get("/api/courses/" + this.course + "/files/" + schoolFileId + "/content")
			.andExpect(status().isSeeOther())
			.andExpect(header().string("Location", location));
		assertThat(this.school.downloads.get()).isEqualTo(1);
		// 학교 본문은 그 학생의 학교 session으로만 읽는다.
		this.other.get("/api/courses/" + this.course + "/activities/school-" + this.material)
			.andExpect(status().isConflict());
		this.outsider.get(location).andExpect(status().isNotFound());
		this.outsider.get("/api/courses/" + this.course + "/activities/school-" + this.material)
			.andExpect(status().isNotFound());
		this.outsider.get("/api/courses/" + this.course + "/files/" + schoolFileId + "/content")
			.andExpect(status().isNotFound());
	}

	@Test
	void instructorsDoNotGetSchoolMaterials() throws Exception {
		assertThat(materials(this.professor).size()).isZero();
		assertThat(this.school.lists.get()).isZero();
	}

	@Test
	void anEndedSchoolSessionAsksToSignInAgainAndIsForgotten() throws Exception {
		this.school.expired = true;

		this.student.get("/api/courses/" + this.course + "/activities/school-" + this.material)
			.andExpect(status().isConflict())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
				.value("school_signin_required"));

		assertThat(this.registry.of(this.studentSession)).isEmpty();
		assertThat(this.school.closed).isTrue();
	}

	@Test
	void signingOutClosesTheSchoolSession() throws Exception {
		this.student.post("/api/auth/logout").andExpect(status().is2xxSuccessful());

		assertThat(this.registry.of(this.studentSession)).isEmpty();
		assertThat(this.school.closed).isTrue();
	}

	@Test
	void filesThatLeaveTheSchoolListLeaveStack() throws Exception {
		materials(this.student);
		this.school.listed = List.of();
		this.jdbc.sql("UPDATE school_file_lists SET listed_at = DATE_SUB(listed_at, INTERVAL 1 DAY)").update();

		JsonNode items = materials(this.student);

		assertThat(items.get(items.size() - 1).path("files").size()).isZero();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM school_files WHERE course_id = :course")
			.param("course", this.course)
			.query(Integer.class)
			.single()).isZero();
	}

	private JsonNode materials(ApiClient client) throws Exception {
		return ApiClient.json(client.get("/api/courses/" + this.course + "/materials").andExpect(status().isOk()))
			.path("items");
	}

	/** 학교 강의실 대역. 자료 하나에 PDF 하나, 공지 둘(하나는 안 읽음, PDF 하나 첨부). */
	static final class FakeSchool implements SchoolCourseRooms {

		final String key;

		final AtomicInteger lists = new AtomicInteger();

		final AtomicInteger details = new AtomicInteger();

		final AtomicInteger downloads = new AtomicInteger();

		final SchoolFile file = new SchoolFile("SYNFILE000001", "31", "슬라이드.pdf", 2048L, "lecture_material", "31");

		volatile List<SchoolFile> listed = List.of(this.file);

		final AtomicInteger noticeLists = new AtomicInteger();

		final AtomicInteger noticeDetails = new AtomicInteger();

		final SchoolFile noticeFile = new SchoolFile("SYNFILE000002", "SYNCONTENT0002", "안내.pdf", 1024L, "notice",
				"71");

		volatile List<SchoolNotice> notices = List.of(
				new SchoolNotice("71", "시험 안내", "다음 주", LocalDateTime.of(2026, 11, 5, 21, 0), 12, false, false,
						"https://eclass.example.ac.kr/connect?SEQ=71"),
				new SchoolNotice("70", "Midterm", null, LocalDateTime.of(2026, 10, 1, 9, 0), 40, true, true,
						"https://eclass.example.ac.kr/connect?SEQ=70"));

		volatile boolean expired;

		volatile boolean closed;

		private final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();

		FakeSchool(String key) {
			this.key = key;
		}

		@Override
		public boolean claim() {
			return this.claimed.compareAndSet(false, true);
		}

		@Override
		public SchoolCourseRoom read(String courseKey, Duration requestTimeout, Duration remaining) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<SchoolFile> files(String courseKey, Duration requestTimeout, Duration remaining) {
			check(courseKey);
			this.lists.incrementAndGet();
			return this.listed;
		}

		@Override
		public SchoolPost material(String courseKey, String itemId, Duration requestTimeout, Duration remaining) {
			check(courseKey);
			this.details.incrementAndGet();
			return new SchoolPost("학교 자료", LocalDateTime.of(2026, 9, 15, 9, 0), "담당 교수", "첫 줄\n둘째 줄",
					List.of(this.file));
		}

		@Override
		public List<SchoolNotice> notices(String courseKey, Duration requestTimeout, Duration remaining) {
			check(courseKey);
			this.noticeLists.incrementAndGet();
			return this.notices;
		}

		@Override
		public SchoolPost notice(String courseKey, String itemId, Duration requestTimeout, Duration remaining) {
			check(courseKey);
			this.noticeDetails.incrementAndGet();
			if (this.notices.stream().noneMatch((n) -> n.itemId().equals(itemId))) {
				throw new SchoolLoginException(SchoolLoginException.Kind.NOT_FOUND, "post_unrecognized");
			}
			return new SchoolPost("시험 안내", LocalDateTime.of(2026, 11, 5, 21, 0), "담당 교수", "본문",
					"71".equals(itemId) ? List.of(this.noticeFile) : List.of());
		}

		@Override
		public SchoolDownload download(String courseKey, SchoolFile file, long maxBytes, Path target,
				Duration requestTimeout, Duration remaining) {
			check(courseKey);
			this.downloads.incrementAndGet();
			try {
				Files.write(target, PDF);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return new SchoolDownload(PDF.length);
		}

		@Override
		public void close() {
			this.closed = true;
		}

		private void check(String courseKey) {
			assertThat(courseKey).isEqualTo(this.key);
			if (this.expired) {
				throw new SchoolLoginException(SchoolLoginException.Kind.EXPIRED, "signed_out");
			}
		}

	}

}
