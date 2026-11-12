package com.stackcorp.astra.files.api;

import java.util.List;
import java.util.Optional;

/**
 * 문서의 쪽 이미지와 쪽 텍스트(rendition). 화면용 쪽 이미지는 delivery bucket의 doc/{renditionKey}/에, 모델 입력용 이미지와
 * 텍스트는 content bucket에 둔다(ADR-0007).
 */
public interface DocumentRenditions {

	/**
	 * 파일의 현재 버전에 대한 rendition을 돌려준다. 없으면 converter로 만든다. 만드는 동안 blocking된다.
	 *
	 * @throws RenditionException
	 */
	Rendition prepare(long fileId);

	/**
	 * @throws RenditionException 이미지가 없을 때
	 */
	byte[] modelImage(String renditionKey, int page);

	/** 이미 만든 rendition의 key. 없으면 만들지 않고 비어 있는 값을 돌려준다. */
	Optional<String> readyKey(long fileId, int fileVersionNo);

	/**
	 * @param key 추측할 수 없는 64자 hex. 전달 경로 doc/{key}/에 쓴다
	 * @param sourceSha256 원본 바이트의 SHA-256
	 * @param title PDF 문서 정보의 제목. 없으면 null
	 */
	record Rendition(String key, long fileId, int fileVersionNo, String sourceSha256, String title,
			List<RenditionPage> pages) {

		public Rendition {
			pages = List.copyOf(pages);
		}

	}

	record RenditionPage(int page, String text, boolean textTruncated, long modelImageBytes) {
	}

}
