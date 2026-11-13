package com.stackcorp.astra.learning;

import java.net.URI;
import java.time.Duration;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아직 받아 두지 않은 학교 파일({@code fileId}가 {@code "school-file-<id>"})을 여는 경로(ADR-0043). 학생이 파일을 열면(내려받기,
 * 미리보기) 그 학생의 학교 session으로 받아 과목 파일로 저장한 뒤 같은 요청을 서비스 파일 경로로 넘긴다. 그다음부터 목록은 서비스 파일
 * ID를 준다. 썸네일·요약처럼 목록이 미리 부르는 경로는 받지 않는다(학교에 요청하지 않는다).
 */
@RestController
class SchoolFileController {

	static final int OPENS_PER_MINUTE = 30;

	private final SchoolContent content;

	private final RateLimiter rateLimiter;

	SchoolFileController(SchoolContent content, RateLimiter rateLimiter) {
		this.content = content;
		this.rateLimiter = rateLimiter;
	}

	/** 받아 둔 뒤 303으로 {@code GET /api/courses/{courseId}/files/{fileId}/content}에 넘긴다. */
	@GetMapping("/api/courses/{courseId}/files/school-file-{schoolFileId}/content")
	ResponseEntity<Void> content(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long schoolFileId) {
		long fileId = open(actor, courseId, schoolFileId);
		return redirect(HttpStatus.SEE_OTHER, "/api/courses/" + courseId + "/files/" + fileId + "/content");
	}

	/** 받아 둔 뒤 307로 {@code POST /api/courses/{courseId}/files/{fileId}/preview-sessions}에 넘긴다. */
	@PostMapping("/api/courses/{courseId}/files/school-file-{schoolFileId}/preview-sessions")
	ResponseEntity<Void> preview(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long schoolFileId) {
		long fileId = open(actor, courseId, schoolFileId);
		return redirect(HttpStatus.TEMPORARY_REDIRECT,
				"/api/courses/" + courseId + "/files/" + fileId + "/preview-sessions");
	}

	private long open(Actor actor, long courseId, long schoolFileId) {
		this.rateLimiter.check("school-file-open", actor.userId(), OPENS_PER_MINUTE, Duration.ofMinutes(1));
		SchoolContent.OpenedFile opened = this.content.open(actor, courseId, schoolFileId);
		if (!opened.ready()) {
			throw new ApiException(HttpStatus.CONFLICT, "file_scanning", "파일을 검사하고 있습니다. 잠시 뒤 다시 열어 주세요.",
					Duration.ofSeconds(5));
		}
		return opened.fileId();
	}

	private static ResponseEntity<Void> redirect(HttpStatus status, String location) {
		return ResponseEntity.status(status).location(URI.create(location)).cacheControl(CacheControl.noStore()).build();
	}

}
