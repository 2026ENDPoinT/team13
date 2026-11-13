package com.stackcorp.astra.files.api;

/**
 * 녹음 요약이 쓰는 글 생성 port(ADR-0041). 구현은 integration 모듈에 있고 요약 모듈의 모델 port(ADR-0008)와 같은 제공자를 부른다.
 * 구현은 요청을 한 번만 보낸다. 재시도는 부르는 쪽이 {@link TextModelException#retryable()}을 보고 정한다.
 */
public interface TextModel {

	/** 모델 제공자가 설정돼 있다. false면 녹음에 요약 작업을 만들지 않는다. */
	boolean available();

	/**
	 * @param system 지시문
	 * @param user 입력. 신뢰하지 않는 글(전사문)을 담는다
	 * @return 모델이 쓴 글
	 * @throws TextModelException 호출이 실패했거나 글이 잘렸을 때
	 */
	String complete(String system, String user, int maxOutputTokens);

}
