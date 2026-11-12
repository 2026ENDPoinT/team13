package com.stackcorp.astra.files.api;

import java.util.Locale;

/**
 * 신뢰할 수 없는 이미지 바이트를 격리한 converter에서 해석해 정해진 형식으로 다시 만든다(ADR-0038, ADR-0033 4번). api와 worker
 * process는 올린 이미지를 디코딩하지 않는다. 구현은 integration 모듈의 converter client다.
 */
public interface ImageNormalizer {

	/** 아바타 사진의 한 변(px). 결과는 언제나 이 크기의 정사각형 JPEG다. */
	int AVATAR_SIZE = 256;

	/**
	 * PNG나 JPEG 바이트를 가운데 정사각형으로 잘라 {@value #AVATAR_SIZE}px JPEG로 다시 인코딩한다. 메타데이터와 투명도(흰 배경)는
	 * 남기지 않는다.
	 * @throws NormalizationException 읽을 수 없거나 상한을 넘거나 converter에 닿지 못했을 때
	 */
	byte[] avatar(byte[] image);

	class NormalizationException extends RuntimeException {

		public enum Code {
			/** PNG나 JPEG로 읽을 수 없다. */
			NOT_IMAGE(false),
			/** 바이트나 가로·세로가 상한을 넘는다. */
			TOO_LARGE(false),
			/** converter가 제한 시간 안에 끝내지 못했다. */
			TIMEOUT(true),
			/** converter에 닿지 못했다. */
			UNAVAILABLE(true),
			/** converter 응답이 계약과 다르다. */
			FAILED(false);

			private final boolean retryable;

			Code(boolean retryable) {
				this.retryable = retryable;
			}

			/** 같은 입력으로 다시 시도하면 성공할 수 있는지. */
			public boolean retryable() {
				return this.retryable;
			}

			public String value() {
				return name().toLowerCase(Locale.ROOT);
			}

		}

		private final Code code;

		public NormalizationException(Code code, String message) {
			super(message);
			this.code = code;
		}

		public NormalizationException(Code code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public Code code() {
			return this.code;
		}

	}

}
