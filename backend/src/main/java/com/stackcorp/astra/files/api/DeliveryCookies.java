package com.stackcorp.astra.files.api;

import java.time.Duration;
import java.util.List;

/**
 * 전달 경로의 signed cookie 발급(ADR-0007 6번). 호출하는 쪽이 먼저 권한을 판정한다.
 * CDN 서명이 설정되지 않은 환경에서는 빈 목록을 돌려준다.
 */
public interface DeliveryCookies {

	List<DeliveryCookie> forDocument(String renditionKey);

	List<DeliveryCookie> forVideo(String assetKey);

	record DeliveryCookie(String name, String value, String path, Duration maxAge) {
	}

}
