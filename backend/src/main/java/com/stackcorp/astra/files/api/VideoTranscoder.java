package com.stackcorp.astra.files.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * 영상 변환 port(ADR-0016). 구현은 integration 모듈에 있고 격리한 transcoder process를 부른다(SC-08).
 * 결과는 암호화하지 않은 MPEG-TS segment다. AES-128 암호화, 재생 목록 작성, 게시는 files가 한다. key는 port를 지나지 않는다.
 */
public interface VideoTranscoder {

	/**
	 * 원본을 HLS segment로 나눈다. segment는 0부터 순서대로 sink에 넘긴다. sink가 예외를 던지면 변환을 멈추고 그 예외를 그대로 던진다.
	 *
	 * @param cancelled segment 사이마다 확인한다. true면 {@link TranscodeException.Code#CANCELLED}로 멈춘다
	 * @throws TranscodeException 변환할 수 없는 입력이거나 transcoder에 닿지 못했을 때
	 */
	Transcoded transcode(VideoSource source, SegmentSink sink, BooleanSupplier cancelled);

	/**
	 * @param container 서버가 판정한 컨테이너: mp4, mov, webm, mkv
	 */
	record VideoSource(String container, long sizeBytes, Opener opener) {
	}

	/** 원본 stream을 연다. 연 쪽이 닫는다. */
	@FunctionalInterface
	interface Opener {

		InputStream open() throws IOException;

	}

	@FunctionalInterface
	interface SegmentSink {

		/**
		 * @param durationSeconds segment 재생 길이
		 * @param mpegTs 암호화하지 않은 MPEG-TS 바이트
		 */
		void accept(int index, double durationSeconds, byte[] mpegTs);

	}

	record Transcoded(int segmentCount, double durationSeconds) {
	}

}
