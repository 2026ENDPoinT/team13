package com.stackcorp.astra.files;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 원본과 생성물 내려받기의 응답 header(FILES-09). 브라우저가 내용을 문서로 해석하지 못하도록 언제나 attachment로 응답하고,
 * 캐시와 형식 추측을 막는다. 파일 이름은 RFC 5987 UTF-8 형식으로 그대로 보낸다.
 */
public final class Downloads {

	private Downloads() {
	}

	/**
	 * @param mimeType 저장할 때 내용으로 판정한 MIME. 없으면 application/octet-stream
	 */
	public static ResponseEntity<InputStreamResource> attachment(String name, String mimeType, long sizeBytes,
			InputStream content) {
		return headers(ResponseEntity.ok(), name, mimeType).contentLength(sizeBytes).body(new InputStreamResource(content));
	}

	/**
	 * 구간 요청(Range)을 받는 attachment 응답. 오디오처럼 재생 중에 탐색하는 내용에 쓴다. 언제나 {@code Accept-Ranges: bytes}를 보낸다.
	 *
	 * @param totalBytes 내용 전체 크기
	 * @param first 보낼 첫 byte 위치. 전체를 보내면 0
	 * @param last 보낼 마지막 byte 위치(포함). 전체를 보내면 totalBytes - 1
	 * @param partial true면 206과 Content-Range, false면 200 전체
	 * @param content first부터 last까지의 내용
	 */
	public static ResponseEntity<InputStreamResource> ranged(String name, String mimeType, long totalBytes, long first,
			long last, boolean partial, InputStream content) {
		ResponseEntity.BodyBuilder response = partial ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
			.header(HttpHeaders.CONTENT_RANGE, "bytes " + first + "-" + last + "/" + totalBytes) : ResponseEntity.ok();
		return headers(response, name, mimeType).header(HttpHeaders.ACCEPT_RANGES, "bytes")
			.contentLength(last - first + 1)
			.body(new InputStreamResource(content));
	}

	/**
	 * @param mimeType 저장할 때 내용으로 판정한 MIME. 없으면 application/octet-stream
	 */
	private static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder response, String name,
			String mimeType) {
		String type = mimeType != null ? mimeType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
		return response.contentType(MediaType.parseMediaType(type))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'; sandbox")
			.header("Cross-Origin-Resource-Policy", "same-origin");
	}

}
