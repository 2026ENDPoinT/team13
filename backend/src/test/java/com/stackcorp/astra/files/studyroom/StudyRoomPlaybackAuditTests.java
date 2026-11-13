package com.stackcorp.astra.files.studyroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 재생 감사 기록(ADR-0040 7번). 기록은 저장소를 열기 전에 남긴다. 이미 남겼는지 찾지 못하면(DB 오류) 재생은 그대로 되고 기록을 다시
 * 남긴다(AuditLog의 transaction 밖 규칙). 감사 조회를 실패시키려고 JdbcClient와 저장소를 spy로 감싸므로 Spring context를 따로 쓴다.
 */
class StudyRoomPlaybackAuditTests extends IntegrationTest {

	private static final String OPERATION = "study_room.recording.play";

	private static final ArgumentMatcher<String> LOOKUP = (sql) -> sql != null && sql.contains("FROM audit_events")
			&& sql.contains("session_id = :session");

	private static final ArgumentMatcher<String> INSERT = (sql) -> sql != null
			&& sql.contains("INSERT INTO audit_events");

	@MockitoSpyBean
	JdbcClient jdbc;

	@MockitoSpyBean
	ObjectStore store;

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	MeterRegistry meters;

	@Test
	void aFailedAuditLookupStillPlaysAndRecordsThePlayBeforeTheObjectIsOpened() throws Exception {
		long userId = this.data.user("STUDENT");
		long course = this.data.course();
		this.data.enroll(course, userId);
		ApiClient owner = new ApiClient(this.mvc, this.data.session(userId));
		byte[] audio = StudyRoomApiTests.webm();
		long id = ApiClient.json(owner
			.send(MockMvcRequestBuilders.multipart("/api/me/study-room/courses/" + course + "/recordings")
				.file(new MockMultipartFile("file", "r.webm", "audio/webm", audio))
				.param("title", "녹음")
				.param("source", "mic")
				.param("durationMs", "1000")
				.param("recordedAt", Instant.now().toString()))
			.andExpect(status().isCreated())).get("id").asLong();

		List<Tracked> opened = new CopyOnWriteArrayList<>();
		doAnswer((invocation) -> {
			@SuppressWarnings("unchecked")
			Optional<InputStream> stream = (Optional<InputStream>) invocation.callRealMethod();
			return stream.map((s) -> {
				Tracked tracked = new Tracked(s);
				opened.add(tracked);
				return tracked;
			});
		}).when(this.store).openRange(any(Bucket.class), anyString(), anyLong(), anyLong());
		doThrow(new QueryTimeoutException("audit lookup failed")).when(this.jdbc).sql(argThat(LOOKUP));
		clearInvocations(this.jdbc, this.store);
		double failures = lookupFailures();

		// 전체와 구간 모두 재생된다. 찾기가 실패할 때마다 남기지 않은 것으로 보고 기록을 쓴다.
		owner.get("/api/me/study-room/recordings/" + id + "/content").andExpect(status().isOk()).andExpect(content().bytes(audio));
		owner.send(MockMvcRequestBuilders.get("/api/me/study-room/recordings/" + id + "/content").header("Range", "bytes=2-5"))
			.andExpect(status().isPartialContent())
			.andExpect(content().bytes(Arrays.copyOfRange(audio, 2, 6)));

		assertThat(plays(userId)).isEqualTo(2);
		assertThat(lookupFailures()).isEqualTo(failures + 2);
		// 찾기와 기록은 저장소를 열기 전이고, 연 stream은 모두 닫혔다.
		InOrder order = inOrder(this.jdbc, this.store);
		for (int i = 0; i < 2; i++) {
			order.verify(this.jdbc).sql(argThat(LOOKUP));
			order.verify(this.jdbc).sql(argThat(INSERT));
			order.verify(this.store).openRange(any(Bucket.class), anyString(), anyLong(), anyLong());
		}
		assertThat(opened).hasSize(2).allMatch(Tracked::closed);

		// 찾기가 되돌아오면 구간 안에 다시 남기지 않는다.
		reset(this.jdbc);
		owner.get("/api/me/study-room/recordings/" + id + "/content").andExpect(status().isOk());
		assertThat(plays(userId)).isEqualTo(2);
	}

	private long plays(long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE actor_user_id = :u AND operation = :op")
			.param("u", userId)
			.param("op", OPERATION)
			.query(Long.class)
			.single();
	}

	private double lookupFailures() {
		Counter counter = this.meters.find("astra.audit.lookup.failures").tag("operation", OPERATION).counter();
		return counter == null ? 0 : counter.count();
	}

	private static final class Tracked extends FilterInputStream {

		private volatile boolean closed;

		Tracked(InputStream in) {
			super(in);
		}

		@Override
		public void close() throws IOException {
			this.closed = true;
			super.close();
		}

		boolean closed() {
			return this.closed;
		}

	}

}
