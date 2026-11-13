package com.stackcorp.astra.identity.session;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.identity.stepup.StepUpProperties;
import com.stackcorp.astra.identity.stepup.StepUps;
import com.stackcorp.astra.shared.ApiException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개발용 로그인과 개발용 재인증. 테스트 소스에만 있어서 배포하는 jar에는 들어가지 않는다. 로컬 실행(TestStackBackendApplication)만
 * 불러온다. 이 컴퓨터에서 온 요청만 받는다. proxy가 붙인 X-Forwarded-For, Forwarded, CF-Connecting-IP, X-Real-IP에 다른 주소가
 * 있으면 거부한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DevLoginConfiguration {

	static final String PATH = "/api/auth/dev-login";

	public static final String STEP_UP_PATH = "/api/me/step-up/dev";

	@Bean
	@Order(0)
	SecurityFilterChain devLoginSecurity(HttpSecurity http, SessionProperties properties) throws Exception {
		http.securityMatcher(PATH)
			.sessionManagement((s) -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache((c) -> c.disable())
			.csrf((csrf) -> csrf.spa())
			.addFilterBefore(new OriginCheckFilter(properties), CsrfFilter.class)
			.authorizeHttpRequests((a) -> a.anyRequest().permitAll())
			.exceptionHandling((e) -> e.accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, 403, "forbidden",
					"요청을 처리할 수 없습니다. CSRF token을 확인해 주세요.")));
		return http.build();
	}

	@RestController
	static class DevLoginController implements DevLoginMarker {

		private final JdbcClient jdbc;

		private final SessionStore sessions;

		private final SessionProperties properties;

		private final BreakGlassMarker breakGlass;

		private final StepUps stepUps;

		private final StepUpProperties stepUpProperties;

		private final Set<String> allowedRemoteAddresses;

		private final ClientAddresses clientAddresses;

		DevLoginController(JdbcClient jdbc, SessionStore sessions, SessionProperties properties,
				BreakGlassMarker breakGlass, StepUps stepUps, StepUpProperties stepUpProperties,
				@Value("${astra.dev-login.allowed-remote-addresses:}") List<String> allowedRemoteAddresses,
				ClientAddresses clientAddresses) {
			this.jdbc = jdbc;
			this.sessions = sessions;
			this.properties = properties;
			this.breakGlass = breakGlass;
			this.stepUps = stepUps;
			this.stepUpProperties = stepUpProperties;
			this.allowedRemoteAddresses = Set.copyOf(allowedRemoteAddresses);
			this.clientAddresses = clientAddresses;
		}

		@PostMapping(PATH)
		ResponseEntity<Void> login(@RequestBody DevLogin body, HttpServletRequest request) {
			if (!local(request)) {
				throw ApiException.notFound();
			}
			Long userId = this.jdbc
				.sql("SELECT user_id FROM users WHERE external_ref = :ref AND account_status = 'ACTIVE'")
				.param("ref", body.externalRef())
				.query(Long.class)
				.optional()
				.filter((id) -> !this.breakGlass.isBreakGlass(id))
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "rejected", "로그인 정보를 확인해 주세요."));
			IssuedSession session = this.sessions.create(userId, DeviceCookie.read(request), this.clientAddresses.of(request),
					request.getHeader(HttpHeaders.USER_AGENT));
			ResponseCookie cookie = ResponseCookie.from(this.properties.cookieName(), session.token())
				.httpOnly(true)
				.secure(this.properties.secureCookie())
				.sameSite("Lax")
				.path("/")
				.maxAge(this.properties.absoluteTimeout())
				.build();
			return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookie.toString(),
						DeviceCookie.issue(session.deviceUuid(), this.properties).toString(),
						CsrfCookie.renew(request).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.build();
		}

		/**
		 * 학교 IdP가 없는 로컬 실행의 재인증(ADR-0029). IdP에서 다시 로그인한 것처럼 지금 session에 재인증을 기록한다. 학습 인증의
		 * {@code idp} 수단도 이 기록을 근거로 받는다. 일반 API chain을 지나므로 session과 CSRF token이 필요하다.
		 */
		@PostMapping(STEP_UP_PATH)
		ResponseEntity<Void> stepUp(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
			if (!local(request) || actor.breakGlass()) {
				throw ApiException.notFound();
			}
			this.stepUps.grant(actor.sessionId(), StepUps.RECENT_AUTH, StepUps.METHOD_DEV,
					this.stepUpProperties.recentAuth());
			return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
		}

		private boolean local(HttpServletRequest request) {
			String remote = request.getRemoteAddr();
			if (!loopback(remote) && !this.allowedRemoteAddresses.contains(remote)) {
				return false;
			}
			if (request.getHeader("Forwarded") != null || request.getHeader("CF-Connecting-IP") != null
					|| request.getHeader("X-Real-IP") != null) {
				return false;
			}
			String forwarded = request.getHeader("X-Forwarded-For");
			if (forwarded != null) {
				for (String hop : forwarded.split(",")) {
					if (!loopback(hop.strip())) {
						return false;
					}
				}
			}
			return true;
		}

		// 이름이 아니라 주소 literal만 본다. DNS를 조회하지 않는다.
		private static boolean loopback(String address) {
			if (address == null || !address.matches("[0-9a-fA-F:.]+")) {
				return false;
			}
			try {
				return InetAddress.getByName(address).isLoopbackAddress();
			}
			catch (UnknownHostException ex) {
				return false;
			}
		}

	}

	record DevLogin(String externalRef) {
	}

}
