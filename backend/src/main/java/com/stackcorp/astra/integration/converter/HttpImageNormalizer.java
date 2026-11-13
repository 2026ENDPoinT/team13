package com.stackcorp.astra.integration.converter;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import com.stackcorp.astra.files.api.ImageNormalizer;
import com.stackcorp.astra.files.api.ImageNormalizer.NormalizationException.Code;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * converter process의 {@code POST /avatar}를 부른다(ADR-0038). 요청은 이미지 바이트뿐이고 URL이나 경로를 담지 않는다. 응답은 크기
 * 상한 안의 JPEG여야 한다. redirect를 따르지 않는다.
 */
final class HttpImageNormalizer implements ImageNormalizer, AutoCloseable {

	/** 256px JPEG가 넘지 않는 응답 크기 상한. */
	static final int MAX_RESPONSE_BYTES = 1 << 20;

	private static final int ERROR_BODY_BYTES = 4000;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final URI endpoint;

	private final Duration timeout;

	private final HttpClient http;

	HttpImageNormalizer(ConverterProperties properties) {
		String base = properties.url().toString();
		this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/avatar");
		this.timeout = properties.timeout();
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	}

	@Override
	public byte[] avatar(byte[] image) {
		HttpRequest request = HttpRequest.newBuilder(this.endpoint)
			.header("content-type", "application/octet-stream")
			.timeout(this.timeout)
			.POST(HttpRequest.BodyPublishers.ofByteArray(image))
			.build();
		HttpResponse<InputStream> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (ConnectException ex) {
			throw new NormalizationException(Code.UNAVAILABLE, "converter에 연결하지 못했습니다.", ex);
		}
		catch (HttpTimeoutException ex) {
			throw new NormalizationException(Code.TIMEOUT, "converter가 제한 시간 안에 응답하지 않았습니다.", ex);
		}
		catch (IOException ex) {
			throw new NormalizationException(Code.UNAVAILABLE, "converter 호출이 실패했습니다.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new NormalizationException(Code.UNAVAILABLE, "변환을 기다리다 중단되었습니다.", ex);
		}
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw failure(response.statusCode(), body.readNBytes(ERROR_BODY_BYTES));
			}
			if (!response.headers().firstValue("content-type").orElse("").equals("image/jpeg")) {
				throw new NormalizationException(Code.FAILED, "converter 응답이 JPEG가 아닙니다.");
			}
			byte[] jpeg = body.readNBytes(MAX_RESPONSE_BYTES + 1);
			if (jpeg.length > MAX_RESPONSE_BYTES || jpeg.length < 3 || (jpeg[0] & 0xFF) != 0xFF
					|| (jpeg[1] & 0xFF) != 0xD8 || (jpeg[2] & 0xFF) != 0xFF) {
				throw new NormalizationException(Code.FAILED, "converter 응답이 JPEG 상한이나 형식에 맞지 않습니다.");
			}
			return jpeg;
		}
		catch (IOException ex) {
			throw new NormalizationException(Code.UNAVAILABLE, "converter 응답을 읽지 못했습니다.", ex);
		}
	}

	@Override
	public void close() {
		this.http.close();
	}

	private static NormalizationException failure(int status, byte[] body) {
		String code = "";
		try {
			code = JSON.readTree(body).path("code").asString("");
		}
		catch (JacksonException ex) {
			// 본문이 JSON이 아니면 상태 코드만 쓴다.
		}
		Code mapped = switch (code) {
			case "not_image" -> Code.NOT_IMAGE;
			case "too_large" -> Code.TOO_LARGE;
			default -> status >= 500 ? Code.UNAVAILABLE : Code.FAILED;
		};
		return new NormalizationException(mapped, "converter가 이미지를 다시 만들지 못했습니다 (HTTP " + status + ", "
				+ mapped.value() + ")");
	}

}
