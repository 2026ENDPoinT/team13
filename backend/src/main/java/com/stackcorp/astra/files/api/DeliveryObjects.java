package com.stackcorp.astra.files.api;

import java.util.Optional;

/**
 * delivery bucket의 객체 읽기. CDN 없이 앱이 전달 경로를 직접 응답할 때만 쓴다(ADR-0009). 호출하는 쪽이 서명을 먼저 확인한다.
 */
public interface DeliveryObjects {

	/**
	 * @param path doc/{renditionKey}/NNN.jpg 또는 vod/{assetKey}/{파일}. 이 형식이 아니면 비어 있는 값을 돌려준다
	 */
	Optional<DeliveryObject> find(String path);

	record DeliveryObject(byte[] content, String contentType) {
	}

}
