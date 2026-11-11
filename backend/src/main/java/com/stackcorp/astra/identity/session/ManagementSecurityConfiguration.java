package com.stackcorp.astra.identity.session;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 관리 port(management.server.port)에서는 health만 연다. 관리 port는 container 안의 127.0.0.1에만 열고 API port의 경로에는
 * 없다(architecture.md 3.3절, ADR-0019). EndpointRequest는 관리 port가 따로 있으면 관리 port의 요청에만 맞는다. 그래서 API
 * port로 온 /actuator 요청은 이 chain이 아니라 API chain이 거부한다. 다른 actuator endpoint는 켜지 않고, 켜더라도 이 chain이
 * 거부한다.
 */
@Configuration(proxyBeanMethods = false)
class ManagementSecurityConfiguration {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SecurityFilterChain managementSecurity(HttpSecurity http) throws Exception {
		http.securityMatcher(EndpointRequest.toAnyEndpoint())
			.sessionManagement((s) -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache((c) -> c.disable())
			.formLogin((f) -> f.disable())
			.httpBasic((b) -> b.disable())
			.logout((l) -> l.disable())
			.authorizeHttpRequests((a) -> a.requestMatchers(EndpointRequest.to(HealthEndpoint.class))
				.permitAll()
				.anyRequest()
				.denyAll());
		return http.build();
	}

}
