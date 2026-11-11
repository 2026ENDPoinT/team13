package com.stackcorp.astra.identity.session;

import java.io.IOException;
import java.time.Instant;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.session.SessionStore.ActiveSession;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * session 쿠키로 요청을 인증한다. 만료된 session은 이 자리에서 폐기한다.
 */
final class SessionAuthenticationFilter extends OncePerRequestFilter {

	private final SessionStore sessions;

	private final SessionProperties properties;

	private final BreakGlassMarker breakGlass;

	SessionAuthenticationFilter(SessionStore sessions, SessionProperties properties, BreakGlassMarker breakGlass) {
		this.sessions = sessions;
		this.properties = properties;
		this.breakGlass = breakGlass;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = token(request);
		if (token != null) {
			this.sessions.find(token).ifPresent((session) -> {
				if (!usable(session)) {
					this.sessions.revoke(session.sessionId(), "EXPIRED");
					return;
				}
				this.sessions.touch(session.sessionId());
				Actor actor = new Actor(session.userId(), session.name(), session.role(),
						this.breakGlass.isBreakGlass(session.userId()), session.sessionId());
				var context = SecurityContextHolder.createEmptyContext();
				context.setAuthentication(new ActorAuthentication(actor));
				SecurityContextHolder.setContext(context);
			});
		}
		chain.doFilter(request, response);
	}

	private boolean usable(ActiveSession session) {
		Instant now = Instant.now();
		Instant lastSeen = session.lastSeenAt() != null ? session.lastSeenAt() : session.authenticatedAt();
		return session.active() && session.authenticatedAt().plus(this.properties.absoluteTimeout()).isAfter(now)
				&& lastSeen.plus(this.properties.idleTimeout()).isAfter(now);
	}

	private String token(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (this.properties.cookieName().equals(cookie.getName()) && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
