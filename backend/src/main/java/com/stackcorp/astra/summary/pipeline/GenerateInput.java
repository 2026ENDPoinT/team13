package com.stackcorp.astra.summary.pipeline;

import java.util.function.Consumer;

/**
 * @param documentId 결과의 lecture 값이 되는 문서 식별자
 * @param title 문서 표시 제목. 없으면 rendition의 제목을 쓴다
 * @param onProgress 진행 알림. null이면 알리지 않는다
 */
public record GenerateInput(String documentId, String title, SourceDocument source, CheckpointStore checkpoints,
		Cancellation cancellation, Consumer<GenerationProgress> onProgress, Limits limits) {

	/**
	 * 값이 null이면 기본값을 쓴다.
	 *
	 * @param pagesPerChunk 한 번에 보내는 쪽 수(1~10, 기본 6)
	 * @param maxCalls 작업의 모델 호출 수 상한
	 * @param maxImageBytesPerCall 한 번에 보내는 이미지 크기 합의 상한(기본 4 MiB)
	 */
	public record Limits(Integer pagesPerChunk, Integer maxCalls, Long maxImageBytesPerCall) {

		public static final Limits DEFAULT = new Limits(null, null, null);

	}

}
