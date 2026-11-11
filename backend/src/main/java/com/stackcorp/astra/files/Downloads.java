package com.stackcorp.astra.files;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
		String type = mimeType != null ? mimeType : MediaType.APPLICATION_OCTET_STREAM_VALUE;
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(type))
			.contentLength(sizeBytes)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'; sandbox")
			.header("Cross-Origin-Resource-Policy", "same-origin")
			.body(new InputStreamResource(content));
	}

}
