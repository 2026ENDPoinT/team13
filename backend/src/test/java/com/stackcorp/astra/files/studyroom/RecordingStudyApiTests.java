package com.stackcorp.astra.files.studyroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.files.api.SpeechToText.Transcript;
import com.stackcorp.astra.files.api.SpeechToText.Utterance;
import com.stackcorp.astra.files.api.SpeechToTextException;
import com.stackcorp.astra.files.studyroom.RecordingStudyFakes.FakeSpeechToText;
import com.stackcorp.astra.files.studyroom.RecordingStudyFakes.SwitchableTextModel;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;

import tools.jackson.databind.JsonNode;

/**
 * 학습실 녹음의 전사·요약(ADR-0041). 프론트엔드 계약(Frontend-final README "녹음 전사·요약 연동 계약")이 구분하는 상태를 모두 응답으로
 * 확인한다: 미제공, 첫 응답부터 queued, processing, ready, failed, 하나 ready 하나 failed. 결과는 소유자만 보고, 검사를 마친 녹음만
 * 처리하며, 한도와 실제 길이로 판정하고, 지우면 결과도 사라진다. 음성 인식은 가짜, 모델은 진짜 adapter를 거친 FakeLlm이다.
 */
class RecordingStudyApiTests extends IntegrationTest {

	static final String BASE = "/api/me/study-room";

	private static final Pattern TIME = Pattern.compile("\\[(\\d+\\.\\d+)\\]");

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	FakeSpeechToText speech;

	@Autowired
	SwitchableTextModel model;

	@Autowired
	FakeLlm llm;

	@Autowired
	RecordingStudyRunner runner;

	@Autowired
	RecordingJobRecords jobs;

	long course;

	long ownerId;

	ApiClient owner;

	ApiClient other;

	@BeforeEach
	void setUp() {
		this.speech.enabled = true;
		this.model.enabled = true;
		this.llm.reset(RecordingStudyApiTests::summary);
		drain();
		this.llm.reset(RecordingStudyApiTests::summary);
		this.speech.calls.clear();
		this.course = this.data.course();
		this.ownerId = this.data.user("STUDENT");
		this.data.enroll(this.course, this.ownerId);
		this.owner = new ApiClient(this.mvc, this.data.session(this.ownerId));
		long otherId = this.data.user("STUDENT");
		this.data.enroll(this.course, otherId);
		this.other = new ApiClient(this.mvc, this.data.session(otherId));
	}

	@AfterEach
	void tearDown() {
		this.speech.reset();
		this.model.enabled = true;
		this.llm.reset(FakeLlm::honest);
	}

	@Test
	void withoutASpeechProviderNothingIsCreatedAndTheFieldsAreAbsent() throws Exception {
		this.speech.enabled = false;
		JsonNode created = json(upload("5000").andExpect(status().isCreated()));
		assertThat(created.has("transcription")).isFalse();
		assertThat(created.has("summary")).isFalse();
		JsonNode detail = detail(created.get("id").asLong());
		assertThat(detail.has("transcription")).isFalse();
		assertThat(detail.has("summary")).isFalse();
		assertThat(this.runner.runOnce()).isFalse();
		assertThat(this.speech.calls).isEmpty();
	}

	@Test
	void aRecordingFromBeforeTheFeatureStaysNotProvidedAndIsNeverProcessed() throws Exception {
		this.speech.enabled = false;
		long id = id(upload("5000"));
		this.speech.enabled = true;
		drain();
		assertThat(this.speech.calls).isEmpty();
		assertThat(detail(id).has("transcription")).isFalse();
	}

