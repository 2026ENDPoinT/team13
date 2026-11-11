package com.stackcorp.astra.learning;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.stackcorp.astra.files.api.VideoKeys;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningAccess.Activity;

/**
 * HLS manifest의 key URI(ADR-0007 8번). 요청마다 권한을 판정하고 캐시하지 않는다.
 * online_lecture_items.content_path는 delivery bucket의 manifest 경로 vod/{assetKey}/...다.
 * 권한이 없거나 다른 과목의 항목이면 존재를 드러내지 않도록 404로 응답한다.
 */
@RestController
class LectureKeyController {

	private static final Pattern CONTENT_PATH = Pattern.compile("vod/(" + VideoKeys.ASSET_KEY_PATTERN + ")/[^/]+\\.m3u8");

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final VideoKeys keys;

	LectureKeyController(JdbcClient jdbc, LearningAccess access, VideoKeys keys) {
		this.jdbc = jdbc;
		this.access = access;
		this.keys = keys;
	}

	@GetMapping("/api/courses/{courseId}/lecture-items/{itemId}/hls-key")
	ResponseEntity<byte[]> key(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long itemId) {
		Item item = this.jdbc.sql("""
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
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		Matcher m = item.contentPath() == null ? null : CONTENT_PATH.matcher(item.contentPath());
		Optional<byte[]> key = m != null && m.matches() ? this.keys.find(m.group(1)) : Optional.empty();
		return key
			.map((bytes) -> ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.body(bytes))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
	}

	private record Item(Activity activity, String contentPath) {
	}

}
