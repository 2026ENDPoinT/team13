package com.stackcorp.astra.learning;

import java.time.LocalDateTime;

/**
 * 온라인강의 콘텐츠 하나의 학습 진도. 적립 원장에서만 계산하고 재생 위치는 쓰지 않는다(SC-06, ADR-0014).
 *
 * @param creditedSec 출석인정기간 안에 적립한 초
 * @param requiredSec 출석인정에 필요한 초. required_sec이 없으면 duration_sec, 둘 다 없으면 null
 * @param status met(출석인정 충족) | under_required(미달) | unknown(필요 시간을 정할 수 없음)
 * @param attendedAt 처음 충족한 서버 시각. 한 번 기록하면 바뀌지 않는다
 * @param resumePositionSec 이어보기 위치 힌트. 진도와 출석 판정에 쓰지 않는다
 */
record ItemProgress(long itemId, int creditedSec, Integer requiredSec, int progressPercent, String status,
		LocalDateTime attendedAt, Integer resumePositionSec) {

	/** 출석인정 필요 시간 규칙 v1: required_sec, 없으면 콘텐츠 길이의 100%. */
	static Integer required(Integer requiredSec, Integer durationSec) {
		return requiredSec != null ? requiredSec : durationSec;
	}

	static ItemProgress of(long itemId, Integer requiredSec, Integer durationSec, int creditedSec,
			LocalDateTime metAt, Integer resumePositionSec) {
		Integer required = required(requiredSec, durationSec);
		String status = metAt != null ? "met" : required == null ? "unknown" : "under_required";
		int percent;
		if (required == null || required == 0) {
			percent = metAt != null ? 100 : 0;
		}
		else {
			percent = (int) Math.min(100, creditedSec * 100L / required);
		}
		return new ItemProgress(itemId, creditedSec, required, percent, status, metAt, resumePositionSec);
	}

}
