package com.stackcorp.astra.summary.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 LLM 기능의 배포 스위치와 제공자 profile(ADR-0032). 제공자 연결 값(주소, model, key)은 integration이 같은 접두사로 따로
 * 읽는다. 이 record는 연결 값을 알지 못한다.
 *
 * @param enabled false면 쪽별 요약 생성과 학습 대화를 모두 막고 모델을 부르지 않는다. 게시한 요약과 파일 요약은 계속 보인다
 * @param policy 이 배포의 제공자 데이터 처리 조건
 */
@ConfigurationProperties("astra.llm")
public record LlmPolicyProperties(@DefaultValue("true") boolean enabled, @DefaultValue ProviderProfile policy) {
}
