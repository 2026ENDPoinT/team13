package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.RenditionException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 저장소 GC, orphan upload backlog, 문서 변환의 지표(architecture.md 9절, ADR-0034).
 */
class FilesMetricsTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MeterRegistry meters;

	@Autowired
	StorageGc gc;

	@Autowired
	FilesBacklog backlog;

	@Autowired
	DocumentRenditions renditions;

	@Test
	void reportsOrphanUploadsUntilTheGcExpiresThemAndCountsWhatItProcessed() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		long upload = new ApiClient(this.mvc, this.data.session(prof)).uploaded(course, "orphan.txt",
				("orphan " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
		this.backlog.sample();
		double before = gauge("expired_uploads");
		double processed = processed("expired_uploads");

		this.jdbc.sql("UPDATE upload_sessions SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) WHERE upload_id = :id")
			.param("id", upload)
			.update();
		this.backlog.sample();
		assertThat(gauge("expired_uploads")).isEqualTo(before + 1);

		this.gc.run();

		assertThat(processed("expired_uploads")).isGreaterThanOrEqualTo(processed + 1);
		assertThat(this.jdbc.sql("SELECT upload_state FROM upload_sessions WHERE upload_id = :id")
			.param("id", upload)
			.query(String.class)
			.single()).isEqualTo("expired");
		this.backlog.sample();
		assertThat(gauge("expired_uploads")).isLessThanOrEqualTo(before);
		assertThat(gauge("deleted_files")).isGreaterThanOrEqualTo(0);
		assertThat(gauge("unreferenced_blobs")).isGreaterThanOrEqualTo(0);
	}

	@Test
	void timesConversionsAndCountsFailuresByCode() {
		long good = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		long broken = this.data.pdfFile("not a pdf".getBytes(StandardCharsets.US_ASCII), "clean");
		double ok = conversions("ok");
		double failed = conversions("failed");

		this.renditions.prepare(good);
		// 이미 만든 rendition을 다시 읽는 것은 변환이 아니다.
		this.renditions.prepare(good);
		RenditionException ex = catchThrowableOfType(RenditionException.class, () -> this.renditions.prepare(broken));

		assertThat(conversions("ok")).isEqualTo(ok + 1);
		assertThat(conversions("failed")).isEqualTo(failed + 1);
		assertThat(this.meters.get("astra.files.rendition.conversions")
			.tag("outcome", "failed")
			.tag("code", ex.detail().toLowerCase())
			.timer()
			.count()).isPositive();
	}

	private double gauge(String kind) {
		return this.meters.get("astra.files.gc.backlog").tag("kind", kind).gauge().value();
	}

	private double processed(String step) {
		Counter counter = this.meters.find("astra.files.gc.processed").tag("step", step).counter();
		return counter == null ? 0 : counter.count();
	}

	private double conversions(String outcome) {
		return this.meters.find("astra.files.rendition.conversions")
			.tag("outcome", outcome)
			.timers()
			.stream()
			.mapToDouble(Timer::count)
			.sum();
	}

}
