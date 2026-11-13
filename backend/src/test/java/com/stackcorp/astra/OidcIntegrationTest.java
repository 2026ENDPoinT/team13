package com.stackcorp.astra;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.stackcorp.astra.identity.session.DevLoginConfiguration;
import com.stackcorp.astra.integration.oidc.MockOpenIdProvider;

/**
 * 학교 도입 구성의 통합 테스트 공통 설정. 같은 process의 가짜 OpenID Provider를 issuer로 두고, 온라인 학습에 학습 인증 grant를
 * 요구한다(SC-06-A). 공유 context와 설정이 달라 context를 하나 더 쓰고, 이 class를 상속한 테스트끼리는 그 context를 함께 쓴다.
 * 로컬 실행처럼 개발용 로그인 구성도 불러온다.
 * <p>
 * 가짜 IdP는 context가 시작하기 전부터 503으로만 응답한다. context가 뜬 직후 IdP가 받은 요청 수를
 * {@link #REQUESTS_AT_STARTUP}에 남긴다. 시작할 때 IdP를 불렀다면 0이 아니다.
 */
@Import({ DevLoginConfiguration.class, OidcIntegrationTest.StartupProbe.class })
@TestPropertySource(properties = "astra.learning.require-auth-grant=true")
public abstract class OidcIntegrationTest extends IntegrationTest {

	public static final String REDIRECT_URI = "http://localhost/api/auth/callback";

	public static final MockOpenIdProvider IDP = downAtStartup();

	public static final AtomicInteger REQUESTS_AT_STARTUP = new AtomicInteger(-1);

	@DynamicPropertySource
	static void oidc(DynamicPropertyRegistry registry) {
		registry.add("astra.oidc.issuer", IDP::issuer);
		registry.add("astra.oidc.client-id", () -> MockOpenIdProvider.CLIENT_ID);
		registry.add("astra.oidc.client-secret", () -> MockOpenIdProvider.CLIENT_SECRET);
		registry.add("astra.oidc.redirect-uri", () -> REDIRECT_URI);
		registry.add("astra.oidc.user-claim", () -> MockOpenIdProvider.USER_CLAIM);
		registry.add("astra.oidc.scopes", () -> "openid,profile");
	}

	private static MockOpenIdProvider downAtStartup() {
		MockOpenIdProvider idp = MockOpenIdProvider.start(REDIRECT_URI);
		idp.down(true);
		return idp;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class StartupProbe {

		@Bean
		SmartInitializingSingleton idpRequestsAtStartup() {
			return () -> REQUESTS_AT_STARTUP.compareAndSet(-1, IDP.requests());
		}

	}

}
