package com.stackcorp.astra.identity.api;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 온라인 학습 적립에 쓰는 학습 인증 grant(SC-06-A, ADR-0029). grant는 로그인 session 하나에 묶이고, 2차 인증 challenge를
 * 통과해야 생기며, 만료되거나 사용자가 폐기할 수 있다.
 */
public interface LearningAuthGrants {

	/**
	 * 만료 여부는 판정하지 않는다. 적립하는 쪽이 자기 시계(DB 시계)로 {@code now < expiresAt}을 판정해 그 경계에서 구간을 자른다.
	 * @param sessionId 요청한 로그인 session({@link Actor#sessionId()})
	 * @return grant가 없거나 로그인 session이 폐기됐으면 비어 있다
	 */
	Optional<Grant> current(long sessionId);

	/**
	 * @param expiresAt grant 만료(폐기했으면 폐기 시각)와 로그인 session의 절대 만료 중 이른 쪽. DB 시계 기준이다
	 */
	record Grant(LocalDateTime expiresAt) {
	}

}
