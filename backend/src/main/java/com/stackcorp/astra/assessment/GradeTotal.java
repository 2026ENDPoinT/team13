package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 점수를 받은 항목만 더한 합계(ADR-0023). 점수가 없는 항목은 0점으로 치지 않고 합계에서 뺀다. 학생 응답에서는 공개한 항목만 넣어 부르므로
 * 공개하지 않은 점수가 합계, 배점 합, 항목 수 어디에도 섞이지 않는다(SEC-14).
 *
 * @param score 받은 점수의 합
 * @param maxScore 그 항목들의 배점 합
 * @param itemCount 더한 항목 수
 * @param weightedScore 비율을 정한 항목의 (점수 / 배점 × 비율) 합. 소수 둘째 자리 반올림. 비율을 정한 항목이 없으면 없다
 * @param weightTotal 그 항목들의 비율 합. weightedScore와 함께만 있다
 */
record GradeTotal(BigDecimal score, BigDecimal maxScore, int itemCount,
		@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal weightedScore,
		@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal weightTotal) {

	/** @return 점수를 받은 항목이 없으면 null */
	static GradeTotal of(List<Part> parts) {
		BigDecimal score = BigDecimal.ZERO;
		BigDecimal max = BigDecimal.ZERO;
		BigDecimal weighted = null;
		BigDecimal weights = null;
		int count = 0;
		for (Part part : parts) {
			if (part.score() == null) {
				continue;
			}
			count++;
			score = score.add(part.score());
			max = max.add(part.maxScore());
			if (part.weight() != null && part.maxScore().signum() > 0) {
				BigDecimal share = part.score().multiply(part.weight()).divide(part.maxScore(), MathContext.DECIMAL64);
				weighted = weighted == null ? share : weighted.add(share);
				weights = weights == null ? part.weight() : weights.add(part.weight());
			}
		}
		if (count == 0) {
			return null;
		}
		return new GradeTotal(score, max, count, weighted == null ? null : weighted.setScale(2, RoundingMode.HALF_UP),
				weights);
	}

	/**
	 * @param score 받은 점수. 없으면 null
	 */
	record Part(BigDecimal score, BigDecimal maxScore, BigDecimal weight) {
	}

}
