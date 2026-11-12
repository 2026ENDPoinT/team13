package com.stackcorp.astra.files;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.api.DeliveryObjects;
import com.stackcorp.astra.files.api.VideoKeys;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

import org.springframework.stereotype.Service;

/**
 * 전달 경로를 delivery bucket의 객체로 바꾼다. 경로는 정해진 형식만 받고, 응답 형식은 확장자 목록에서만 정한다.
 * 요청 경로를 저장 key에 그대로 이어 붙이지 않는다.
 */
@Service
class DeliveryObjectReader implements DeliveryObjects {

	private static final Pattern DOCUMENT = Pattern.compile("doc/[0-9a-f]{64}/[0-9]{3}\\.(jpg)");

	private static final Pattern VIDEO = Pattern
		.compile("vod/" + VideoKeys.ASSET_KEY_PATTERN + "/[A-Za-z0-9][A-Za-z0-9_-]{0,80}\\.(m3u8|ts|m4s|mp4|aac|vtt)");

	private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "m3u8", "application/vnd.apple.mpegurl",
			"ts", "video/mp2t", "m4s", "video/iso.segment", "mp4", "video/mp4", "aac", "audio/aac", "vtt", "text/vtt");

	private final ObjectStore store;

	DeliveryObjectReader(ObjectStore store) {
		this.store = store;
	}

	@Override
	public Optional<DeliveryObject> find(String path) {
		if (path == null) {
			return Optional.empty();
		}
		for (Pattern pattern : new Pattern[] { DOCUMENT, VIDEO }) {
			Matcher m = pattern.matcher(path);
			if (m.matches()) {
				String type = TYPES.get(m.group(1));
				return this.store.get(Bucket.DELIVERY, path).map((bytes) -> new DeliveryObject(bytes, type));
			}
		}
		return Optional.empty();
	}

}
