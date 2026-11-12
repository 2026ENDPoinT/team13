package com.stackcorp.astra.files.upload;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.files.api.UploadPermission;
import com.stackcorp.astra.files.scan.ScanMode;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.upload.UploadRecords.Blob;
import com.stackcorp.astra.files.upload.UploadRecords.Keyed;
import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.files.upload.UploadRecords.Upload;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.idempotency.Idempotency;

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

	static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Logger log = LoggerFactory.getLogger(UploadService.class);

	private final UploadRecords records;

	private final ObjectStore store;

	private final CourseMemberships memberships;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final UploadProperties properties;

	private final ScanMode scan;

	private final List<UploadPermission> permissions;

	UploadService(UploadRecords records, ObjectStore store, CourseMemberships memberships, RateLimiter rateLimiter,
			TransactionTemplate transaction, UploadProperties properties, ScanMode scan,
			List<UploadPermission> permissions) {
		this.records = records;
		this.store = store;
		this.memberships = memberships;
		this.rateLimiter = rateLimiter;
		this.transaction = transaction;
		this.properties = properties;
		this.scan = scan;
		this.permissions = permissions;
	}

	/**
	 * @param courseId 과목 용도면 그 과목. 과목 밖 용도(ADR-0035)면 null. 용도와 맞지 않으면 400이다
	 */
	Upload receive(Actor actor, Long courseId, String purposeCode, MultipartFile file, String clientKey) {
		long userId = actor.userId();
		// 권한이 없는 시도와 거부한 업로드도 센다. 본문은 이미 받은 뒤라 횟수로 막는다.
		this.rateLimiter.check("upload", userId, this.properties.rateLimit(), this.properties.rateWindow());
		UploadPurpose purpose = UploadPurpose.of(purposeCode)
			.orElseThrow(() -> ApiException.invalid("purpose 값이 올바르지 않습니다."));
		if (purpose.streamed()) {
			throw ApiException.invalid("이 용도의 파일은 POST /api/uploads/stream으로 올립니다.");
		}
		if (purpose.courseScoped() != (courseId != null)) {
			throw ApiException.invalid(
					purpose.courseScoped() ? "이 용도에는 courseId가 필요합니다." : "이 용도에는 courseId를 줄 수 없습니다.");
		}
		boolean allowed = courseId != null ? mayUpload(userId, courseId, purpose) : mayUploadOutsideCourse(actor, purpose);
		if (!allowed) {
			throw ApiException.notFound();
		}
		if (clientKey != null && !CLIENT_KEY.matcher(clientKey).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		Policy policy = this.records.policy(purpose.code())
			.orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + purpose.code()));
		return store(userId, courseId, purpose.code(), policy, file, clientKey, null, Placement.NONE);
	}

	/** 그 과목에서 이 용도로 올릴 수 있는 역할인지. 과목 밖 용도는 언제나 false다. */
	boolean mayUpload(long userId, long courseId, UploadPurpose purpose) {
		return purpose.courseScoped() && this.memberships.roleOf(userId, courseId).filter(purpose::allows).isPresent();
	}

	/** 과목 밖 용도를 이 사람이 올릴 수 있는지. 부모를 가진 모듈의 {@link UploadPermission}이 판정한다. 과목 용도는 언제나 false다. */
	boolean mayUploadOutsideCourse(Actor actor, UploadPurpose purpose) {
		return !purpose.courseScoped() && this.permissions.stream().anyMatch((p) -> p.allows(actor, purpose.code()));
	}

	/** 그 과목의 구성원인지. 역할은 묻지 않는다. */
	boolean member(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).isPresent();
	}

	/**
	 * 이름·확장자·크기·내용 형식을 검사한 뒤 저장하고 기록한다. 권한과 요청 제한은 부르는 쪽이 먼저 판정한다.
	 * placement는 업로드를 기록하는 transaction 안에서 기록 앞뒤로 부른다. 예외를 던지면 업로드 기록 전체가 되돌아간다.
	 * <p>
	 * 멱등 key가 있으면 내용 hash를 계산한 뒤, 저장하기 전에 같은 key의 업로드를 찾는다. 요청 지문(용도, 과목, 이름, 크기, 내용
	 * hash, 폴더)이 같으면 그 업로드를 돌려주고, 다르면 409 {@code idempotency_conflict}다. 어느 쪽도 객체를 저장하지 않는다.
	 *
	 * @param courseId 과목 밖 업로드(파일함)면 null
	 * @param folderId 파일함 업로드의 대상 폴더(루트면 null). 과목 업로드면 null
	 * @return 기록한 업로드. 같은 멱등 key의 같은 요청이 먼저 끝났으면 그 업로드
	 */
	Upload store(long userId, Long courseId, String purposeCode, Policy policy, MultipartFile file, String clientKey,
			Long folderId, Placement placement) {
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
		String fingerprint = clientKey == null ? null
				: fingerprint(purposeCode, courseId, name, size, sha, folderId);
		if (clientKey != null) {
			Optional<Upload> replay = this.records.findByKey(userId, clientKey).map((k) -> sameRequest(k, fingerprint));
			if (replay.isPresent()) {
				return replay.get();
			}
		}
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
					placement.before();
					Optional<Blob> referenced = this.records.referenceBlob(sha, size, mime, storageKey,
							this.scan.initialBlobState());
					if (referenced.isEmpty()) {
						// placement가 이 transaction에서 바꾼 것도 함께 되돌린다.
						tx.setRollbackOnly();
						return null;
					}
					Blob blob = referenced.get();
					if ("rejected".equals(blob.state())) {
						throw rejected("content_rejected", "올릴 수 없는 파일입니다.");
					}
					// 검사를 마치지 않은 blob을 함께 쓰면 이 업로드도 검사를 기다린다. 판정은 blob에 내리고 같은 내용의 파일 모두에 옮긴다.
					String state = "clean".equals(blob.state()) ? "clean" : "quarantined";
					long fileId = this.records.insertFile(blob.id(), name, extension, mime, size, userId, courseId,
							purposeCode, "clean".equals(state) ? "clean" : "pending");
					long uploadId = this.records.insertUpload(policy.id(), userId, courseId, fileId, clientKey,
							fingerprint, state, this.properties.ttl());
					placement.after(fileId, uploadId, name);
					return new Recorded(this.records.find(uploadId, userId).orElseThrow(), blob.storageKey());
				});
			}
			catch (DuplicateKeyException ex) {
				discardUnused(sha, storageKey, null);
				// 같은 멱등 key의 요청이 동시에 들어왔다. 먼저 끝난 쪽이 같은 요청이면 그 결과를 돌려주고, 다르면 409다.
				if (clientKey == null) {
					throw ex;
				}
				return sameRequest(this.records.findByKey(userId, clientKey).orElseThrow(() -> ex), fingerprint);
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

	/**
	 * 멱등 업로드의 요청 지문. 검사한 값(용도, 과목, 정리한 이름, 크기, 내용 SHA-256, 파일함 폴더)을 공용 멱등 원장과 같은 규칙(정렬한
	 * JSON의 SHA-256, ADR-0012)으로 묶는다. 과목이 다르거나 파일함과 과목 업로드처럼 대상이 다르면 지문도 다르다.
	 */
	static String fingerprint(String purpose, Long courseId, String name, long sizeBytes, String sha256,
			Long folderId) {
		return Idempotency.fingerprint(new UploadRequest(purpose, courseId, name, sizeBytes, sha256, folderId));
	}

	/**
	 * 같은 key로 먼저 기록한 업로드가 같은 요청이면 그 업로드다. 지문이 다르거나, 지문을 저장하기 전에 만든 업로드라 같은 요청인지 알 수
	 * 없으면 409다. 다른 요청의 업로드를 돌려주지 않는다.
	 */
	static Upload sameRequest(Keyed keyed, String fingerprint) {
		if (keyed.fingerprint() == null || !keyed.fingerprint().equals(fingerprint)) {
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key를 다른 요청에 썼습니다.");
		}
		return keyed.upload();
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

	/** 업로드 요청 지문의 정규형. 속성 이름 순서로 정렬해 SHA-256을 낸다. */
	record UploadRequest(String purpose, Long courseId, String name, long sizeBytes, String sha256,
			Long folderId) {
	}

	/**
	 * 업로드를 어디에 둘지 정하는 쪽이 업로드 기록과 같은 transaction에서 하는 일. 파일함은 앞에서 용량을 잡고 뒤에서 항목을 만든다.
	 * 잠금 순서를 지키도록 before는 blob을 잠그기 전에 부른다.
	 */
	interface Placement {

		Placement NONE = new Placement() {
		};

		default void before() {
		}

		default void after(long fileId, long uploadId, String name) {
		}

	}

}
