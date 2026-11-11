package com.stackcorp.astra.files.upload;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.storage.ObjectStreams;
import com.stackcorp.astra.files.upload.UploadRecords.Blob;
import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.files.upload.UploadRecords.Upload;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.files.scan.ScanMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 본문 스트림 업로드(ADR-0016). 큰 파일(강의 영상)을 디스크와 메모리에 쌓지 않고 content bucket으로 흘려 보낸다.
 * <p>
 * 순서: 요청 제한 → 용도와 과목 권한 → 멱등 key → 이름·확장자 → 선언한 크기(Content-Length)와 정책 상한. 여기까지는 본문을 읽지
 * 않는다. 그다음 앞부분 signature를 확인하고, 통과한 뒤에야 multipart upload를 시작한다. 읽은 크기가 선언과 다르거나 중간에 실패하면
 * upload를 취소해 part를 남기지 않는다. 기록은 multipart 업로드({@link UploadService})와 같은 행과 상태를 만든다.
 * <p>
 * 내용 hash는 다 받은 뒤에야 알 수 있어 원본은 blobs/stream/{임의 값}에 둔다. 같은 내용의 blob이 이미 있으면 그 blob을 함께 쓰고
 * 방금 올린 객체는 지운다. 영상은 악성코드 검사를 면제한다(ADR-0016 12번). 새 내용은 검사기가 있으면 unscanned로 저장하고, 이
 * 상태는 다른 용도에 clean으로 쓰이지 않는다(UploadRecords.referenceBlob).
 */
@Service
@EnableConfigurationProperties(StreamUploadProperties.class)
class StreamUploadService {

	static final String KEY_PREFIX = "blobs/stream/";

	private static final Logger log = LoggerFactory.getLogger(StreamUploadService.class);

