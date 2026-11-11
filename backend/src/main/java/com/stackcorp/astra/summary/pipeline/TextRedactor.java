package com.stackcorp.astra.summary.pipeline;

/**
 * 모델에 보내기 전에 글에서 개인을 가리킬 수 있는 부분을 가린다(ADR-0032, ADR-0008 6번). 쪽별 요약의 쪽 텍스트와 자료 제목, 학습
 * 대화의 쪽 텍스트·해설·질문이 모두 이 hook을 거친다. 구현은 원문의 뜻을 바꾸지 않는 범위에서만 가리고, 같은 입력에 같은 결과를
 * 돌려준다(checkpoint key가 원문 해시라 결과가 매번 달라지면 안 된다).
 */
public interface TextRedactor {

	String redact(String text);

}
