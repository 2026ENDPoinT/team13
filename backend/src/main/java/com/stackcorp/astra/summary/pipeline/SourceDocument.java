package com.stackcorp.astra.summary.pipeline;

import java.util.List;

/**
 * 요약할 문서의 rendition. 쪽은 1부터 빠짐없이 이어진다.
 */
public interface SourceDocument {

	/** 원본 파일의 SHA-256. checkpoint key에 들어간다. */
	String sha256();

	/** 문서에서 읽은 제목. 없으면 null. */
	String title();

	List<Page> pages();

	/** 모델 입력용 쪽 이미지(JPEG). */
	byte[] modelImage(int page);

	/**
	 * @param textTruncated 추출한 텍스트가 길어 잘렸는지
	 * @param modelImageBytes 모델 입력용 이미지의 크기. 한 번에 보낼 묶음을 정할 때 쓴다
	 * @param imageUnavailable 쪽 이미지가 대체 그림인지. 그런 쪽은 모델에 이미지를 보내지 않는다
	 */
	record Page(int page, String text, boolean textTruncated, long modelImageBytes, boolean imageUnavailable) {
	}

}
