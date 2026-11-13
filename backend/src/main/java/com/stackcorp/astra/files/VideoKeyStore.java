package com.stackcorp.astra.files;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.VideoKeys;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

@Service
class VideoKeyStore implements VideoKeys {

	private static final Pattern ASSET_KEY = Pattern.compile(ASSET_KEY_PATTERN);

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ObjectStore store;

	VideoKeyStore(ObjectStore store) {
		this.store = store;
	}

	@Override
	public byte[] ensure(String assetKey) {
		Optional<byte[]> existing = find(assetKey);
		if (existing.isPresent()) {
			return existing.get();
		}
		byte[] key = new byte[16];
		RANDOM.nextBytes(key);
		this.store.put(Bucket.CONTENT, objectKey(assetKey), key, "application/octet-stream");
		return key;
	}

	@Override
	public Optional<byte[]> find(String assetKey) {
		return this.store.get(Bucket.CONTENT, objectKey(assetKey)).filter((key) -> key.length == 16);
	}

	/** asset을 거둘 때 key를 지운다. 없으면 아무것도 하지 않는다. */
	void delete(String assetKey) {
		this.store.delete(Bucket.CONTENT, objectKey(assetKey));
	}

	private static String objectKey(String assetKey) {
		if (assetKey == null || !ASSET_KEY.matcher(assetKey).matches()) {
			throw new IllegalArgumentException("asset key 형식이 아닙니다.");
		}
		return "vod-keys/" + assetKey + ".key";
	}

}
