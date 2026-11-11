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
			.addFilterBefore(new SessionAuthenticationFilter(sessions, properties, breakGlass),
					AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests((a) -> a.requestMatchers(HttpMethod.GET, "/api/auth/csrf")
				.permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/break-glass")
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

	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

}
