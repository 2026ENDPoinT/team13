package com.stackcorp.astra.files.api;

import java.time.Instant;
import java.util.List;

/**
 * CDN 전달 경로의 서명 port. 구현은 integration에 있다(CloudFront signed cookie).
 */
public interface DeliverySigner {

	/**
	 * @param pathPrefix "/doc/{key}/" 같은 경로. 이 prefix 아래만 유효한 서명을 만든다
	 */
	List<SignedCookie> sign(String pathPrefix, Instant expiresAt);

	record SignedCookie(String name, String value) {
	}

}
