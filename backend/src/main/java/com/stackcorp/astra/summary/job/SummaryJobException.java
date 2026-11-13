package com.stackcorp.astra.summary.job;

public class SummaryJobException extends RuntimeException {

	public enum Code {
		/** 작업이나 파일이 없다. */
		NOT_FOUND,
		/** 같은 파일에 진행 중인 작업이 있다. */
		CONFLICT,
		/** 지금 상태에서 할 수 없는 요청이다. */
		INVALID_STATE,
		/** 파일이 검사를 마치지 않았다. */
		FILE_NOT_READY,
		/** PDF가 아니다. */
		UNSUPPORTED_FILE,
		/** 요청한 사람이 과목의 결정권자가 아니다. 조교는 외부 AI 처리를 요청하지 않는다(ADR-0032). */
		LLM_DECIDER_REQUIRED
	}

	private final Code code;

	public SummaryJobException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public Code code() {
		return this.code;
	}

}
