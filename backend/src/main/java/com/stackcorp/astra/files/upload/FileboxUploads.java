package com.stackcorp.astra.files.upload;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.files.upload.UploadRecords.Upload;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 개인 파일함(files.filebox)이 쓰는 업로드 기능(ADR-0028). 파일함에 올리는 파일과 파일함 파일을 과목 업로드로 다시 쓰는 일은 일반
 * 업로드와 같은 정책, 이름 규칙, 내용 검사, 검사 상태, 요청 제한을 따른다(FILES-11).
 * <ul>
 * <li>파일함에 올리기: 용도 {@value #PURPOSE}의 정책으로 검사해 저장한다. 업로드 기록과 같은 transaction에서 파일함이 용량을 잡고
 * 항목을 만들며, 업로드는 바로 attached가 된다. 첨부 확정(Attachments)으로는 쓸 수 없다.</li>
 * <li>다시 쓰기(EP-0126): 같은 내용 객체를 참조하는 새 파일과 clean 업로드를 만든다. 대상 용도의 역할, 확장자, 크기 정책을 다시
 * 검사하고, 검사를 마치지 않았거나 거부한 내용은 쓰지 않는다. 만든 업로드는 일반 업로드처럼 부모 저장에서 첨부한다.</li>
 * </ul>
 */
@Service
public class FileboxUploads {

	/** 파일함 업로드의 용도 코드(upload_policies.policy_code, files.source_menu_code). */
	public static final String PURPOSE = "filebox";

	private final UploadService uploads;

	private final UploadRecords records;

	private final RateLimiter rateLimiter;

	private final UploadProperties properties;

	private final JdbcClient jdbc;

	FileboxUploads(UploadService uploads, UploadRecords records, RateLimiter rateLimiter, UploadProperties properties,
			JdbcClient jdbc) {
		this.uploads = uploads;
		this.records = records;
		this.rateLimiter = rateLimiter;
		this.properties = properties;
		this.jdbc = jdbc;
	}

	/**
	 * 파일함에 올릴 파일을 검사해 저장하고 기록한다. 같은 사용자의 같은 멱등 key로 같은 요청(같은 파일, 이름, 폴더)의 업로드가 이미
	 * 있으면 아무것도 하지 않고 그 업로드 ID를 돌려주고, 다른 요청이면 409 {@code idempotency_conflict}다. 그 업로드가 파일함
	 * 업로드인지는 부르는 쪽이 확인한다.
	 *
	 * @param folderId 올릴 폴더. 루트면 null. 요청 지문에 들어간다
	 * @return 업로드 ID
	 */
	public long receive(long userId, MultipartFile file, String clientKey, Long folderId, Target target) {
		// 요청 제한(upload)은 multipart 본문을 나누기 전에 MultipartRateLimitFilter가 센다.
		if (clientKey != null && !UploadService.CLIENT_KEY.matcher(clientKey).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		Policy policy = this.records.policy(PURPOSE)
			.orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + PURPOSE));
		long size = file.getSize();
		return this.uploads.store(userId, null, PURPOSE, policy, file, clientKey, folderId, new UploadService.Placement() {

			@Override
			public void before() {
				target.reserve(size);
			}

			@Override
			public void after(long fileId, long uploadId, String name) {
				target.place(fileId, name);
				FileboxUploads.this.jdbc.sql("""
						UPDATE upload_sessions SET upload_state = 'attached', attached_at = CURRENT_TIMESTAMP(3)
						 WHERE upload_id = :id AND upload_state IN ('quarantined', 'clean')
						""").param("id", uploadId).update();
			}

		}).uploadId();
	}

	/**
	 * 다시 쓸 대상 용도와 과목에 올릴 수 있는지 본다. 멱등 원장을 거치기 전에 권한을 판정할 때 쓴다. 과목 밖 용도(공지·자료실·문의·쪽지
	 * 첨부, ADR-0035)는 과목 없이 부르고, 일반 업로드와 같이 부모를 가진 모듈의 권한으로 판정한다.
	 *
	 * @param courseId 과목 용도면 그 과목. 과목 밖 용도면 null
	 * @throws ApiException 용도가 틀리거나 과목 용도에 과목이 없으면 400, 구성원인 과목이지만 역할이 그 용도에 맞지 않으면 400
	 * {@code purpose_not_allowed}, 구성원이 아닌 과목이거나 과목 밖 용도를 올릴 수 없는 사람이면 404
	 */
	public void checkTarget(Actor actor, Long courseId, String purposeCode) {
		target(actor, courseId, purposeCode);
	}

	private UploadPurpose target(Actor actor, Long courseId, String purposeCode) {
		UploadPurpose purpose = UploadPurpose.of(purposeCode)
			.orElseThrow(() -> ApiException.invalid("purpose 값이 올바르지 않습니다."));
		if (purpose.streamed()) {
			// 강의 영상은 변환 작업을 만드는 스트림 업로드로만 받는다(ADR-0016).
			throw ApiException.invalid("이 용도에는 파일함 파일을 쓸 수 없습니다.");
		}
		if (courseId == null) {
			if (purpose.courseScoped()) {
				throw ApiException.invalid("이 용도에는 courseId가 필요합니다.");
			}
			if (!this.uploads.mayUploadOutsideCourse(actor, purpose)) {
				// 일반 업로드와 같다. 올릴 수 없는 과목 밖 용도는 404다.
				throw ApiException.notFound();
			}
			return purpose;
		}
		long userId = actor.userId();
		if (!this.uploads.mayUpload(userId, courseId, purpose)) {
			// 구성원이 아닌 과목은 있는지도 드러내지 않는다. 구성원에게 역할이 맞지 않는다고 알리는 것은 본인의 역할만 드러낸다.
			if (!this.uploads.member(userId, courseId)) {
				throw ApiException.notFound();
			}
			throw new ApiException(HttpStatus.BAD_REQUEST, "purpose_not_allowed",
					"이 과목에서 고른 용도로는 파일을 올릴 수 없습니다.");
		}
		return purpose;
	}

	/**
	 * 파일함 파일을 업로드로 다시 쓴다. 부르는 쪽 transaction 안에서, 파일함 항목을 잠가 소유와 상태를 확인한 뒤 부른다.
	 * 잠금 순서는 filebox_items → file_blobs → files → upload_sessions다. 하나라도 쓸 수 없으면 예외를 던지고, 부르는 쪽
	 * transaction이 모두 되돌린다.
	 *
	 * @param courseId 과목 용도면 그 과목. 과목 밖 용도면 null
	 * @return 만든 업로드. sources 순서
	 * @throws ApiException 용도와 과목은 {@link #checkTarget}과 같다. 검사를 마치지 않았거나 거부한 내용이면 409, 정책이 허용하지 않는
	 * 형식이면 422, 너무 크면 413
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Reused> reuse(Actor actor, Long courseId, String purposeCode, List<Source> sources) {
		UploadPurpose purpose = target(actor, courseId, purposeCode);
		long userId = actor.userId();
		this.rateLimiter.check("upload", userId, this.properties.rateLimit(), this.properties.rateWindow());
		Policy policy = this.records.policy(purpose.code())
			.orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + purpose.code()));
		List<Reused> reused = new ArrayList<>();
		for (Source source : sources) {
			Reusable file = this.jdbc.sql("""
					SELECT f.file_blob_id, f.file_extension, f.mime_type, f.file_size_bytes
					  FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
					 WHERE f.file_id = :file AND f.state = 'active' AND f.scan_state = 'clean'
					   AND b.blob_state = 'clean' AND b.purged_at IS NULL
					""")
				.param("file", source.fileId())
				.query((rs, n) -> new Reusable(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
				.optional()
				.orElseThrow(FileboxUploads::notReady);
			if (file.extension() == null || !policy.extensions().contains(file.extension())) {
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "extension_not_allowed", "올릴 수 없는 파일 형식입니다.");
			}
			if (policy.maxFileBytes() != null && file.sizeBytes() > policy.maxFileBytes()) {
				throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_large", "파일이 너무 큽니다.");
			}
			// 잠근 뒤 다시 확인한다. 검사 판정, GC의 삭제 표시와 같은 blob 행에서 차례를 기다린다.
			int referenced = this.jdbc.sql("""
					UPDATE file_blobs SET reference_count = reference_count + 1
					 WHERE file_blob_id = :blob AND blob_state = 'clean' AND purged_at IS NULL
					""").param("blob", file.blobId()).update();
			if (referenced == 0) {
				throw notReady();
			}
			long fileId = this.records.insertFile(file.blobId(), source.name(), file.extension(), file.mimeType(),
					file.sizeBytes(), userId, courseId, purpose.code(), "clean");
			this.jdbc.sql("UPDATE files SET source_filebox_item_id = :item WHERE file_id = :file")
				.param("item", source.itemId())
				.param("file", fileId)
				.update();
			long uploadId = this.records.insertUpload(policy.id(), userId, courseId, fileId, null, null, "clean",
					this.properties.ttl());
			Upload upload = this.records.find(uploadId, userId).orElseThrow();
			reused.add(new Reused(upload.uploadId(), source.itemId(), upload.state(), upload.name(), upload.sizeBytes(),
					upload.expiresAt()));
		}
		return reused;
	}

	private static ApiException notReady() {
		return new ApiException(HttpStatus.CONFLICT, "not_ready", "검사를 마치지 않았거나 거부된 파일은 쓸 수 없습니다.");
	}

	/** 파일함이 업로드 기록과 같은 transaction에서 하는 일. 예외를 던지면 업로드 전체가 되돌아간다. */
	public interface Target {

		/** 내용 객체를 잠그기 전에 부른다. 용량과 항목 수를 잡는다. */
		void reserve(long sizeBytes);

		/** 파일을 기록한 뒤 부른다. 파일함 항목을 만든다. */
		void place(long fileId, String name);

	}

	/**
	 * @param itemId 파일함 항목 ID
	 * @param fileId 그 항목의 파일 ID
	 * @param name 새 업로드의 표시 이름. 파일함 항목 이름
	 */
	public record Source(long itemId, long fileId, String name) {
	}

	public record Reused(long uploadId, long itemId, String state, String name, long sizeBytes, Instant expiresAt) {
	}

	private record Reusable(long blobId, String extension, String mimeType, long sizeBytes) {
	}

}
