package com.stackcorp.astra.files.upload;

import com.stackcorp.astra.files.upload.UploadController.UploadView;
import com.stackcorp.astra.identity.api.Actor;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 큰 파일의 업로드(EP-0127의 대체 계약, ADR-0016). 본문은 파일 바이트 그대로(application/octet-stream)이고 크기는
 * Content-Length로 미리 알린다. 과목, 용도, 파일 이름은 query로 받는다. 권한, 용도, 이름, 크기를 본문을 읽기 전에 판정하고
 * 거부하면 본문을 읽지 않는다. 응답과 이후 흐름(조회, 삭제, 첨부)은 POST /api/uploads와 같다.
 */
@RestController
class StreamUploadController {

	private final StreamUploadService uploads;

	StreamUploadController(StreamUploadService uploads) {
		this.uploads = uploads;
	}

	@PostMapping(path = "/api/uploads/stream", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	ResponseEntity<UploadView> upload(@AuthenticationPrincipal Actor actor, @RequestParam long courseId,
			@RequestParam String purpose, @RequestParam String name,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			HttpServletRequest request) {
		var upload = this.uploads.receive(actor.userId(), courseId, purpose, name, idempotencyKey,
				request.getHeader(HttpHeaders.CONTENT_LENGTH), request::getInputStream);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(UploadView.of(upload));
	}

}
