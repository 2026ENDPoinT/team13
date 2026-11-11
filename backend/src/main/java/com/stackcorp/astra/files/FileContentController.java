package com.stackcorp.astra.files;

import java.io.InputStream;

import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원본 파일 내려받기(EP-0122, EP-0129의 대체 계약). 파일을 첨부한 부모 객체의 권한을 요청마다 판정한다(CHG-06).
 * 요청에 사용자 ID를 받지 않는다. 응답 header는 {@link Downloads}가 정한다(FILES-09).
 */
@RestController
class FileContentController {

	private final FileAccess access;

	private final FileRecords files;

	private final ObjectStore store;

	private final AuditLog audit;

	FileContentController(FileAccess access, FileRecords files, ObjectStore store, AuditLog audit) {
		this.access = access;
		this.files = files;
		this.store = store;
		this.audit = audit;
	}

	@GetMapping("/api/courses/{courseId}/files/{fileId}/content")
	ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		if (!this.access.allowed(actor.userId(), courseId, fileId, FileAction.READ)) {
			throw ApiException.notFound();
		}
		FileRow file = this.files.find(fileId).filter(FileRow::ready).orElseThrow(ApiException::notFound);
		InputStream content = this.store.open(Bucket.CONTENT, file.storageKey()).orElseThrow(ApiException::notFound);
		this.audit.success(actor.userId(), actor.sessionId(), "file.download", "file", fileId, courseId);
		return Downloads.attachment(file.name(), file.detectedMimeType(), file.sizeBytes(), content);
	}

}
