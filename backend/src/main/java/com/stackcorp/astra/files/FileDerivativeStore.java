package com.stackcorp.astra.files;

import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.FileDerivatives;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

/**
 * 파생물은 content bucket의 derivatives/{fileId}/{name}에 둔다. 파일을 지우면 worker가 이 prefix를 함께 지운다(StorageGc).
 * 지운 파일에는 새로 쓰지 않는다. GC가 정리한 뒤 객체가 다시 생기지 않게 한다.
 */
@Service
class FileDerivativeStore implements FileDerivatives {

	private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*");

	private final ObjectStore store;

	private final FileRecords files;

	FileDerivativeStore(ObjectStore store, FileRecords files) {
		this.store = store;
		this.files = files;
	}

	@Override
	public void put(long fileId, String name, byte[] content, String contentType) {
		String key = key(fileId, name);
		if (this.files.find(fileId).isEmpty()) {
			throw new IllegalStateException("삭제했거나 없는 파일에는 파생물을 쓰지 않습니다: " + fileId);
		}
		this.store.put(Bucket.CONTENT, key, content, contentType);
	}

	@Override
	public Optional<byte[]> get(long fileId, String name) {
		return this.store.get(Bucket.CONTENT, key(fileId, name));
	}

	static String prefix(long fileId) {
		return "derivatives/" + fileId + "/";
	}

	private static String key(long fileId, String name) {
		if (name == null || name.contains("..") || !NAME.matcher(name).matches()) {
			throw new IllegalArgumentException("파생물 이름이 올바르지 않습니다: " + name);
		}
		return prefix(fileId) + name;
	}

}
