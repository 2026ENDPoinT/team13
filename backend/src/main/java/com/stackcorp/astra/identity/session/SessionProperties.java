package com.stackcorp.astra.identity.session;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param idleTimeout 마지막 요청 뒤 이 시간이 지나면 session을 끝낸다
 * @param absoluteTimeout 로그인 뒤 이 시간이 지나면 session을 끝낸다
 * @param allowedOrigins 상태를 바꾸는 요청에 허용하는 Origin. 브라우저가 보내는 형식({@code scheme://host[:port]}, 소문자, 기본
 * port와 끝의 {@code /} 없이) 그대로 적는다. 비우면 요청 자신의 origin만 허용한다. 앱 앞에 proxy(CloudFront, 프론트엔드 서버)가
 * 있으면 앱이 보는 Host와 scheme이 브라우저의 것과 달라 비워 둘 수 없다(compose.yaml이 요구한다)
 */
@ConfigurationProperties("astra.session")
public record SessionProperties(@DefaultValue("ASTRA_SESSION") String cookieName,
		@DefaultValue("2h") Duration idleTimeout, @DefaultValue("12h") Duration absoluteTimeout,
		@DefaultValue("true") boolean secureCookie, @DefaultValue List<String> allowedOrigins) {

	public SessionProperties {
		allowedOrigins.forEach(SessionProperties::requireSerializedOrigin);
	}

	// OriginCheckFilter는 문자열을 그대로 비교하므로, 브라우저가 보낼 수 없는 모양(끝의 /, 경로, 대문자, 기본 port)은 어떤 요청과도 맞지
	// 않는다. 그런 값은 모든 POST·PUT·DELETE를 403으로 만들므로 시작할 때 막는다.
	private static void requireSerializedOrigin(String origin) {
		String expected = null;
		try {
			URI uri = new URI(origin);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			boolean defaultPort = ("http".equals(scheme) && uri.getPort() == 80)
					|| ("https".equals(scheme) && uri.getPort() == 443);
			if (("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null
					&& uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null) {
				expected = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
						+ ((uri.getPort() == -1 || defaultPort) ? "" : ":" + uri.getPort());
			}
		}
		catch (URISyntaxException ex) {
			// 아래에서 거부한다.
		}
		if (!origin.equals(expected)) {
			throw new IllegalArgumentException("astra.session.allowed-origins(ASTRA_SESSION_ALLOWED_ORIGINS)의 값 '" + origin
					+ "'은 브라우저가 보내는 Origin 형식이 아닙니다. scheme://host[:port]를 소문자로, 기본 port와 끝의 / 없이 적으세요"
					+ (expected == null ? "." : ": " + expected));
		}
	}

}
