package com.stackcorp.astra.learning;

import com.stackcorp.astra.files.api.VideoAssetListener;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 영상 asset의 변환 결과를 온라인강의 콘텐츠에 반영한다(ADR-0016). files가 asset 상태를 바꾸는 transaction 안에서 부른다.
 * content_path가 채워진 콘텐츠만 재생 허가와 key API가 응답한다(LectureVideoController). 원본이 삭제되면 content_path를 비워 즉시 닫는다.
 */
@Component
class LectureVideoAssets implements VideoAssetListener {

	private final JdbcClient jdbc;

	LectureVideoAssets(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void ready(long assetId, String contentPath, int durationSec) {
		this.jdbc.sql("""
				UPDATE online_lecture_items SET content_path = :path, duration_sec = :duration, video_state = 'ready'
				 WHERE video_asset_id = :asset
				""").param("path", contentPath).param("duration", durationSec).param("asset", assetId).update();
	}

	@Override
	public void failed(long assetId) {
		this.jdbc.sql("UPDATE online_lecture_items SET video_state = 'failed' WHERE video_asset_id = :asset")
			.param("asset", assetId)
			.update();
	}

	@Override
	public void withdrawn(long assetId) {
		this.jdbc.sql("""
				UPDATE online_lecture_items SET content_path = NULL, video_state = 'removed' WHERE video_asset_id = :asset
				""").param("asset", assetId).update();
	}

}
