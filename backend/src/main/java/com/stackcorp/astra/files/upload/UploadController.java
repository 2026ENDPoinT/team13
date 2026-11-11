package com.stackcorp.astra.files.upload;

import java.time.Instant;

import com.stackcorp.astra.files.upload.UploadRecords.Upload;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 API(EP-0127, EP-0137의 대체 계약). 올린 사람만 자기 업로드를 보고 지운다.
 * 업로드한 파일은 부모 객체에 첨부하기 전에는 누구도 내려받지 못한다.
 */
@RestController
class UploadController {

	private final UploadService uploads;

	private final AuditLog audit;

	UploadController(UploadService uploads, AuditLog audit) {
		this.uploads = uploads;
		this.audit = audit;
	}

	@PostMapping(path = "/api/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<UploadView> upload(@AuthenticationPrincipal Actor actor, @RequestParam("file") MultipartFile file,
			@RequestParam long courseId, @RequestParam String purpose,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		Upload upload = this.uploads.receive(actor.userId(), courseId, purpose, file, idempotencyKey);
		this.audit.success(actor.userId(), actor.sessionId(), "upload.create", "upload", upload.uploadId(), courseId);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(UploadView.of(upload));
	}

	@GetMapping("/api/uploads/{uploadId}")
	ResponseEntity<UploadView> get(@AuthenticationPrincipal Actor actor, @PathVariable long uploadId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(UploadView.of(this.uploads.get(actor.userId(), uploadId)));
	}

	@DeleteMapping("/api/uploads/{uploadId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long uploadId) {
		this.uploads.delete(actor.userId(), uploadId);
		this.audit.success(actor.userId(), actor.sessionId(), "upload.delete", "upload", uploadId, null);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	record UploadView(long uploadId, String state, String name, long sizeBytes, Instant expiresAt) {

		static UploadView of(Upload upload) {
			return new UploadView(upload.uploadId(), upload.state(), upload.name(), upload.sizeBytes(),
					upload.expiresAt());
		}

	}

}
