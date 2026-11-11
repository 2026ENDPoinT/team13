package com.stackcorp.astra.identity.api;

import java.net.URI;
import java.util.Map;

/**
 * 학교 IdP 로그인 port(ADR-0004 1번, ADR-0017). {@code integration}이 프로토콜별 adapter로 구현한다. 지금 구현은 OpenID
 * Connect authorization code flow + PKCE다. IdP가 설정되지 않았으면 bean이 없다.
 * <p>
 * 서버는 로그인 시작과 callback 사이에 아무것도 기억하지 않는다. adapter가 이 브라우저에 묶어 둘 값(state, nonce, PKCE
 * verifier, 돌아갈 경로)을 암호화한 {@code binding}으로 돌려주고, {@code identity}가 그 값을 로그인 전용 쿠키로 브라우저에
 * 맡겼다가 callback에서 그대로 돌려준다.
 */
public interface ExternalIdentityProvider {

	/**
	 * 로그인을 시작한다.
	 * @param returnTo 로그인 뒤 돌아갈 같은 origin의 상대 경로. 호출자가 이미 검사한 값이다
	 * @throws ExternalLoginException IdP 정보를 받지 못했을 때
	 */
	LoginRedirect begin(String returnTo);

	/**
	 * 이미 로그인한 사용자의 재인증을 시작한다(ADR-0029). IdP에 기존 IdP session을 쓰지 말고 다시 인증하라고 요구한다(OIDC
	 * {@code prompt=login}, {@code max_age=0}). callback에서 IdP가 이 시작 뒤에 인증했다는 근거(OIDC {@code auth_time})를
	 * 확인하지 못하면 {@link #complete}가 실패한다.
	 * @param returnTo 재인증 뒤 돌아갈 같은 origin의 상대 경로. 호출자가 이미 검사한 값이다
	 * @throws ExternalLoginException IdP 정보를 받지 못했을 때
	 */
	LoginRedirect beginReauthentication(String returnTo);

	/**
	 * callback을 검증하고 사용자를 식별한다. 실패 이유는 예외의 reason으로만 구분한다.
	 * @param binding {@link #begin} 또는 {@link #beginReauthentication}이 돌려준 값. 쿠키가 없으면 null
	 * @param callback callback query parameter. 같은 이름이 두 번 온 parameter는 호출자가 거부한다
	 * @throws ExternalLoginException 검증에 실패했을 때
	 */
	ExternalLogin complete(String binding, Map<String, String> callback);

	/**
	 * @param location 브라우저를 보낼 IdP 주소
	 * @param binding 로그인 전용 쿠키에 넣을 값. 암호화되어 있다
	 */
	record LoginRedirect(URI location, String binding) {

		@Override
		public String toString() {
			return "LoginRedirect[location=" + this.location.getHost() + "]";
		}

	}

	/**
	 * @param externalRef 설정한 claim의 값. {@code users.external_ref}와 맞춘다
	 * @param returnTo 시작할 때 넘긴 경로
	 * @param reauthentication {@link #beginReauthentication}으로 시작했고 IdP가 그 뒤에 다시 인증했다
	 */
	record ExternalLogin(String externalRef, String returnTo, boolean reauthentication) {
	}

}