	@Test
	void jobsAreQueuedFromTheFirstResponseThenProcessingThenReady() throws Exception {
		JsonNode created = json(upload("5000").andExpect(status().isCreated()));
		long id = created.get("id").asLong();
		assertThat(created.get("transcription").toString()).isEqualTo("{\"status\":\"queued\"}");
		assertThat(created.get("summary").toString()).isEqualTo("{\"status\":\"queued\"}");
		assertThat(detail(id).get("transcription").get("status").asString()).isEqualTo("queued");

		// 처리하는 동안 상세는 processing이다.
		AtomicReference<JsonNode> during = new AtomicReference<>();
		this.speech.script = (audio, bytes) -> {
			try {
				during.set(detail(id));
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return FakeSpeechToText.lecture(audio, bytes);
		};
		assertThat(this.runner.runOnce()).isTrue();
		assertThat(during.get().get("transcription").get("status").asString()).isEqualTo("processing");
		assertThat(during.get().get("summary").get("status").asString()).isEqualTo("queued");

		JsonNode transcribed = detail(id);
		JsonNode transcription = transcribed.get("transcription");
		assertThat(transcription.get("status").asString()).isEqualTo("ready");
		assertThat(transcription.has("message")).isFalse();
		JsonNode segments = transcription.get("result").get("segments");
		assertThat(segments).hasSize(3);
		assertThat(segments.get(0).get("at").asDouble()).isEqualTo(0.0);
		assertThat(segments.get(1).get("at").asDouble()).isEqualTo(2.34);
		assertThat(segments.get(1).get("text").asString()).isEqualTo("두 번째 문장입니다.");
		assertThat(segments.get(2).get("at").asDouble()).isEqualTo(4.5);
		long bytes = this.speech.calls.getFirst();
		assertThat(bytes).isEqualTo(created.get("sizeBytes").asLong());
		assertThat(transcription.get("result").get("text").asString())
			.isEqualTo("첫 문장 bytes=" + bytes + "\n두 번째 문장입니다.\n세 번째 Stack 문장");
		assertThat(transcribed.get("summary").get("status").asString()).isEqualTo("queued");

		assertThat(this.runner.runOnce()).isTrue();
		JsonNode summary = detail(id).get("summary");
		assertThat(summary.get("status").asString()).isEqualTo("ready");
		JsonNode result = summary.get("result");
		assertThat(result.get("overview").asString()).isEqualTo("부분 요약");
		assertThat(result.get("note").asString()).isEqualTo(RecordingSummarizer.NOTE);
		JsonNode sections = result.get("sections");
		assertThat(sections).hasSize(2);
		assertThat(sections.get(0).get("title").asString()).isEqualTo("앞 주제");
		assertThat(sections.get(0).get("at").asDouble()).isEqualTo(0.0);
		assertThat(sections.get(0).get("table").get("head")).hasSize(2);
		assertThat(sections.get(1).get("title").asString()).isEqualTo("뒤 주제");
		// 모델이 4.8로 적은 시각은 그 이하의 가장 늦은 전사 줄(4.5)로 맞춘다.
		assertThat(sections.get(1).get("at").asDouble()).isEqualTo(4.5);
		assertThat(sections.get(1).has("table")).isFalse();
		assertThat(sections.get(1).get("points").get(0).get("term").asString()).isEqualTo("용어");
		// 모델에는 전사 줄만 간다. 제목은 가지 않는다.
		assertThat(FakeLlm.text(this.llm.log.getLast())).contains("[0.00] 첫 문장").doesNotContain("강의 녹음 제목");
		assertThat(this.llm.log.getLast().purpose()).isEqualTo(LlmRequest.Purpose.RECORDING);

		// 감사 기록은 ID와 결과만 남긴다.
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM audit_events WHERE object_id = :id AND object_type = 'study_room_recording'
				   AND operation IN ('study_room.recording.transcription', 'study_room.recording.summary') AND result_code = 'ok'
				""").param("id", id).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void listAndCourseViewCarryNoResultsAndAnotherUserGetsNotFound() throws Exception {
		long id = id(upload("5000"));
		drain();
		assertThat(detail(id).get("summary").get("status").asString()).isEqualTo("ready");
		for (JsonNode recording : json(this.owner.get(BASE + "/recordings").andExpect(status().isOk())).get("recordings")) {
			assertThat(recording.has("transcription")).isFalse();
			assertThat(recording.has("summary")).isFalse();
		}
		for (JsonNode recording : json(this.owner.get(BASE + "/courses/" + this.course).andExpect(status().isOk()))
			.get("recordings")) {
			assertThat(recording.has("transcription")).isFalse();
			assertThat(recording.has("summary")).isFalse();
		}
		String body = this.other.get(BASE + "/recordings/" + id)
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(body).doesNotContain("segments").doesNotContain("첫 문장");
		assertThat(json(this.other.get(BASE + "/recordings")).get("recordings").toString()).doesNotContain("\"id\":" + id);
	}

	@Test
	void oneJobCanBeReadyWhileTheOtherFailed() throws Exception {
		long id = id(upload("5000"));
		this.llm.reset((request) -> FakeLlm.answer("요약을 못 하겠습니다"));
		assertThat(this.runner.runOnce()).isTrue();
		for (int attempt = 1; attempt <= 4; attempt++) {
			assertThat(this.runner.runOnce()).isTrue();
			if (attempt < 4) {
				// 형식이 틀린 출력은 다시 시도한다. 그 사이 상세는 queued다.
				assertThat(detail(id).get("summary").get("status").asString()).isEqualTo("queued");
				due(id);
			}
		}
		JsonNode detail = detail(id);
		assertThat(detail.get("transcription").get("status").asString()).isEqualTo("ready");
		assertThat(detail.get("summary").toString()).isEqualTo(
				"{\"status\":\"failed\",\"message\":\"요약 모델의 결과가 형식에 맞지 않아 요약을 만들지 못했습니다.\"}");
	}

	@Test
	void silentAudioIsAReadyEmptyTranscriptAndNoSummary() throws Exception {
		long id = id(upload("5000"));
		this.speech.script = (audio, bytes) -> new Transcript(5.0, List.of(new Utterance(1, " ")));
		drain();
		JsonNode detail = detail(id);
		assertThat(detail.get("transcription").get("result").toString()).isEqualTo("{\"segments\":[],\"text\":\"\"}");
		assertThat(detail.get("summary").get("message").asString()).isEqualTo("녹음에서 말소리를 찾지 못해 요약을 만들지 않았습니다.");
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void withoutAModelOnlyTheTranscriptionIsCreatedAndQueuedSummariesCloseWhenTheModelGoesAway() throws Exception {
		this.model.enabled = false;
		JsonNode created = json(upload("5000"));
		assertThat(created.get("transcription").get("status").asString()).isEqualTo("queued");
		assertThat(created.has("summary")).isFalse();

		this.model.enabled = true;
		long second = id(upload("5000"));
		this.model.enabled = false;
		drain();
		JsonNode detail = detail(second);
		assertThat(detail.get("transcription").get("status").asString()).isEqualTo("ready");
		assertThat(detail.get("summary").get("message").asString())
			.isEqualTo("이 서비스에서 요약 모델을 쓰지 않도록 설정이 바뀌어 요약하지 않았습니다.");
	}

	@Test
	void onlyScannedRecordingsAreProcessedAndRejectedOnesFail() throws Exception {
		long id = id(upload("5000"));
		scanState(id, "quarantined", "pending");
		assertThat(this.runner.runOnce()).isFalse();
		JsonNode scanning = detail(id);
		assertThat(scanning.get("state").asString()).isEqualTo("scanning");
		assertThat(scanning.get("transcription").get("status").asString()).isEqualTo("queued");
		assertThat(this.speech.calls).isEmpty();

		scanState(id, "rejected", "infected");
		assertThat(this.runner.runOnce()).isFalse();
		JsonNode rejected = detail(id);
		assertThat(rejected.get("transcription").get("message").asString())
			.isEqualTo("악성코드 검사에서 거부된 녹음이라 처리하지 않았습니다.");
		assertThat(rejected.get("summary").get("status").asString()).isEqualTo("failed");
		assertThat(this.speech.calls).isEmpty();
	}

	@Test
	void lengthAndUsageLimitsRefuseBeforeSending() throws Exception {
		long tooLong = id(upload(Long.toString(180 * 60_000L + 1000)));
		drain();
		assertThat(detail(tooLong).get("transcription").get("message").asString())
			.isEqualTo("녹음이 180분보다 길어 전사하지 않았습니다.");
		assertThat(detail(tooLong).get("summary").get("message").asString())
			.isEqualTo("전사를 끝내지 못해 요약을 만들지 않았습니다.");

		usage(LocalDate.now(), 299 * 60);
		long daily = id(upload("120000"));
		drain();
		assertThat(detail(daily).get("transcription").get("message").asString())
			.isEqualTo("오늘 전사할 수 있는 녹음 시간(300분)을 넘어 전사하지 않았습니다. 내일 다시 올리면 전사합니다.");
		assertThat(this.speech.calls).isEmpty();
		assertThat(this.jobs.usageSeconds(this.ownerId)).isEqualTo(299 * 60);

		LocalDate earlier = LocalDate.now().withDayOfMonth(1);
		assumeThat(earlier).isBefore(LocalDate.now());
		usage(LocalDate.now(), 0);
		usage(earlier, 1799 * 60);
		long monthly = id(upload("120000"));
		drain();
		assertThat(detail(monthly).get("transcription").get("message").asString())
			.isEqualTo("이번 달 전사할 수 있는 녹음 시간(1800분)을 넘어 전사하지 않았습니다.");
	}

	@Test
	void theProviderLengthIsChargedAndAnUnderstatedRecordingIsWithheld() throws Exception {
		// 길게 선언했지만 실제는 30초: 실제 길이만 청구하고 결과를 낸다.
		long overstated = id(upload("600000"));
		this.speech.script = (audio, bytes) -> new Transcript(30.0, List.of(new Utterance(40, "끝")));
		assertThat(this.runner.runOnce()).isTrue();
		JsonNode ready = detail(overstated).get("transcription");
		assertThat(ready.get("status").asString()).isEqualTo("ready");
		// 실제 길이 밖의 시각은 실제 길이로 자른다.
		assertThat(ready.get("result").get("segments").get(0).get("at").asDouble()).isEqualTo(30.0);
		assertThat(this.jobs.usageSeconds(this.ownerId)).isEqualTo(30);

		// 10초로 선언했지만 실제는 1시간: 실제 길이를 청구하고 결과는 내주지 않는다.
		long understated = id(upload("10000"));
		this.speech.script = (audio, bytes) -> new Transcript(3600.0, List.of(new Utterance(1, "몰래")));
		assertThat(this.runner.runOnce()).isTrue();
		JsonNode withheld = detail(understated);
		assertThat(withheld.get("transcription").toString()).doesNotContain("몰래").contains("실제 길이가 올릴 때 알린 길이와 달라");
		assertThat(withheld.get("summary").get("status").asString()).isEqualTo("failed");
		assertThat(this.jobs.usageSeconds(this.ownerId)).isEqualTo(30 + 3600);
		assertThat(this.jdbc.sql("SELECT result_json IS NULL FROM study_room_recording_jobs WHERE study_room_recording_id = :id"
				+ " AND job_kind = 'transcription'").param("id", understated).query(Boolean.class).single()).isTrue();

		// 실제 길이가 상한을 넘으면 too_long이다.
		long hidden = id(upload("10800000"));
		this.speech.script = (audio, bytes) -> new Transcript(10900.0, List.of(new Utterance(1, "길다")));
		assertThat(this.runner.runOnce()).isTrue();
		assertThat(detail(hidden).get("transcription").get("message").asString())
			.isEqualTo("녹음이 180분보다 길어 전사하지 않았습니다.");
	}

	@Test
	void transientProviderFailuresRetryWithBackoffAndRefundTheReservation() throws Exception {
		long id = id(upload("60000"));
		this.speech.script = (audio, bytes) -> {
			throw new SpeechToTextException(SpeechToTextException.Code.SERVER, "HTTP 503");
		};
		assertThat(this.runner.runOnce()).isTrue();
		assertThat(detail(id).get("transcription").get("status").asString()).isEqualTo("queued");
		assertThat(this.jobs.usageSeconds(this.ownerId)).isZero();
		// 다음 시도 시각 전에는 잡지 않는다.
		assertThat(this.runner.runOnce()).isFalse();
		for (int attempt = 2; attempt <= 4; attempt++) {
			due(id);
			assertThat(this.runner.runOnce()).isTrue();
		}
		JsonNode detail = detail(id);
		assertThat(detail.get("transcription").get("message").asString()).isEqualTo("음성 인식 서비스가 응답하지 않아 전사하지 못했습니다.");
		assertThat(detail.get("transcription").toString()).doesNotContain("503");
		assertThat(detail.get("summary").get("status").asString()).isEqualTo("failed");

		// 다시 해도 같은 실패는 한 번에 닫는다.
		long bad = id(upload("60000"));
		this.speech.script = (audio, bytes) -> {
			throw new SpeechToTextException(SpeechToTextException.Code.INVALID_AUDIO, "HTTP 400");
		};
		assertThat(this.runner.runOnce()).isTrue();
		assertThat(detail(bad).get("transcription").get("message").asString())
			.isEqualTo("음성을 인식할 수 없는 오디오라 전사하지 못했습니다.");
	}

	@Test
	void anExpiredLeaseIsRecoveredAndRetriedOrClosed() throws Exception {
		long id = id(upload("60000"));
		crashed(id, 1);
		this.runner.recoverExpired();
		JsonNode retried = detail(id).get("transcription");
		assertThat(retried.get("status").asString()).isEqualTo("queued");
		assertThat(this.jobs.usageSeconds(this.ownerId)).isZero();
		due(id);
		drain();
		assertThat(detail(id).get("transcription").get("status").asString()).isEqualTo("ready");

		long last = id(upload("60000"));
		crashed(last, 4);
		this.runner.recoverExpired();
		JsonNode closed = detail(last);
		assertThat(closed.get("transcription").get("message").asString()).isEqualTo("처리하던 서버가 멈춰 끝내지 못했습니다.");
		assertThat(closed.get("summary").get("status").asString()).isEqualTo("failed");
	}

	@Test
	void deletingDuringProcessingDiscardsTheResultAndRemovesTheJobs() throws Exception {
		long id = id(upload("60000"));
		this.speech.script = (audio, bytes) -> {
			try {
				this.owner.send(MockMvcRequestBuilders.delete(BASE + "/recordings/" + id).header("If-Match", "\"1\""))
					.andExpect(status().isNoContent());
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return FakeSpeechToText.lecture(audio, bytes);
		};
		assertThat(this.runner.runOnce()).isTrue();
		assertThat(jobRows(id)).isZero();
		this.owner.get(BASE + "/recordings/" + id).andExpect(status().isNotFound());
		// 제공자는 이미 처리했으므로 잡아 둔 사용량(선언 길이)은 남는다.
		assertThat(this.jobs.usageSeconds(this.ownerId)).isEqualTo(60);
	}

	@Test
	void continuingARecordingGivesTheNewAudioItsOwnResults() throws Exception {
		long original = id(upload("5000"));
		drain();
		long sizeOfOriginal = detail(original).get("sizeBytes").asLong();
		// 이어서 녹음: 이어 붙인 음원을 새로 올리고 원래 녹음을 지운다.
		byte[] longer = StudyRoomApiTests.webm();
		longer = Arrays.copyOf(longer, longer.length + 4096);
		JsonNode continued = json(upload("10000", longer));
		long next = continued.get("id").asLong();
		assertThat(continued.get("transcription").get("status").asString()).isEqualTo("queued");
		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/recordings/" + original).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		assertThat(jobRows(original)).isZero();
		drain();
		String text = detail(next).get("transcription").get("result").get("text").asString();
		assertThat(text).startsWith("첫 문장 bytes=" + continued.get("sizeBytes").asLong());
		assertThat(continued.get("sizeBytes").asLong()).isNotEqualTo(sizeOfOriginal);
	}

	@Test
	void twoWorkersNeverProcessTheSameJob() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			ids.add(id(upload("5000")));
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<?>> runs = new ArrayList<>();
			for (int t = 0; t < 2; t++) {
				runs.add(pool.submit(() -> {
					for (int i = 0; i < 10; i++) {
						this.runner.runOnce();
					}
				}));
			}
			for (Future<?> run : runs) {
				run.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
		drain();
		assertThat(this.speech.calls).hasSize(3);
		assertThat(this.llm.log).hasSize(3);
		for (long id : ids) {
			assertThat(detail(id).get("summary").get("status").asString()).isEqualTo("ready");
			// 작업마다 한 번만 실행했다.
			assertThat(this.jdbc.sql("""
					SELECT attempt_count FROM study_room_recording_jobs WHERE study_room_recording_id = :id
					""").param("id", id).query(Integer.class).list()).containsOnly(1);
		}
	}

	// ---------- helpers ----------

	/** 전사 줄의 첫 시각과 마지막 시각으로 섹션 둘을 만든다. 둘째 섹션은 마크다운과 시각 순서를 어긴다. */
	static LlmResponse summary(LlmRequest request) {
		if (RecordingSummarizer.OVERVIEW_SYSTEM.equals(request.system())) {
			return FakeLlm.answer("{\"overview\":\"전체 요약\"}");
		}
		Matcher m = TIME.matcher(FakeLlm.text(request));
		List<Double> times = new ArrayList<>();
		while (m.find()) {
			times.add(Double.parseDouble(m.group(1)));
		}
		double first = times.getFirst();
		double late = times.getLast() + 0.3;
		return FakeLlm.answer("""
				```json
				{"overview": "부분 요약", "sections": [
				  {"title": "**뒤** 주제", "at": %s, "points": [{"term": "용어", "text": "설명", "at": %s}], "table": {"head": ["a"], "rows": [["1", "2"]]}},
				  {"title": "앞 주제", "at": %s, "points": [{"term": "개념", "text": "정의", "at": %s}], "table": {"head": ["항목", "뜻"], "rows": [["A", "B"]]}}
				]}
				```""".formatted(late, late, first, first));
	}

	private void drain() {
		while (this.runner.runOnce()) {
			// 남은 작업을 비운다.
		}
	}

	private ResultActions upload(String durationMs) throws Exception {
		return upload(durationMs, StudyRoomApiTests.webm());
	}

	private ResultActions upload(String durationMs, byte[] content) throws Exception {
		return this.owner.send(MockMvcRequestBuilders.multipart(BASE + "/courses/" + this.course + "/recordings")
			.file(new MockMultipartFile("file", "lecture.webm", "audio/webm", content))
			.param("title", "강의 녹음 제목")
			.param("source", "mic")
			.param("durationMs", durationMs)
			.param("recordedAt", Instant.now().toString()));
	}

	private JsonNode detail(long id) throws Exception {
		return json(this.owner.get(BASE + "/recordings/" + id).andExpect(status().isOk()));
	}

	private void due(long recordingId) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs SET next_attempt_at = CURRENT_TIMESTAMP(3)
				 WHERE study_room_recording_id = :id AND job_state = 'queued'
				""").param("id", recordingId).update();
	}

