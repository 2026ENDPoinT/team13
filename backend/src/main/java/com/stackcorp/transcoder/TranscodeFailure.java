package com.stackcorp.transcoder;

/**
 * 변환할 수 없는 입력이나 제한 초과. code는 응답 JSON의 code 값이고 message는 고정 문장이다. 영상 내용과 ffmpeg 출력을 넣지 않는다.
 */
final class TranscodeFailure extends Exception {

	private final String code;

	TranscodeFailure(String code, String message) {
		super(message);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}
