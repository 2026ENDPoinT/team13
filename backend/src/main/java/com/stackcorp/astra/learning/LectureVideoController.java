package com.stackcorp.astra.learning;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliveryCookies.DeliveryCookie;
import com.stackcorp.astra.files.api.VideoKeys;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningAccess.Activity;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의 영상의 재생 허가와 HLS key(ADR-0007 6, 8번). 두 API 모두 요청마다 권한을 판정하고 캐시하지 않는다.
 * online_lecture_items.content_path는 delivery bucket의 manifest 경로 vod/{assetKey}/{이름}.m3u8이다. 올린 영상은 변환 worker가
 * 게시를 마친 뒤에만 채우고 원본이 삭제되면 비운다(ADR-0016). 비어 있는 콘텐츠(변환 중, 실패, 삭제)는 404다.
 * 권한이 없거나 다른 과목의 항목이면 존재를 드러내지 않도록 404로 응답한다.
 */
@RestController
class LectureVideoController {

	private static final Pattern CONTENT_PATH = Pattern.compile("vod/(" + VideoKeys.ASSET_KEY_PATTERN + ")/[^/]+\\.m3u8");

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final VideoKeys keys;

	private final DeliveryCookies cookies;

	LectureVideoController(JdbcClient jdbc, LearningAccess access, VideoKeys keys, DeliveryCookies cookies) {
		this.jdbc = jdbc;
		this.access = access;
		this.keys = keys;
		this.cookies = cookies;
	}

	/**
	 * 이 강의의 전달 경로에만 유효한 짧은 cookie를 발급한다. player는 만료 전에 다시 부른다. 권한이 사라지면 다시 발급되지 않는다.
	 */
	@PostMapping("/api/courses/{courseId}/lecture-items/{itemId}/playback")
	ResponseEntity<Playback> playback(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId) {
		Video video = video(actor, courseId, itemId);
		List<DeliveryCookie> issued = this.cookies.forVideo(video.assetKey());
		if (issued.isEmpty()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "delivery_unavailable", "영상 전달이 설정되지 않았습니다.");
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		issued.forEach((c) -> response.header(HttpHeaders.SET_COOKIE, c.header()));
		return response.body(new Playback("/" + video.contentPath(), this.cookies.videoTtl().toSeconds()));
	}

	@GetMapping("/api/courses/{courseId}/lecture-items/{itemId}/hls-key")
	ResponseEntity<byte[]> key(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId) {
		Video video = video(actor, courseId, itemId);
		return this.keys.find(video.assetKey())
			.map((bytes) -> ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(bytes))
			.orElseThrow(ApiException::notFound);
	}

	private Video video(Actor actor, long courseId, long itemId) {
		String contentPath = this.jdbc.sql("""
				SELECT a.activity_id, a.course_id, a.published_yn, a.open_at, i.content_path
				  FROM online_lecture_items i JOIN learning_activities a ON a.activity_id = i.activity_id
				 WHERE i.online_lecture_item_id = :item AND a.course_id = :course
				""")
			.param("item", itemId)
			.param("course", courseId)
			.query((rs, n) -> new Item(new Activity(rs.getLong(1), rs.getLong(2), rs.getBoolean(3),
					rs.getObject(4, java.time.LocalDateTime.class)), rs.getString(5)))
			.optional()
			.filter((i) -> this.access.canView(actor.userId(), i.activity()))
			.map(Item::contentPath)
			.orElseThrow(ApiException::notFound);
		Matcher m = contentPath == null ? null : CONTENT_PATH.matcher(contentPath);
		if (m == null || !m.matches()) {
			throw ApiException.notFound();
		}
		return new Video(contentPath, m.group(1));
	}

	private record Item(Activity activity, String contentPath) {
	}

	private record Video(String contentPath, String assetKey) {
	}

	record Playback(String manifestUrl, long expiresInSeconds) {
	}

}
