package com.stackcorp.astra.files.api;

/**
 * 영상 asset을 쓰는 부모 모듈이 구현하는 port(ADR-0001 4번). files는 부모 테이블을 읽지 않는다.
 * 모든 호출은 files가 asset 상태를 바꾸는 transaction 안에서 일어난다. 자기가 모르는 asset이면 아무것도 하지 않는다.
 */
public interface VideoAssetListener {

	/**
	 * 변환을 마치고 HLS를 게시했다.
	 *
	 * @param contentPath delivery bucket의 재생 목록 경로 vod/{assetKey}/index.m3u8
	 */
	void ready(long assetId, String contentPath, int durationSec);

	/** 변환이 끝내 실패했다. 실패 사유는 files가 기록하고 부모에게는 알리지 않는다. */
	void failed(long assetId);

	/** 원본 파일이 삭제되어 asset을 거두었다. 재생 경로를 즉시 닫아야 한다. */
	void withdrawn(long assetId);

}