	/** worker가 전사를 잡은 뒤 멈춘 상태: 사용량을 잡아 두고 lease가 끝났다. */
	private void crashed(long recordingId, int attempts) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs
				   SET job_state = 'processing', attempt_count = :attempts, lease_owner = 'dead-worker',
				       lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND), reserved_seconds = 60,
				       reserved_on = CURRENT_DATE
				 WHERE study_room_recording_id = :id AND job_kind = 'transcription'
				""").param("attempts", attempts).param("id", recordingId).update();
		usage(LocalDate.now(), 60);
	}

	private void usage(LocalDate on, long seconds) {
		this.jdbc.sql("""
				INSERT INTO study_room_transcription_usage (owner_user_id, usage_date, audio_seconds) VALUES (:u, :d, :s)
				ON DUPLICATE KEY UPDATE audio_seconds = :s
				""").param("u", this.ownerId).param("d", on).param("s", seconds).update();
	}

	private long jobRows(long recordingId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM study_room_recording_jobs WHERE study_room_recording_id = :id")
			.param("id", recordingId)
			.query(Long.class)
			.single();
	}

	private void scanState(long recordingId, String blobState, String fileState) {
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id
				  JOIN study_room_recordings r ON r.file_id = f.file_id
				   SET b.blob_state = :blob, f.scan_state = :file
				 WHERE r.study_room_recording_id = :id
				""").param("blob", blobState).param("file", fileState).param("id", recordingId).update();
	}

	private static JsonNode json(ResultActions result) throws Exception {
		return ApiClient.json(result);
	}

	private static long id(ResultActions result) throws Exception {
		return json(result).get("id").asLong();
	}

}
