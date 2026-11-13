package com.stackcorp.astra.integration.deepgram;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Deepgram 사전 녹음 음성 인식 설정(ADR-0041). key가 없으면 녹음 전사·요약이 꺼진다. 시작할 때 모양을 확인한다.
 *
 * @param apiKey Deepgram API key. 환경 변수 ASTRA_DEEPGRAM_API_KEY. 비우면 기능이 꺼진다
 * @param url 제공자 주소. 이 주소 뒤에 /v1/listen을 붙여 부른다. 허용하는 외부 host는 이 하나다. EU 처리는 https://api.eu.deepgram.com
 * @param model 인식 모델
 * @param language 인식 언어. multi는 여러 언어가 섞인 말(code-switching)을 한 모델로 받는다
 * @param languageHints language가 multi일 때 감지를 기울일 언어. 다른 language에서는 보내지 않는다
 * @param timeout 요청 하나의 전체 제한 시간(본문 보내기와 응답 읽기 포함). Deepgram은 처리 10분을 넘으면 504로 끊는다
 * @param maxResponseBytes 응답 본문 상한. 단어별 정보(words)가 함께 와서 강의 2시간이면 수 MB다
 */
@ConfigurationProperties("astra.deepgram")
record DeepgramProperties(String apiKey, @DefaultValue("https://api.deepgram.com") URI url,
		@DefaultValue("nova-3") String model, @DefaultValue("multi") String language,
		@DefaultValue({ "ko", "en" }) List<String> languageHints, @DefaultValue("15m") Duration timeout,
		@DefaultValue("64MB") DataSize maxResponseBytes) {

	private static final Pattern MODEL = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

	private static final Pattern LANGUAGE = Pattern.compile("multi|[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}");

	DeepgramProperties {
		// key를 엉뚱한 곳으로 보내지 않도록 주소 모양을 시작할 때 확인한다.
		if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null
				|| url.getFragment() != null) {
			throw new IllegalArgumentException("astra.deepgram.url은 host가 있고 계정 정보, query, fragment가 없어야 합니다.");
		}
		boolean https = "https".equalsIgnoreCase(url.getScheme());
		if (!https && !("http".equalsIgnoreCase(url.getScheme()) && isLoopback(url.getHost()))) {
			throw new IllegalArgumentException("astra.deepgram.url은 https여야 합니다. http는 loopback 주소에만 허용합니다.");
		}
		if (!MODEL.matcher(model).matches()) {
			throw new IllegalArgumentException("astra.deepgram.model은 소문자, 숫자, . _ -로 된 모델 이름이어야 합니다.");
		}
		if (!LANGUAGE.matcher(language).matches()) {
			throw new IllegalArgumentException("astra.deepgram.language는 multi 또는 BCP-47 언어 tag여야 합니다.");
		}
		languageHints = languageHints == null ? List.of() : List.copyOf(languageHints);
		for (String hint : languageHints) {
			if ("multi".equals(hint) || !LANGUAGE.matcher(hint).matches()) {
				throw new IllegalArgumentException("astra.deepgram.language-hints는 BCP-47 언어 tag 목록이어야 합니다.");
			}
		}
		if (timeout.isNegative() || timeout.isZero() || maxResponseBytes.toBytes() < 1) {
			throw new IllegalArgumentException("astra.deepgram.timeout과 max-response-bytes는 0보다 커야 합니다.");
		}
	}

	boolean configured() {
		return this.apiKey != null && !this.apiKey.isBlank();
	}

	// record 기본 toString은 key를 그대로 내보낸다.
	@Override
	public String toString() {
		return "DeepgramProperties[url=" + this.url + ", model=" + this.model + ", language=" + this.language
				+ ", configured=" + configured() + "]";
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
