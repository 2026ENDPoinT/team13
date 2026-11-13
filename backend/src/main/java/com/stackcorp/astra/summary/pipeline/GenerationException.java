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

	private final String providerFailure;

	public GenerationException(Code code, Stage stage, String message) {
		this(code, stage, message, null, null);
	}

	/**
	 * 모델 호출 실패({@link Code#PROVIDER}).
	 * @param providerFailure 실패한 호출의 {@code LlmException.Code} 이름(소문자). 모델 client 밖의 예외면 null
	 * @param message 서버 로그와 failure.json에 남기는 기술 세부. 화면에는 보이지 않는다
	 */
	static GenerationException provider(Stage stage, String providerFailure, String message) {
		return new GenerationException(Code.PROVIDER, stage, message, null, providerFailure);
	}

	private GenerationException(Code code, Stage stage, String message, Usage usage, String providerFailure) {
		super(message);
		this.code = code;
		this.stage = stage;
		this.usage = usage;
		this.providerFailure = providerFailure;
	}

	public Code code() {
		return this.code;
	}

	public Stage stage() {
		return this.stage;
	}

	/** {@link Code#PROVIDER}일 때 실패한 모델 호출의 {@code LlmException.Code} 이름(소문자). 모르면 null. */
	public String providerFailure() {
		return this.providerFailure;
	}

	/** 실패할 때까지 쓴 모델 호출 수와 token 수. pipeline 밖으로 나온 예외에는 항상 있다. */
	public Usage usage() {
		return this.usage != null ? this.usage : new Usage(0, 0, 0);
	}

	GenerationException withUsage(Usage usage) {
		return new GenerationException(this.code, this.stage, getMessage(), usage, this.providerFailure);
	}

}
