package com.stackcorp.astra.files.studyroom;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 녹음 전사·요약의 응답 형식. 프론트엔드 계약(Frontend-final README "녹음 전사·요약 연동 계약", src/shared/recording-study.ts)과
 * 필드 이름이 같다. 결과는 이 형식 그대로 study_room_recording_jobs.result_json에 저장한다(ADR-0041).
 */
final class RecordingStudyViews {

	private RecordingStudyViews() {
	}

	/**
	 * 작업 하나의 상태. queued, processing은 status만, ready는 result, failed는 message를 담는다.
	 *
	 * @param result {@link Transcription} 또는 {@link Summary}
	 * @param message 정해 둔 한국어 문장({@link RecordingStudyFailures})
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Job(String status, Object result, String message) {

		static final String QUEUED = "queued";

		static final String PROCESSING = "processing";

		static final String READY = "ready";

		static final String FAILED = "failed";

	}

	/** @param at 녹음 시작 기준 초(0.01초 단위, 오름차순) */
	record Segment(double at, String text) {
	}

	/** @param text segment 글을 줄바꿈으로 이은 것. TXT 내려받기에 그대로 쓰인다 */
	record Transcription(List<Segment> segments, String text) {

		Transcription {
			segments = List.copyOf(segments);
		}

	}

	record Point(String term, String text, double at) {
	}

	record Table(List<String> head, List<List<String>> rows) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Section(String title, double at, List<Point> points, Table table) {
	}

	/** @param note 서버가 정한 출처 안내 문장. 모델이 쓰지 않는다 */
	record Summary(String overview, List<Section> sections, String note) {
	}

}
