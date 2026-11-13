package com.stackcorp.astra.summary.api;

/**
 * 요약 생성이 쓰는 모델 호출 port. 구현은 integration 모듈에 있다(ADR-0008).
 * 구현은 요청을 한 번만 보낸다. 재시도는 호출하는 쪽이 {@link LlmException#retryable()}을 보고 정한다.
 */
public interface LlmClient {

	/** 작업에 기록할 모델 이름. */
	String model();

	/** 이미지 입력을 받는지. false면 호출하는 쪽이 이미지를 보내지 않는다. */
	boolean acceptsImages();

	/**
	 * @throws LlmException 호출이 실패했을 때
	 */
	LlmResponse complete(LlmRequest request);

}
