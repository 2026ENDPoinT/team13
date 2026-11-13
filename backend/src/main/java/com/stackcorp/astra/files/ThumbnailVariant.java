package com.stackcorp.astra.files;

import java.util.Optional;

/**
 * 썸네일 변형(EP-0118, FILES-13). 요청 경로의 값은 이 목록으로만 해석하고 저장 key에 이어 붙이지 않는다.
 */
enum ThumbnailVariant {

	/** 첫 쪽. 긴 변 360px JPEG. */
	THUMB_1("thumb_1");

	private final String code;

	ThumbnailVariant(String code) {
		this.code = code;
	}

	/** file_thumbnails.variant 값. */
	String code() {
		return this.code;
	}

	static Optional<ThumbnailVariant> of(String value) {
		for (ThumbnailVariant variant : values()) {
			if (variant.code.equals(value)) {
				return Optional.of(variant);
			}
		}
		return Optional.empty();
	}

}
