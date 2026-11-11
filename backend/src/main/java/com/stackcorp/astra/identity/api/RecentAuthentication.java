package com.stackcorp.astra.identity.api;

import java.time.Duration;

/**
 * 민감한 연산 앞의 재인증(step-up) 확인(ADR-0029). 어느 모듈이든 명령을 실행하기 전에 부른다.
 * <p>
 * 재인증은 이 로그인 session에만 기록한다(session_step_ups의 {@code RECENT_AUTH}). 학교 IdP 계정은 IdP에서 다시
 * 로그인하고({@code GET /api/auth/reauth}, OIDC {@code prompt=login}, {@code max_age=0}), break-glass 계정은 TOTP를 다시
 * 입력한다({@code POST /api/me/step-up/totp}). 로그인 자체는 재인증으로 치지 않는다.
 */
public interface RecentAuthentication {

	/** 재인증이 없거나 오래됐을 때의 응답 code. */
	String STEP_UP_REQUIRED = "step_up_required";

	/**
	 * @param maxAge 재인증 뒤 이 시간이 지났으면 다시 요구한다. 설정한 재인증 유효 시간보다 길게 줘도 그 시간을 넘지 않는다
	 * @throws com.stackcorp.astra.shared.ApiException 403 {@value #STEP_UP_REQUIRED}
	 */
	void requireRecentAuth(Actor actor, Duration maxAge);

}
