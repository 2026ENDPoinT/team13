package com.stackcorp.astra.identity.session;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.stackcorp.astra.identity.api.Actor;

/**
 * session으로 인증한 요청. 권한은 role이 아니라 모듈별 Policy가 객체 단위로 판정한다(ADR-0004 5번).
 */
final class ActorAuthentication extends AbstractAuthenticationToken {

	private final Actor actor;

	ActorAuthentication(Actor actor) {
		super(List.of(new SimpleGrantedAuthority(actor.breakGlass() ? "BREAK_GLASS" : "USER")));
		this.actor = actor;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public Actor getPrincipal() {
		return this.actor;
	}

}
