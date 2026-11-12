package com.stackcorp.astra.identity.stepup;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.api.SecondFactorChannel;
import com.stackcorp.astra.identity.session.DevLoginMarker;

/**
 * 학습 인증의 2차 인증 수단 {@code idp}: 학교 IdP에서 다시 로그인한다(ADR-0029).
 * <p>
 * 근거는 challenge를 만든 뒤 같은 로그인 session에 기록된 재인증이다. 그 기록은 IdP가 서명한 ID token의 사용자와
 * {@code auth_time}을 서버가 확인했을 때만 생긴다({@code identity.sso}). 확인 요청의 본문은 근거가 아니다. 코드가 없는 수단이라
 * 코드를 보내면 실패다. 로컬 실행에서는 개발용 재인증(테스트 소스)도 근거로 받는다.
 */
@Component
class IdpReauthenticationChannel implements SecondFactorChannel {

	static final String NAME = "idp";

	/** secondary_auth_challenges.auth_way. 원본의 1~5 다음 값이다. */
	static final int AUTH_WAY = 6;

	static final String REAUTH_PATH = "/api/auth/reauth";

	private final ObjectProvider<ExternalIdentityProvider> providers;

	private final ObjectProvider<DevLoginMarker> devLogin;

	private final StepUps stepUps;

	IdpReauthenticationChannel(ObjectProvider<ExternalIdentityProvider> providers,
			ObjectProvider<DevLoginMarker> devLogin, StepUps stepUps) {
		this.providers = providers;
		this.devLogin = devLogin;
		this.stepUps = stepUps;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public int authWay() {
		return AUTH_WAY;
	}

	@Override
	public boolean available() {
		return this.providers.getIfAvailable() != null || this.devLogin.getIfAvailable() != null;
	}

	@Override
	public Start start(long userId, long sessionId) {
		return new Start(null, null, this.providers.getIfAvailable() != null ? REAUTH_PATH : null);
	}

	@Override
	public boolean verify(Pending challenge, String code) {
		if (code != null) {
			return false;
		}
		return this.devLogin.getIfAvailable() != null
				? this.stepUps.reauthenticatedSince(challenge.sessionId(), challenge.createdAt(), StepUps.METHOD_SSO,
						StepUps.METHOD_DEV)
				: this.stepUps.reauthenticatedSince(challenge.sessionId(), challenge.createdAt(), StepUps.METHOD_SSO);
	}

}
