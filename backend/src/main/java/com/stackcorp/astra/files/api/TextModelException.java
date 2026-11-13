package com.stackcorp.astra.files.api;

/**
 * 글 생성 실패. message에는 제공자 응답 본문, 입력 글, key를 넣지 않는다.
 */
public class TextModelException extends RuntimeException {

	public enum Kind {
		/** 요청 한도, 서버 오류, 연결 실패, 빈 응답. 다시 보내면 성공할 수 있다. */
		UNAVAILABLE(true),
		/** key 거부, 잘못된 요청, 제공자 없음. 다시 보내도 같다. */
		CONFIG(false),
		/** 응답이 출력 token 상한에서 잘렸다. 같은 요청을 다시 보내도 대개 다시 잘린다. */
		TRUNCATED(false);

		private final boolean retryable;

		Kind(boolean retryable) {
			this.retryable = retryable;
		}

		public boolean retryable() {
			return this.retryable;
		}
	}

	private final Kind kind;

	public TextModelException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return this.kind;
	}

	public boolean retryable() {
		return this.kind.retryable();
	}

}
