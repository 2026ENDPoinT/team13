package com.stackcorp.astra.identity.session;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * 모든 요청은 session 쿠키로 인증하고, 객체 권한은 모듈별 Policy가 판정한다. HttpSession은 쓰지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionProperties.class)
class SecurityConfiguration {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, SessionStore sessions, SessionProperties properties,
			BreakGlassMarker breakGlass) throws Exception {
		http.sessionManagement((s) -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.securityContext((c) -> c.requireExplicitSave(true))
			.requestCache((c) -> c.disable())
			.formLogin((f) -> f.disable())
			.httpBasic((b) -> b.disable())
			.logout((l) -> l.disable())
			.csrf((csrf) -> csrf.spa())
			.addFilterBefore(new OriginCheckFilter(properties), CsrfFilter.class)
			.addFilterBefore(new MultipartCsrfHeaderFilter(), CsrfFilter.class)
			.addFilterBefore(new SessionAuthenticationFilter(sessions, properties, breakGlass),
					AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests((a) -> a.requestMatchers(HttpMethod.GET, "/api/auth/csrf")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/break-glass", "/api/auth/eclass-login")
				.permitAll()
				// 학교 IdP 로그인 시작·callback과 로그인 수단 조회(ADR-0017). 모두 GET이고 DB 상태를 바꾸는 것은 callback의 로그인뿐이다.
				.requestMatchers(HttpMethod.GET, "/api/auth/login", "/api/auth/callback", "/api/auth/methods")
				.permitAll()
				// 재인증 시작은 브라우저의 top-level 이동이다. session이 없거나 만료됐으면 401 JSON 대신 handler가 돌아갈 경로에
				// reauth=failed를 붙여 보낸다(ADR-0029). handler는 actor가 없으면 그 일만 하고 session을 만들지 않는다.
				.requestMatchers(HttpMethod.GET, "/api/auth/reauth")
				.permitAll()
				.requestMatchers("/error")
				.permitAll()
				.requestMatchers("/api/**")
				.authenticated()
				// CDN 없이 앱이 전달 경로를 응답할 때만 handler가 있다(ADR-0009). 그때도 session과 경로 서명을 함께 요구한다.
				.requestMatchers(HttpMethod.GET, "/doc/**", "/vod/**")
				.authenticated()
				.anyRequest()
				.denyAll())
			.exceptionHandling((e) -> e
				.authenticationEntryPoint((req, res, ex) -> ProblemResponses.write(res, 401, "unauthorized",
						"로그인이 필요합니다."))
				.accessDeniedHandler((req, res, ex) -> ProblemResponses.write(res, 403, "forbidden",
						"요청을 처리할 수 없습니다. CSRF token을 확인해 주세요.")));
		return http.build();
	}

	/**
	 * firewall이 거부한 요청(경로의 {@code ;}, 인코딩한 {@code /} 등)은 400 problem JSON을 바로 쓴다. 기본 처리는 sendError(400)로
	 * {@code /error}에 넘기는데, 그곳의 DispatcherServlet이 multipart 본문 전체를 임시 디스크로 받는다. 400 응답 뒤 Tomcat은 남은 본문을
	 * {@code server.tomcat.max-swallow-size}(2MB)까지 읽어 버리고 연결을 닫는다.
	 */
	@Bean
	RequestRejectedHandler requestRejectedHandler() {
		return (request, response, ex) -> ProblemResponses.write(response, 400, "invalid_request",
				"요청 형식이 올바르지 않습니다.");
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
