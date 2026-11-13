package com.stackcorp.astra.files.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 녹음 음성 인식 port(ADR-0041). 구현은 integration 모듈에 있고 외부 음성 인식 제공자(Deepgram)를 부른다. 원본은 stream으로
 * 보내고 메모리에 모두 올리지 않는다. 구현은 요청을 한 번만 보낸다. 재시도는 부르는 쪽이 {@link SpeechToTextException#retryable()}을
 * 보고 정한다.
 */
public interface SpeechToText {

	/** 제공자 key가 설정돼 있다. false면 녹음에 전사 작업을 만들지 않는다. */
	boolean available();

	/**
	 * @throws SpeechToTextException 인식하지 못했거나 제공자에 닿지 못했을 때
	 */
	Transcript transcribe(Audio audio);

	/**
	 * @param mimeType 서버가 내용으로 판정한 audio MIME(예: audio/webm)
	 */
	record Audio(String mimeType, long sizeBytes, Opener opener) {
	}

	/** 원본 stream을 연다. 연 쪽이 닫는다. */
	@FunctionalInterface
	interface Opener {

		InputStream open() throws IOException;

	}

	/**
	 * @param durationSeconds 제공자가 잰 오디오 길이. 알 수 없으면 null
	 * @param utterances 제공자가 준 순서 그대로의 발화 단위
	 */
	record Transcript(Double durationSeconds, List<Utterance> utterances) {

		public Transcript {
			utterances = List.copyOf(utterances);
		}

	}

	/** @param start 녹음 시작 기준 초 */
	record Utterance(double start, String text) {
	}

}
