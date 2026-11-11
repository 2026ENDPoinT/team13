package com.stackcorp.astra.files.upload;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 수신(SC-08). 순서: 요청 제한 → 용도와 과목 권한 → 이름·확장자·크기 → 내용 형식 → 저장 → 기록.
 * 원본은 content bucket의 blobs/{sha256 앞 2자}/{sha256}에 둔다. 같은 내용은 객체 하나를 함께 쓴다.
 * 검사기가 없으면 형식 검사를 통과한 파일은 바로 clean이 된다. 검사기가 있으면 새 내용은 검사 worker가 판정할 때까지 quarantined다(ADR-0018).
 */
@Service
@EnableConfigurationProperties(UploadProperties.class)
class UploadService {

	private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

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
		if (existing.filter((b) -> "rejected".equals(b.state())).isPresent()) {
			throw rejected("content_rejected", "올릴 수 없는 파일입니다.");
		}
		String storageKey = "blobs/" + sha.substring(0, 2) + "/" + sha;
		boolean stored = existing.filter((b) -> !b.purged()).isEmpty();
		if (stored) {
			try (InputStream in = file.getInputStream()) {
				this.store.put(Bucket.CONTENT, storageKey, in, size, mime);
			}
			catch (IOException ex) {
				throw new IllegalStateException("업로드를 저장하지 못했습니다.", ex);
			}
		}

		try {
			return this.transaction.execute((tx) -> {
				Blob blob = this.records.referenceBlob(sha, size, mime, storageKey, this.scan.initialBlobState());
				if ("rejected".equals(blob.state())) {
					throw rejected("content_rejected", "올릴 수 없는 파일입니다.");
				}
				// 검사를 마치지 않은 blob을 함께 쓰면 이 업로드도 검사를 기다린다. 판정은 blob에 내리고 같은 내용의 파일 모두에 옮긴다.
				String state = "clean".equals(blob.state()) ? "clean" : "quarantined";
				long fileId = this.records.insertFile(blob.id(), name, extension, mime, size, userId, courseId,
						purpose.code(), "clean".equals(state) ? "clean" : "pending");
				long uploadId = this.records.insertUpload(policy.id(), userId, courseId, fileId, clientKey, state,
						this.properties.ttl());
				return this.records.find(uploadId, userId).orElseThrow();
			});
		}
		catch (ApiException ex) {
			// 저장하는 사이 같은 내용이 거부됐다. 방금 쓴 객체를 남기지 않는다.
			if (stored && "content_rejected".equals(ex.code())) {
				this.store.delete(Bucket.CONTENT, storageKey);
			}
			throw ex;
		}
		catch (DuplicateKeyException ex) {
			// 같은 멱등 key의 요청이 동시에 들어왔다. 먼저 끝난 쪽의 결과를 돌려준다.
			if (clientKey == null) {
				throw ex;
			}
			return sameTarget(this.records.findByKey(userId, clientKey).orElseThrow(() -> ex), courseId);
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

}
