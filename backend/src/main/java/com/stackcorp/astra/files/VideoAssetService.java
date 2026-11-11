package com.stackcorp.astra.files;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.VideoAssets;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영상 asset 등록(ADR-0016). asset key는 추측할 수 없는 임의 값이고 delivery 경로 vod/{assetKey}/와 key 객체의 이름이 된다.
 */
@Service
@EnableConfigurationProperties(VideoWorkerProperties.class)
class VideoAssetService implements VideoAssets {

	// 재생 목록의 따옴표 안에 그대로 들어가므로 따옴표, 공백, 줄바꿈이 없는 같은 origin의 API 경로만 받는다.
	private static final Pattern KEY_URI = Pattern.compile("/api/[A-Za-z0-9/_-]{1,190}");

	private static final Map<String, String> CONTAINERS = Map.of("video/mp4", "mp4", "video/quicktime", "mov",
			"video/webm", "webm", "video/x-matroska", "mkv");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final FileRecords files;

	private final VideoAssetRecords records;

	VideoAssetService(FileRecords files, VideoAssetRecords records) {
		this.files = files;
		this.records = records;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public long register(long fileId, String keyUri) {
		if (keyUri == null || !KEY_URI.matcher(keyUri).matches()) {
			throw new IllegalArgumentException("key URI 형식이 아닙니다.");
		}
		this.files.find(fileId)
			.filter(FileRow::transcodable)
			.filter((f) -> container(f.detectedMimeType()).isPresent())
			.orElseThrow(() -> new AttachmentException("변환할 수 있는 영상 파일이 아닙니다."));
		byte[] random = new byte[16];
		RANDOM.nextBytes(random);
		long assetId;
		try {
			assetId = this.records.insertAsset(fileId, HexFormat.of().formatHex(random), keyUri);
		}
		catch (DuplicateKeyException ex) {
			throw new AttachmentException("이미 등록한 영상 파일입니다.");
		}
		this.records.insertJob(assetId);
		return assetId;
	}

	/** 서버가 판정한 MIME으로 transcoder에 알릴 컨테이너를 정한다. */
	static Optional<String> container(String detectedMimeType) {
		return Optional.ofNullable(detectedMimeType).map(CONTAINERS::get);
	}

}