	private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,19}");

	private final UploadRecords records;

	private final ObjectStreams streams;

	private final ObjectStore store;

	private final CourseMemberships memberships;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final UploadProperties properties;

	private final StreamUploadProperties streamProperties;

	private final ScanMode scan;

	StreamUploadService(UploadRecords records, ObjectStreams streams, ObjectStore store, CourseMemberships memberships,
			RateLimiter rateLimiter, TransactionTemplate transaction, UploadProperties properties,
			StreamUploadProperties streamProperties, ScanMode scan) {
		this.records = records;
		this.streams = streams;
		this.store = store;
		this.memberships = memberships;
		this.rateLimiter = rateLimiter;
		this.transaction = transaction;
		this.properties = properties;
		this.streamProperties = streamProperties;
		this.scan = scan;
	}

	/**
	 * @param contentLength 요청의 Content-Length header 값. 없으면 null
	 * @param body 본문. 권한과 크기를 확인한 뒤에만 연다
	 */
	Upload receive(long userId, long courseId, String purposeCode, String rawName, String clientKey,
			String contentLength, Body body) {
		this.rateLimiter.check("upload", userId, this.properties.rateLimit(), this.properties.rateWindow());
		UploadPurpose purpose = UploadPurpose.of(purposeCode)
			.filter(UploadPurpose::streamed)
			.orElseThrow(() -> ApiException.invalid("purpose 값이 올바르지 않습니다."));
		if (this.memberships.roleOf(userId, courseId).filter(purpose::allows).isEmpty()) {
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
		String name = FileNames.sanitize(rawName).orElseThrow(() -> rejected("invalid_name", "파일 이름이 올바르지 않습니다."));
		String extension = FileNames.extension(name)
			.filter(policy.extensions()::contains)
			.orElseThrow(() -> rejected("extension_not_allowed", "올릴 수 없는 파일 형식입니다."));
		if (contentLength == null || !DECIMAL.matcher(contentLength).matches()) {
			throw new ApiException(HttpStatus.LENGTH_REQUIRED, "length_required", "Content-Length가 필요합니다.");
		}
		long size = Long.parseLong(contentLength);
		if (size <= 0) {
			throw rejected("empty_file", "빈 파일은 올릴 수 없습니다.");
		}
		if (policy.maxFileBytes() != null && size > policy.maxFileBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_large", "파일이 너무 큽니다.");
		}

		// key는 요청마다 새 임의 값이다. 같은 내용의 blob을 GC가 지웠으면(ADR-0011) 그 key로 되살리므로 다시 판정할 일이 없다.
		String storageKey = KEY_PREFIX + UUID.randomUUID();
		Stored stored = store(body, extension, size, storageKey);
		try {
			Recorded recorded = this.transaction.execute((tx) -> {
				Blob blob = this.records
					.referenceBlob(stored.sha256(), size, stored.mime(), storageKey, this.scan.exemptBlobState(), true)
					.orElseThrow(() -> new IllegalStateException("새 key로도 blob을 참조하지 못했습니다."));
				if ("rejected".equals(blob.state())) {
					throw rejected("content_rejected", "올릴 수 없는 파일입니다.");
				}
				// 검사 면제(unscanned)이거나 검사를 통과한 내용이면 바로 쓸 수 있다. 다른 용도로 검사 중인 내용을 함께 쓰면 그 판정을 기다린다.
				boolean usable = "clean".equals(blob.state()) || ScanMode.UNSCANNED.equals(blob.state());
				String state = usable ? "clean" : "quarantined";
				long fileId = this.records.insertFile(blob.id(), name, extension, stored.mime(), size, userId, courseId,
						purpose.code(), usable ? "clean" : "pending");
				long uploadId = this.records.insertUpload(policy.id(), userId, courseId, fileId, clientKey, state,
						this.properties.ttl());
				return new Recorded(this.records.find(uploadId, userId).orElseThrow(), blob.storageKey());
			});
			// 같은 내용의 살아 있는 blob이 있으면 그 객체를 쓴다. 방금 올린 객체는 참조가 없다.
			if (!storageKey.equals(recorded.blobKey())) {
				discard(storageKey);
			}
			return recorded.upload();
		}
		catch (RuntimeException ex) {
			discard(storageKey);
			if (ex instanceof DuplicateKeyException && clientKey != null) {
				// 같은 멱등 key의 요청이 동시에 들어왔다. 먼저 끝난 쪽의 결과를 돌려준다.
				return sameTarget(this.records.findByKey(userId, clientKey).orElseThrow(() -> ex), courseId);
			}
			throw ex;
		}
	}

	private void discard(String storageKey) {
		try {
			this.store.delete(Bucket.CONTENT, storageKey);
		}
		catch (RuntimeException ex) {
			// 참조가 없는 객체다. 남아도 어느 경로로도 닿지 않는다.
			log.warn("failed to discard unused stream upload object", ex);
		}
	}

	/** 앞부분 signature를 확인한 뒤 본문 전체를 multipart upload로 저장한다. 실패하면 올린 part를 취소한다. */
	private Stored store(Body body, String extension, long size, String storageKey) {
		MessageDigest digest = sha256();
		try (InputStream in = body.open()) {
			byte[] head = in.readNBytes((int) Math.min(ContentInspector.HEAD_BYTES, size));
			if (head.length < Math.min(ContentInspector.HEAD_BYTES, size)) {
				throw incomplete();
			}
			String mime = ContentInspector.detect(extension, head)
				.orElseThrow(() -> rejected("content_mismatch", "파일 내용이 확장자와 맞지 않습니다."));
			ObjectStreams.Writer writer = this.streams.create(Bucket.CONTENT, storageKey, mime,
					(int) this.streamProperties.partSize().toBytes());
			try {
				digest.update(head);
				writer.write(head, 0, head.length);
				long total = head.length;
				byte[] buffer = new byte[64 * 1024];
				for (int read; (read = in.read(buffer)) != -1;) {
					total += read;
					if (total > size) {
						throw incomplete();
					}
					digest.update(buffer, 0, read);
					writer.write(buffer, 0, read);
				}
				if (total != size) {
					throw incomplete();
				}
				writer.complete();
				return new Stored(mime, HexFormat.of().formatHex(digest.digest()));
			}
			catch (IOException | RuntimeException ex) {
				writer.abort();
				throw ex;
			}
		}
		catch (IOException ex) {
			throw incomplete();
		}
	}

	private static Upload sameTarget(Upload upload, long courseId) {
		if (upload.courseId() != courseId) {
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key를 다른 요청에 썼습니다.");
		}
		return upload;
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static ApiException incomplete() {
		return new ApiException(HttpStatus.BAD_REQUEST, "upload_incomplete", "받은 크기가 Content-Length와 다릅니다.");
	}

	private static ApiException rejected(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
	}

	/** 요청 본문. 열기 전에는 읽지 않는다. */
	@FunctionalInterface
	interface Body {

		InputStream open() throws IOException;

	}

	private record Stored(String mime, String sha256) {
	}

	private record Recorded(Upload upload, String blobKey) {
	}

}
