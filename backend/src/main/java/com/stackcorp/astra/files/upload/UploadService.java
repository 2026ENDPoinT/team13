package com.stackcorp.astra.files.upload;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.files.scan.ScanMode;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.upload.UploadRecords.Blob;
import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.files.upload.UploadRecords.Upload;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 수신(SC-08). 순서: 요청 제한 → 용도와 과목 권한 → 이름·확장자·크기 → 내용 형식 → 저장 → 기록.
 * 원본은 content bucket의 blobs/{sha256 앞 2자}/{sha256}에 둔다. 같은 내용은 객체 하나를 함께 쓴다. GC가 지운 내용을 다시 받으면
 * 뒤에 임의 값을 붙인 새 key에 저장하고 처음부터 다시 검사한다(ADR-0011).
 * 검사기가 없으면 형식 검사를 통과한 파일은 바로 clean이 된다. 검사기가 있으면 새 내용은 검사 worker가 판정할 때까지 quarantined다(ADR-0018).
 */
@Service
@EnableConfigurationProperties(UploadProperties.class)
class UploadService {

	private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Logger log = LoggerFactory.getLogger(UploadService.class);

	private final UploadRecords records;

	private final ObjectStore store;

	private final CourseMemberships memberships;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final UploadProperties properties;

	private final ScanMode scan;

	UploadService(UploadRecords records, ObjectStore store, CourseMemberships memberships, RateLimiter rateLimiter,
			TransactionTemplate transaction, UploadProperties properties, ScanMode scan) {
		this.records = records;
		this.store = store;
		this.memberships = memberships;
		this.rateLimiter = rateLimiter;
		this.transaction = transaction;
		this.properties = properties;
		this.scan = scan;
	}

	Upload receive(long userId, long courseId, String purposeCode, MultipartFile file, String clientKey) {
		// 권한이 없는 시도와 거부한 업로드도 센다. 본문은 이미 받은 뒤라 횟수로 막는다.
		this.rateLimiter.check("upload", userId, this.properties.rateLimit(), this.properties.rateWindow());
		UploadPurpose purpose = UploadPurpose.of(purposeCode)
			.orElseThrow(() -> ApiException.invalid("purpose 값이 올바르지 않습니다."));
		if (purpose.streamed()) {
			throw ApiException.invalid("이 용도의 파일은 POST /api/uploads/stream으로 올립니다.");
		}
		if (this.memberships.roleOf(userId, courseId).filter((r) -> r == purpose.requiredRole()).isEmpty()) {
			throw ApiException.notFound();
		}
		if (clientKey != null && !CLIENT_KEY.matcher(clientKey).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		if (clientKey != null) {
			Optional<Upload> replay = this.records.findByKey(userId, clientKey);
			if (replay.isPresent()) {
				return sameTarget(replay.get(), courseId);
			}
		}
		Policy policy = this.records.policy(purpose.code())
			.orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + purpose.code()));
		String name = FileNames.sanitize(file.getOriginalFilename())
			.orElseThrow(() -> rejected("invalid_name", "파일 이름이 올바르지 않습니다."));
		String extension = FileNames.extension(name)
			.filter(policy.extensions()::contains)
			.orElseThrow(() -> rejected("extension_not_allowed", "올릴 수 없는 파일 형식입니다."));
		long size = file.getSize();
		if (size <= 0) {
			throw rejected("empty_file", "빈 파일은 올릴 수 없습니다.");
		}
		if (policy.maxFileBytes() != null && size > policy.maxFileBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_large", "파일이 너무 큽니다.");
		}

