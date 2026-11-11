package com.stackcorp.astra.summary.policy;

import java.util.Optional;

/**
 * 동의 범위. 파일 요약(자료 상세의 "ASTRA 서포터즈 요약")은 게시한 쪽별 요약에서 모델 호출 없이 만들므로 따로 두지 않는다(ADR-0032).
 */
public enum ProcessingScope {

	/** 교수자가 요청한 쪽별 요약 생성. 쪽 이미지와 쪽 텍스트를 보낸다. */
	PAGE_SUMMARY,
	/** 학생과 교수자의 학습 대화. 질문과 그 파일의 쪽 텍스트, 게시한 해설을 보낸다. */
	STUDY_CHAT;

	public String value() {
		return name().toLowerCase();
	}

	public static Optional<ProcessingScope> of(String value) {
		for (ProcessingScope scope : values()) {
			if (scope.value().equals(value)) {
				return Optional.of(scope);
			}
		}
		return Optional.empty();
	}

}
