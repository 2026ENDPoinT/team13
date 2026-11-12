package com.stackcorp.astra.summary.pipeline;

import com.stackcorp.astra.summary.pipeline.GenerationProgress.Stage;

/**
 * 요약 생성 실패. 일부만 만든 결과는 돌려주지 않는다.
 */
public class GenerationException extends RuntimeException {

	public enum Code {
		CANCELLED, PROVIDER, INVALID_OUTPUT, BUDGET, INPUT
	}

	private final Code code;

	private final Stage stage;

	private final Usage usage;

	public GenerationException(Code code, Stage stage, String message) {
		this(code, stage, message, null);
	}

	private GenerationException(Code code, Stage stage, String message, Usage usage) {
		super(message);
		this.code = code;
		this.stage = stage;
		this.usage = usage;
	}

	public Code code() {
		return this.code;
	}

	public Stage stage() {
		return this.stage;
	}

	/** 실패할 때까지 쓴 모델 호출 수와 token 수. pipeline 밖으로 나온 예외에는 항상 있다. */
	public Usage usage() {
		return this.usage != null ? this.usage : new Usage(0, 0, 0);
	}

	GenerationException withUsage(Usage usage) {
		return new GenerationException(this.code, this.stage, getMessage(), usage);
	}

}