		Inspected inspected = inspect(file, size);
		String mime = ContentInspector.detect(extension, inspected.head())
			.orElseThrow(() -> rejected("content_mismatch", "파일 내용이 확장자와 맞지 않습니다."));
		String sha = inspected.sha256();
		Optional<Blob> existing = this.records.blob(sha);
		// GC가 그 사이 blob을 지웠으면 한 번 더 판정한다. 두 번째에는 새 key에 저장해 되살리므로 다시 엇갈리지 않는다.
		for (int attempt = 1;; attempt++) {
			if (existing.filter((b) -> "rejected".equals(b.state())).isPresent()) {
				throw rejected("content_rejected", "올릴 수 없는 파일입니다.");
			}
			String storageKey = storageKey(sha, existing);
			if (storageKey != null) {
				try (InputStream in = file.getInputStream()) {
					this.store.put(Bucket.CONTENT, storageKey, in, size, mime);
				}
				catch (IOException ex) {
					throw new IllegalStateException("업로드를 저장하지 못했습니다.", ex);
				}
			}
			Recorded recorded;
			try {
				recorded = this.transaction.execute((tx) -> {
					Optional<Blob> referenced = this.records.referenceBlob(sha, size, mime, storageKey,
							this.scan.initialBlobState());
					if (referenced.isEmpty()) {
						return null;
					}
					Blob blob = referenced.get();
					if ("rejected".equals(blob.state())) {
						throw rejected("content_rejected", "올릴 수 없는 파일입니다.");
					}
					// 검사를 마치지 않은 blob을 함께 쓰면 이 업로드도 검사를 기다린다. 판정은 blob에 내리고 같은 내용의 파일 모두에 옮긴다.
					String state = "clean".equals(blob.state()) ? "clean" : "quarantined";
					long fileId = this.records.insertFile(blob.id(), name, extension, mime, size, userId, courseId,
							purpose.code(), "clean".equals(state) ? "clean" : "pending");
					long uploadId = this.records.insertUpload(policy.id(), userId, courseId, fileId, clientKey, state,
							this.properties.ttl());
					return new Recorded(this.records.find(uploadId, userId).orElseThrow(), blob.storageKey());
				});
			}
			catch (DuplicateKeyException ex) {
				discardUnused(sha, storageKey, null);
				// 같은 멱등 key의 요청이 동시에 들어왔다. 먼저 끝난 쪽의 결과를 돌려준다.
				if (clientKey == null) {
					throw ex;
				}
				return sameTarget(this.records.findByKey(userId, clientKey).orElseThrow(() -> ex), courseId);
			}
			catch (ApiException ex) {
				if (storageKey != null && "content_rejected".equals(ex.code())) {
					// 저장하는 사이 같은 내용이 거부됐다. 거부한 내용은 되살리지 않으므로 방금 쓴 객체는 기본 key여도 남기지 않는다.
					this.store.delete(Bucket.CONTENT, storageKey);
				}
				else {
					discardUnused(sha, storageKey, null);
				}
				throw ex;
			}
			catch (RuntimeException ex) {
				discardUnused(sha, storageKey, null);
				throw ex;
			}
			if (recorded != null) {
				discardUnused(sha, storageKey, recorded.blobKey());
				return recorded.upload();
			}
			if (attempt >= 2) {
				throw new IllegalStateException("지운 blob을 다시 참조하지 못했습니다.");
			}
			existing = this.records.blob(sha);
		}
	}

	/**
	 * 내용을 저장할 key. 살아 있는 blob이 있으면 그 객체를 함께 쓰므로 저장하지 않는다(null). 처음 보는 내용은
	 * blobs/{sha256 앞 2자}/{sha256}에, GC가 지운 blob은 GC가 지우고 있을 수 있는 옛 key와 겹치지 않는 새 key에 저장한다.
	 */
	private static String storageKey(String sha, Optional<Blob> existing) {
		if (existing.isEmpty()) {
			return baseKey(sha);
		}
		if (!existing.get().purged()) {
			return null;
		}
		byte[] suffix = new byte[8];
		RANDOM.nextBytes(suffix);
		return baseKey(sha) + "-" + HexFormat.of().formatHex(suffix);
	}

	private static String baseKey(String sha) {
		return "blobs/" + sha.substring(0, 2) + "/" + sha;
	}

	// 새 key에 저장했지만 blob이 그 key를 쓰지 않게 됐으면 지운다. 기본 key는 같은 내용의 다른 요청과 함께 쓰므로 남긴다.
	private void discardUnused(String sha, String storageKey, String blobKey) {
		if (storageKey == null || storageKey.equals(baseKey(sha)) || storageKey.equals(blobKey)) {
			return;
		}
		try {
			this.store.delete(Bucket.CONTENT, storageKey);
		}
		catch (RuntimeException ex) {
			log.warn("failed to discard unused upload object", ex);
		}
	}

	Upload get(long userId, long uploadId) {
		return this.records.find(uploadId, userId).orElseThrow(ApiException::notFound);
	}

	void delete(long userId, long uploadId) {
		Upload upload = get(userId, uploadId);
		if ("attached".equals(upload.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "attached", "이미 첨부한 파일은 지울 수 없습니다.");
		}
		this.transaction.executeWithoutResult((tx) -> this.records.delete(uploadId, userId));
	}

	private static Upload sameTarget(Upload upload, long courseId) {
		if (upload.courseId() != courseId) {
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key를 다른 요청에 썼습니다.");
		}
		return upload;
	}

	private static Inspected inspect(MultipartFile file, long declaredSize) {
		try (InputStream in = file.getInputStream()) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] head = in.readNBytes(ContentInspector.HEAD_BYTES);
			digest.update(head);
			byte[] buffer = new byte[64 * 1024];
			long total = head.length;
			for (int read; (read = in.read(buffer)) != -1;) {
				digest.update(buffer, 0, read);
				total += read;
			}
			if (total != declaredSize) {
				throw new IllegalStateException("업로드 크기가 선언한 값과 다릅니다.");
			}
			return new Inspected(head, HexFormat.of().formatHex(digest.digest()));
		}
		catch (IOException ex) {
			throw new IllegalStateException("업로드를 읽지 못했습니다.", ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ApiException rejected(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
	}

	private record Inspected(byte[] head, String sha256) {
	}

	private record Recorded(Upload upload, String blobKey) {
	}

}
