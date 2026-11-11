package com.stackcorp.astra.files.api;

/**
 * 첨부로 확정할 수 없는 업로드가 있다. 어느 업로드인지와 이유는 밝히지 않는다. 다른 사람의 업로드 ID를 확인하는 데 쓰이지 않게 한다.
 */
public class AttachmentException extends RuntimeException {

	public AttachmentException(String message) {
		super(message);
	}

}
