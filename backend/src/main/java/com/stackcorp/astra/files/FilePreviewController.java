package com.stackcorp.astra.files;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.files.ConversionRecords.Thumbnail;
import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.PreviewService.Preview;
import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliveryCookies.DeliveryCookie;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * 문서 미리보기(EP-0125, EP-0136), 썸네일(EP-0118), 이미지 보기(EP-0130)의 대체 계약(ADR-0033). 모두 파일을 첨부한 부모 객체의 읽기
 * 권한을 요청마다 판정하고(CHG-06), 권한이 없거나 다른 과목 경로면 404다. 요청에 사용자 ID, 뷰어 token, 저장 경로를 받지 않는다
 * (SEC-17, FILES-13).
 */
@RestController
class FilePreviewController {

	private static final String SANDBOX = "default-src 'none'; sandbox";

	private final FileAccess access;

	private final FileRecords files;

	private final PreviewService previews;

	private final ConversionRecords conversions;

	private final DeliveryCookies cookies;

	private final ObjectStore store;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	FilePreviewController(FileAccess access, FileRecords files, PreviewService previews, ConversionRecords conversions,
			DeliveryCookies cookies, ObjectStore store, RateLimiter rateLimiter, AuditLog audit) {
		this.access = access;
		this.files = files;
		this.previews = previews;
		this.conversions = conversions;
		this.cookies = cookies;
		this.store = store;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
	}

	/**
	 * 미리보기를 연다. 쪽 이미지가 있으면 쪽 수와 {@code pageImageBase}를 돌려주고 그 경로에만 유효한 짧은 cookie를 발급한다
	 * (ADR-0007 6번, ADR-0009). 변환이 필요하면 대기열에 넣고 202 {@code converting}을 돌려준다. client는 같은 요청을 다시 보낸다.
	 * 여러 번 보내도 변환은 하나다. 변환하지 않는 형식은 {@code unsupported}, 변환에 실패했으면 {@code failed}와 코드다.
	 */
	@PostMapping("/api/courses/{courseId}/files/{fileId}/preview-sessions")
	ResponseEntity<PreviewView> open(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		requireRead(actor, courseId, fileId);
		this.rateLimiter.check("file-preview", actor.userId(), 300, Duration.ofMinutes(10));
		Preview preview = this.previews.open(fileId, actor.userId());
		return switch (preview.state()) {
			case READY -> ready(actor, courseId, fileId, preview);
			case CONVERTING -> ResponseEntity.accepted()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.RETRY_AFTER, "2")
				.body(new PreviewView("converting", null, null, null, null));
			case FAILED -> ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(new PreviewView("failed", null, null, null, preview.failure().code()));
			case UNSUPPORTED -> ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(new PreviewView("unsupported", null, null, null, null));
		};
	}

	/**
	 * 첫 쪽 썸네일. worker가 변환할 때 만든 것만 내보내고, 요청으로 만들지 않는다. 형식은 저장할 때 정한 값이다. 브라우저 안에서만
	 * 잠시 캐시한다.
	 */
	@GetMapping("/api/courses/{courseId}/files/{fileId}/thumbnail/{variant}")
	ResponseEntity<byte[]> thumbnail(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId, @PathVariable String variant) {
		ThumbnailVariant chosen = ThumbnailVariant.of(variant).orElseThrow(ApiException::notFound);
		requireRead(actor, courseId, fileId);
		this.files.find(fileId).filter(FileRow::ready).orElseThrow(ApiException::notFound);
		Thumbnail thumbnail = this.conversions.thumbnail(fileId, chosen.code()).orElseThrow(ApiException::notFound);
		byte[] bytes = this.store.get(Bucket.CONTENT, thumbnail.storageKey()).orElseThrow(ApiException::notFound);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(thumbnail.contentType()))
			.contentLength(bytes.length)
			.cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", SANDBOX)
			.header("Cross-Origin-Resource-Policy", "same-origin")
			.body(bytes);
	}

	/**
	 * 이미지를 inline으로 보인다. 저장할 때 서버가 PNG나 JPEG로 판정한 파일만 받고, 그 밖의 파일은 404다. 그런 파일은 원본
	 * 내려받기(attachment)로만 받는다(FILES-09).
	 */
	@GetMapping("/api/courses/{courseId}/files/{fileId}/image")
	ResponseEntity<byte[]> image(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		requireRead(actor, courseId, fileId);
		FileRow file = this.files.find(fileId).filter(FileRow::ready).orElseThrow(ApiException::notFound);
		String type = file.inlineImageType().orElseThrow(ApiException::notFound);
		byte[] bytes = this.store.get(Bucket.CONTENT, file.storageKey()).orElseThrow(ApiException::notFound);
		this.audit.success(actor.userId(), actor.sessionId(), "file.image", "file", fileId, courseId);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(type))
			.contentLength(bytes.length)
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.inline().filename(file.name(), StandardCharsets.UTF_8).build().toString())
			.header(HttpHeaders.CACHE_CONTROL, "private, no-store")
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", SANDBOX)
			.header("Cross-Origin-Resource-Policy", "same-origin")
			.body(bytes);
	}

	private ResponseEntity<PreviewView> ready(Actor actor, long courseId, long fileId, Preview preview) {
		List<DeliveryCookie> issued = this.cookies.forDocument(preview.key());
		this.audit.success(actor.userId(), actor.sessionId(), "file.preview", "file", fileId, courseId);
		ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		issued.forEach((c) -> response.header(HttpHeaders.SET_COOKIE, c.header()));
		// 전달 서명이 없는 환경에서는 쪽 이미지를 열 수 없으므로 경로도 알리지 않는다.
		if (issued.isEmpty()) {
			return response.body(new PreviewView("ready", preview.pageCount(), null, null, null));
		}
		return response.body(new PreviewView("ready", preview.pageCount(),
				"/" + DocumentRenditionService.deliveryPrefix(preview.key()),
				Instant.now().plus(issued.getFirst().maxAge()), null));
	}

	private void requireRead(Actor actor, long courseId, long fileId) {
		if (!this.access.allowed(actor.userId(), courseId, fileId, FileAction.READ)) {
			throw ApiException.notFound();
		}
	}

	/**
	 * @param state ready, converting, failed, unsupported
	 * @param pageImageBase 쪽 이미지 경로. 쪽은 {@code {pageImageBase}NNN.jpg}(001부터)다. 전달 서명이 없는 환경에서는 null
	 * @param cookieExpiresAt 발급한 쪽 이미지 cookie의 만료 시각. 그 전에 같은 요청으로 다시 받는다
	 * @param code failed일 때 실패 코드. encrypted, invalid_document, empty, too_many_pages, too_large, timeout,
	 * unavailable, failed 중 하나다({@link PreviewService.Failure})
	 */
	record PreviewView(String state, Integer pageCount, String pageImageBase, Instant cookieExpiresAt, String code) {
	}

}
