package com.stackcorp.astra.files.avatar;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.stackcorp.astra.files.avatar.AvatarImages.Photo;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * 아바타 사진(EP-0273~EP-0277의 대체 계약, IDENTITY-10, ADR-0038). 원본의 임시 업로드·위치 조정·선택 단계는 화면(canvas)이 하고, 서버는
 * 자른 사진 하나를 받아 다시 만든다. 지우기(EP-0278)와 상태는 identity의 {@code /api/me/profile}이다.
 */
@RestController
class AvatarImageController {

	private static final String SANDBOX = "default-src 'none'; sandbox";

	private final AvatarImages images;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	AvatarImageController(AvatarImages images, RateLimiter rateLimiter, AuditLog audit) {
		this.images = images;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
	}

	/**
	 * 사진 올리기(EP-0273). multipart {@code file} 하나, PNG나 JPEG, 5MB 이하. 202 {@code processing}이고 결과는
	 * {@code GET /api/me/profile}의 {@code avatar.photo}로 본다. 다시 올리면 앞 사진을 바꾼다(대기 중인 사진은 버린다). 바꾸는 명령이라
	 * {@code Idempotency-Key}를 받지 않는다. 사용자당 10분에 10번.
	 */
	@PostMapping(path = "/api/me/profile/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<Upload> upload(@AuthenticationPrincipal Actor actor, @RequestParam("file") MultipartFile file) {
		this.rateLimiter.check("avatar-upload", actor.userId(), 10, Duration.ofMinutes(10));
		long imageId = this.images.upload(actor.userId(), file);
		this.audit.success(actor.userId(), actor.sessionId(), "account.avatar_image_upload", "profile_image", imageId,
				null);
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(new Upload("processing"));
	}

	/**
	 * 사진 보기(EP-0277의 이미지 경로). 본인과, 폐강하지 않은 과목을 함께 듣거나 가르치는 사람만 본다. 그 밖과 사진이 없는 사람은 같은
	 * 404다. 볼 수 있는 사람의 첫 사진이 아직 처리 중이면 같은 404에 {@code Retry-After: 5}를 붙인다. 사진을 바꾸는 중이면 앞 사진을
	 * 그대로 보낸다. 서버가 다시 만든 JPEG만 inline으로 보내고, 브라우저 안에서만 ETag로 다시 확인하며 캐시한다.
	 */
	@GetMapping("/api/users/{userId}/avatar")
	ResponseEntity<byte[]> avatar(@AuthenticationPrincipal Actor actor, @PathVariable long userId,
			@RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		Photo photo = this.images.visible(actor.userId(), userId);
		String etag = "\"" + photo.current().sha256() + "\"";
		if (ifNoneMatch != null && (ifNoneMatch.contains(etag) || ifNoneMatch.contains("W/" + etag))) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
				.eTag(etag)
				.cacheControl(CacheControl.noCache().cachePrivate())
				.build();
		}
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_JPEG)
			.contentLength(photo.jpeg().length)
			.eTag(etag)
			.cacheControl(CacheControl.noCache().cachePrivate())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("avatar.jpg").build().toString())
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", SANDBOX)
			.header("Cross-Origin-Resource-Policy", "same-origin")
			.body(photo.jpeg());
	}

	/** @param state processing. 결과는 내정보의 avatar.photo.state다 */
	record Upload(String state) {
	}

}
