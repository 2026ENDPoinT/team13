package com.stackcorp.astra.integration.llm;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 모델 제공자 설정(ADR-0008). 기본값은 DeepSeek다.
 *
 * @param baseUrl 제공자 주소. 이 주소 뒤에 /chat/completions를 붙여 부른다. 허용하는 외부 host는 이 하나다
 * @param vision 설정한 model이 이미지 입력을 받는지
 * @param timeout 요청 하나의 전체 제한 시간
 * @param maxConcurrency 동시에 보내는 요청 수 상한
 * @param extraBody 요청 본문에 그대로 더하는 제공자 전용 값. 예: thinking.type=disabled
 */
@ConfigurationProperties("astra.llm")
record LlmProperties(@DefaultValue("https://api.deepseek.com") URI baseUrl,
		@DefaultValue("deepseek-flash") String model, String apiKey, @DefaultValue("true") boolean vision,
		@DefaultValue("300s") Duration timeout, @DefaultValue("2") int maxConcurrency,
		@DefaultValue("8MB") DataSize maxResponseBytes, Map<String, Object> extraBody) {

	LlmProperties {
		// key를 엉뚱한 곳으로 보내지 않도록 주소 모양을 시작할 때 확인한다.
		if (baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
				|| baseUrl.getFragment() != null) {
			throw new IllegalArgumentException("astra.llm.base-url은 host가 있고 계정 정보, query, fragment가 없어야 합니다.");
		}
		boolean https = "https".equalsIgnoreCase(baseUrl.getScheme());
		if (!https && !("http".equalsIgnoreCase(baseUrl.getScheme()) && isLoopback(baseUrl.getHost()))) {
			throw new IllegalArgumentException("astra.llm.base-url은 https여야 합니다. http는 loopback 주소에만 허용합니다.");
		}
		if (maxConcurrency < 1) {
			throw new IllegalArgumentException("astra.llm.max-concurrency는 1 이상이어야 합니다.");
		}
		extraBody = extraBody != null ? Map.copyOf(extraBody) : Map.of();
	}

	// record 기본 toString은 key를 그대로 내보낸다.
	@Override
	public String toString() {
		return "LlmProperties[baseUrl=" + this.baseUrl + ", model=" + this.model + ", vision=" + this.vision + "]";
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
