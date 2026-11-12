package com.stackcorp.astra.integration.oidc;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * 학교 IdP(OpenID Connect) 설정(ADR-0017). {@code issuer}가 있을 때만 읽는다. 값이 잘못되면 앱이 시작하지 않는다. IdP가
 * 응답하지 않는 것은 설정 오류가 아니므로 시작할 때 IdP를 부르지 않는다.
 *
 * @param issuer IdP의 issuer 식별자. ID token의 {@code iss}, discovery 문서의 {@code issuer}와 글자 그대로 같아야 한다
 * @param clientId IdP에 등록한 client ID
 * @param clientSecret IdP가 발급한 client secret. 로그인 전용 쿠키의 암호화 key도 이 값에서 만든다
 * @param redirectUri IdP에 등록한 callback 주소. 서비스 origin 뒤에 {@code /api/auth/callback}을 붙인 값이다
 * @param scopes 요청할 scope. {@code openid}가 있어야 한다
 * @param userClaim ID token에서 {@code users.external_ref}로 쓸 claim
 * @param allowedHosts issuer host 말고 discovery가 가리켜도 되는 https host. 비우면 issuer host만 허용한다
 * @param timeout IdP 요청 하나의 전체 제한 시간
 * @param clockSkew ID token 시각 검사에 허용하는 시계 오차
 */
@ConfigurationProperties("astra.oidc")
record OidcProperties(String issuer, String clientId, String clientSecret, URI redirectUri,
		@DefaultValue("openid") List<String> scopes, @DefaultValue("sub") String userClaim,
		@DefaultValue List<String> allowedHosts, @DefaultValue("10s") Duration timeout,
		@DefaultValue("60s") Duration clockSkew) {

	OidcProperties {
		URI issuerUri = parse(issuer, "astra.oidc.issuer");
		if (issuerUri.getRawQuery() != null) {
			throw new IllegalArgumentException("astra.oidc.issuer에는 query가 없어야 합니다.");
		}
		URI redirect = parse(redirectUri == null ? null : redirectUri.toString(), "astra.oidc.redirect-uri");
		if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
			throw new IllegalArgumentException("astra.oidc.client-id와 astra.oidc.client-secret이 필요합니다.");
		}
		if (!scopes.contains("openid")) {
			throw new IllegalArgumentException("astra.oidc.scopes에 openid가 있어야 합니다.");
		}
		if (!StringUtils.hasText(userClaim)) {
			throw new IllegalArgumentException("astra.oidc.user-claim이 비어 있습니다.");
		}
		if (timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("astra.oidc.timeout은 0보다 커야 합니다.");
		}
		if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
			throw new IllegalArgumentException("astra.oidc.clock-skew는 0에서 5분 사이여야 합니다.");
		}
		redirectUri = redirect;
		scopes = List.copyOf(scopes);
		allowedHosts = allowedHosts.stream().map((h) -> h.strip().toLowerCase(Locale.ROOT)).toList();
	}

	URI issuerUri() {
		return URI.create(this.issuer);
	}

	// record 기본 toString은 client secret을 그대로 내보낸다.
	@Override
	public String toString() {
		return "OidcProperties[issuer=" + this.issuer + ", clientId=" + this.clientId + ", redirectUri="
				+ this.redirectUri + ", scopes=" + this.scopes + ", userClaim=" + this.userClaim + "]";
	}

	// https만 받는다. http는 loopback 주소(테스트의 가짜 IdP)에만 허용한다(SC-08).
	private static URI parse(String value, String name) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(name + "가 비어 있습니다.");
		}
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			throw new IllegalArgumentException(name + "가 URI가 아닙니다.");
		}
		if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
			throw new IllegalArgumentException(name + "는 host가 있고 계정 정보와 fragment가 없어야 합니다.");
		}
		boolean https = "https".equalsIgnoreCase(uri.getScheme());
		if (!https && !("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()))) {
			throw new IllegalArgumentException(name + "는 https여야 합니다. http는 loopback 주소에만 허용합니다.");
		}
		return uri;
	}

	private static boolean isLoopback(String host) {
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

}
