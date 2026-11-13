package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * HTTP 요청 지표(architecture.md 9절, SC-07, ADR-0034). 경로는 처리한 pattern으로만, 오류는 problem code로 센다. 어떤 지표에도 사용자,
 * 과목, 파일 ID나 실제 경로 값이 tag로 남지 않는다.
 */
class HttpMetricsTests extends IntegrationTest {

	private static final String COURSE_ROUTE = "/api/courses/{courseId}";

	private static final Set<String> FORBIDDEN_KEYS = Set.of("user", "user.id", "userid", "user_id", "course",
			"course.id", "courseid", "course_id", "file", "file.id", "fileid", "file_id", "session", "session.id",
			"http.url", "url", "query", "request.id", "requestid", "cookie", "authorization");

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	MeterRegistry meters;

	@Test
	void countsRequestsByRoutePatternStatusAndProblemCode() throws Exception {
		long course = this.data.course();
		long member = this.data.user("STUDENT");
		long outsider = this.data.user("STUDENT");
		this.data.enroll(course, member);
		double served = count(COURSE_ROUTE, "200", "none");
		double hidden = count(COURSE_ROUTE, "404", "not_found");

		new ApiClient(this.mvc, this.data.session(member)).get("/api/courses/" + course).andExpect(status().isOk());
		new ApiClient(this.mvc, this.data.session(outsider)).get("/api/courses/" + course + "?q=secret-4711")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("not_found"));

		assertThat(count(COURSE_ROUTE, "200", "none")).isEqualTo(served + 1);
		assertThat(count(COURSE_ROUTE, "404", "not_found")).isEqualTo(hidden + 1);
	}

	@Test
	void countsCommandRejectionsWithTheirCode() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		double rejected = sum("/api/uploads", "POST", "extension_not_allowed");

		new ApiClient(this.mvc, this.data.session(prof))
			.upload(course, "lecture_material", "run.exe", "MZ".getBytes(StandardCharsets.US_ASCII), null)
			.andExpect(status().is4xxClientError())
			.andExpect(jsonPath("$.code").value("extension_not_allowed"));

		assertThat(sum("/api/uploads", "POST", "extension_not_allowed")).isEqualTo(rejected + 1);
		assertThat(this.meters.find("http.server.requests").tag("code", "extension_not_allowed").timers())
			.allSatisfy((t) -> assertThat(t.getId().getTag("outcome")).isEqualTo("CLIENT_ERROR"));
	}

	@Test
	void recordsNoIdentifiersRawPathsOrQueryStringsInAnyMeter() throws Exception {
		long course = this.data.course();
		long outsider = this.data.user("STUDENT");
		String session = this.data.session(outsider);
		new ApiClient(this.mvc, session).get("/api/courses/" + course + "?token=secret-4712").andExpect(status().isNotFound());
		// 인증 단계에서 끊긴 요청도 pattern이 없을 뿐 실제 경로를 남기지 않는다.
		this.mvc.perform(get("/api/courses/" + course + "/materials")).andExpect(status().isUnauthorized());

		assertThat(this.meters.find("http.server.requests").timers()).isNotEmpty();
		for (Meter meter : this.meters.getMeters()) {
			for (Tag tag : meter.getId().getTags()) {
				assertThat(tag.getKey().toLowerCase()).as(meter.getId().getName()).isNotIn(FORBIDDEN_KEYS);
				assertThat(tag.getValue()).as(meter.getId() + " " + tag.getKey())
					.doesNotContain("/api/courses/" + course)
					.doesNotContain("secret-471")
					.doesNotContain(session);
			}
		}
	}

	@Test
	void reportsConnectionPoolUsage() {
		assertThat(this.meters.get("hikaricp.connections.active").gauge().value()).isGreaterThanOrEqualTo(0);
		assertThat(this.meters.get("hikaricp.connections.pending").gauge()).isNotNull();
		assertThat(this.meters.get("hikaricp.connections.max").gauge().value()).isPositive();
	}

	private double sum(String uri, String method, String code) {
		return this.meters.find("http.server.requests")
			.tag("uri", uri)
			.tag("method", method)
			.tag("code", code)
			.timers()
			.stream()
			.mapToDouble(Timer::count)
			.sum();
	}

	private double count(String uri, String status, String code) {
		Timer timer = this.meters.find("http.server.requests")
			.tag("uri", uri)
			.tag("status", status)
			.tag("code", code)
			.timer();
		return timer == null ? 0 : timer.count();
	}

}
