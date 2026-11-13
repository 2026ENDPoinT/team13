package com.stackcorp.astra.integration.eclass;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 학교 e-Class 학번·비밀번호 로그인 설정. {@code base-url}이 있을 때만 읽는다. 값이 잘못되면 앱이 시작하지 않는다. 시작할 때 학교
 * 서버를 부르지 않는다.
 *
 * @param baseUrl 학교 e-Class origin. 예: {@code https://eclass.seoultech.ac.kr}. http는 loopback 주소(테스트의 가짜 학교
 * 서버)에만 허용한다
 * @param timeout 확인 한 번(요청 여러 개)의 전체 제한 시간
 */
@ConfigurationProperties("astra.eclass-login")
record EclassLoginProperties(URI baseUrl, @DefaultValue("10s") Duration timeout) {

	EclassLoginProperties {
		if (baseUrl == null || baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null
				|| baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
				|| !(baseUrl.getRawPath() == null || baseUrl.getRawPath().isEmpty() || "/".equals(baseUrl.getRawPath()))) {
			throw new IllegalArgumentException("astra.eclass-login.base-url은 경로 없는 origin이어야 합니다.");
		}
		if (!"https".equals(baseUrl.getScheme()) && !("http".equals(baseUrl.getScheme()) && loopback(baseUrl.getHost()))) {
			throw new IllegalArgumentException("astra.eclass-login.base-url은 https여야 합니다.");
		}
		if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0) {
			throw new IllegalArgumentException("astra.eclass-login.timeout은 0보다 크고 1분 이하여야 합니다.");
		}
		baseUrl = baseUrl.resolve("/");
	}

	// 이름이 아니라 주소 literal만 본다. DNS를 조회하지 않는다.
	private static boolean loopback(String host) {
		String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
		if (!literal.matches("[0-9a-fA-F:.]+")) {
			return false;
		}
		try {
			return InetAddress.getByName(literal).isLoopbackAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

}
