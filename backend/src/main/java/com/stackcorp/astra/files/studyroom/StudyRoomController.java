package com.stackcorp.astra.files.studyroom;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.studyroom.StudyRoomService.CourseView;
import com.stackcorp.astra.files.studyroom.StudyRoomService.FolderView;
import com.stackcorp.astra.files.studyroom.StudyRoomService.ItemKind;
import com.stackcorp.astra.files.studyroom.StudyRoomService.NewRecording;
import com.stackcorp.astra.files.studyroom.StudyRoomService.PlacementView;
import com.stackcorp.astra.files.studyroom.StudyRoomService.RangeNotSatisfiable;
import com.stackcorp.astra.files.studyroom.StudyRoomService.RecordingList;
import com.stackcorp.astra.files.studyroom.StudyRoomService.RecordingView;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 학습실의 내 폴더, 항목 배치, 강의 녹음 API(ADR-0040). 프론트엔드가 브라우저에만 두던 폴더 트리(src/shared/study-folders.ts)와
 * 녹음(src/shared/course-recordings.ts)을 그대로 옮긴다. 로그인한 사용자 누구나 자기 학습실만 쓴다. 사용자는 session principal에서만
 * 정한다(SC-02).
 */
@RestController
class StudyRoomController {

	static final String BASE = "/api/me/study-room";

	private static final Pattern VERSION = Pattern.compile("\"(0|[1-9][0-9]{0,8})\"");

	private static final Pattern DURATION = Pattern.compile("[0-9]{1,9}");

	private static final long MAX_DURATION_MS = Duration.ofHours(24).toMillis();

	/** 브라우저와 서버 시계가 어긋나도 받는 녹음 시작 일시의 여유. */
	private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

	private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");

	private static final Set<String> SOURCES = Set.of("mic", "tab", "audio");

	private static final int MAX_ORDER = 100;

	private final StudyRoomService studyRoom;

	StudyRoomController(StudyRoomService studyRoom) {
		this.studyRoom = studyRoom;
	}

	/** 과목 하나의 내 폴더, 녹음, 배치, 사용량. */
	@GetMapping(BASE + "/courses/{courseId}")
	ResponseEntity<CourseView> course(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		return ok(this.studyRoom.course(actor.userId(), positive(courseId)));
	}

	/** 폴더 만들기. 201. Idempotency-Key 지원. */
	@PostMapping(BASE + "/courses/{courseId}/folders")
	ResponseEntity<FolderView> createFolder(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody Name body, @RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		FolderView created = this.studyRoom.createFolder(actor, positive(courseId), body.name(), idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
	}

	/** 폴더 이름 바꾸기. If-Match 필수. */
	@PatchMapping(BASE + "/folders/{folderId}")
	ResponseEntity<FolderView> renameFolder(@AuthenticationPrincipal Actor actor, @PathVariable long folderId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody Name body) {
		return ok(this.studyRoom.renameFolder(actor, positive(folderId), expectedVersion(ifMatch), body.name()));
	}

	/** 폴더 지우기. 그 안의 항목은 과목 학습실 맨 위로 돌아간다. If-Match 필수. */
	@DeleteMapping(BASE + "/folders/{folderId}")
	ResponseEntity<Void> deleteFolder(@AuthenticationPrincipal Actor actor, @PathVariable long folderId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		this.studyRoom.deleteFolder(actor, positive(folderId), expectedVersion(ifMatch));
		return ResponseEntity.noContent().build();
	}

	/** 폴더 순서 바꾸기. 과목의 내 폴더 전부를 새 순서로, 폴더마다 마지막으로 읽은 version과 함께 보낸다. */
	@PutMapping(BASE + "/courses/{courseId}/folder-order")
	ResponseEntity<FolderList> reorder(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody FolderOrder body) {
		if (body.folders() == null || body.folders().isEmpty() || body.folders().size() > MAX_ORDER) {
			throw ApiException.invalid("folders는 1개 이상 " + MAX_ORDER + "개 이하여야 합니다.");
		}
		LinkedHashMap<Long, Integer> order = new LinkedHashMap<>();
		for (Versioned folder : body.folders()) {
			if (folder == null || folder.id() == null || folder.id() < 1 || folder.version() == null
					|| folder.version() < 1 || order.put(folder.id(), folder.version()) != null) {
				throw ApiException.invalid("folders가 올바르지 않습니다.");
			}
		}
		return ok(new FolderList(this.studyRoom.reorder(actor, positive(courseId), order)));
	}

	/**
	 * 항목을 폴더에 두거나 다른 폴더로 옮긴다. kind는 course-files(강의자료 파일 ID) 또는 recordings(녹음 ID)다. 배치가 없는 항목은
	 * If-Match "0", 있으면 그 배치의 version을 보낸다.
	 */
	@PutMapping(BASE + "/placements/{kind}/{itemId}")
	ResponseEntity<PlacementView> place(@AuthenticationPrincipal Actor actor, @PathVariable String kind,
			@PathVariable long itemId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody Place body) {
		ItemKind item = kind(kind);
		if (body.folderId() == null || body.folderId() < 1) {
			throw ApiException.invalid("folderId가 올바르지 않습니다.");
		}
		return ok(this.studyRoom.place(actor, item, positive(itemId), expectedVersion(ifMatch), body.folderId()));
	}

	/** 배치를 지워 항목을 과목 학습실 맨 위로 돌려보낸다. If-Match 필수. */
	@DeleteMapping(BASE + "/placements/{kind}/{itemId}")
	ResponseEntity<Void> unplace(@AuthenticationPrincipal Actor actor, @PathVariable String kind,
			@PathVariable long itemId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		ItemKind item = kind(kind);
		this.studyRoom.unplace(actor, item, positive(itemId), expectedVersion(ifMatch));
		return ResponseEntity.noContent().build();
	}

	/**
	 * 녹음 올리기. 201. 본문은 multipart: file, title, source(mic, tab, audio), durationMs, recordedAt(ISO-8601), folderId(선택).
	 * Idempotency-Key 지원(업로드와 같은 형식).
	 */
	@PostMapping(path = BASE + "/courses/{courseId}/recordings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<RecordingView> createRecording(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam("file") MultipartFile file, @RequestParam(required = false) String title,
			@RequestParam(required = false) String source, @RequestParam(required = false) String durationMs,
			@RequestParam(required = false) String recordedAt, @RequestParam(required = false) Long folderId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (source == null || !SOURCES.contains(source)) {
			throw ApiException.invalid("source는 mic, tab, audio 가운데 하나여야 합니다.");
		}
		if (durationMs == null || !DURATION.matcher(durationMs).matches() || Long.parseLong(durationMs) < 1
				|| Long.parseLong(durationMs) > MAX_DURATION_MS) {
			throw ApiException.invalid("durationMs는 1 이상 " + MAX_DURATION_MS + " 이하의 정수여야 합니다.");
		}
		if (folderId != null && folderId < 1) {
			throw ApiException.invalid("folderId가 올바르지 않습니다.");
		}
		NewRecording meta = new NewRecording(StudyRoomService.title(title), source, Long.parseLong(durationMs),
				recordedAt(recordedAt), folderId);
		RecordingView created = this.studyRoom.createRecording(actor, positive(courseId), file, meta, idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.eTag(etag(created.version()))
			.body(created);
	}

	/** 모든 과목의 내 녹음과 사용량. 과목을 잃은 뒤에도 내 녹음을 찾아 지울 수 있다. */
	@GetMapping(BASE + "/recordings")
	ResponseEntity<RecordingList> recordings(@AuthenticationPrincipal Actor actor) {
		return ok(this.studyRoom.recordings(actor.userId()));
	}

	@GetMapping(BASE + "/recordings/{recordingId}")
	ResponseEntity<RecordingView> recording(@AuthenticationPrincipal Actor actor, @PathVariable long recordingId) {
		RecordingView recording = this.studyRoom.recording(actor.userId(), positive(recordingId));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(etag(recording.version())).body(recording);
	}

	/** 녹음 제목 바꾸기. If-Match 필수. */
	@PatchMapping(BASE + "/recordings/{recordingId}")
	ResponseEntity<RecordingView> renameRecording(@AuthenticationPrincipal Actor actor,
			@PathVariable long recordingId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody Title body) {
		RecordingView renamed = this.studyRoom.renameRecording(actor, positive(recordingId), expectedVersion(ifMatch),
				body.title());
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(etag(renamed.version())).body(renamed);
	}

	/** 녹음 지우기. 배치도 함께 지우고 파일 참조를 놓는다. If-Match 필수. */
	@DeleteMapping(BASE + "/recordings/{recordingId}")
	ResponseEntity<Void> deleteRecording(@AuthenticationPrincipal Actor actor, @PathVariable long recordingId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		this.studyRoom.deleteRecording(actor, positive(recordingId), expectedVersion(ifMatch));
		return ResponseEntity.noContent().build();
	}

	/** 녹음 재생과 내려받기. Range 하나를 받는다(206). 언제나 attachment다. */
	@GetMapping(BASE + "/recordings/{recordingId}/content")
	ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal Actor actor, @PathVariable long recordingId,
			@RequestHeader(name = HttpHeaders.RANGE, required = false) String range) {
		return this.studyRoom.content(actor, positive(recordingId), range);
	}

	/** 재생 전 크기와 형식만 묻는 HEAD. 녹음 행만 읽고 저장소를 열지 않는다. GET이 HEAD를 대신 받지 않도록 따로 둔다. */
	@RequestMapping(path = BASE + "/recordings/{recordingId}/content", method = RequestMethod.HEAD)
	ResponseEntity<Void> contentHead(@AuthenticationPrincipal Actor actor, @PathVariable long recordingId) {
		return this.studyRoom.contentHead(actor, positive(recordingId));
	}

	@ExceptionHandler(RangeNotSatisfiable.class)
	ResponseEntity<ProblemDetail> rangeNotSatisfiable(RangeNotSatisfiable ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
				"요청한 구간이 녹음 밖에 있습니다.");
		problem.setProperty("code", "range_not_satisfiable");
		return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_RANGE, "bytes */" + ex.size())
			.body(problem);
	}

	private static ItemKind kind(String kind) {
		return switch (kind) {
			case "course-files" -> ItemKind.COURSE_FILE;
			case "recordings" -> ItemKind.RECORDING;
			default -> throw ApiException.notFound();
		};
	}

	private static Instant recordedAt(String value) {
		Instant at;
		try {
			at = value == null ? null : OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeParseException ex) {
			at = null;
		}
		if (at == null || at.isBefore(EARLIEST) || at.isAfter(Instant.now().plus(CLOCK_SKEW))) {
			throw ApiException.invalid("recordedAt은 지금보다 늦지 않은 ISO-8601 일시(예: 2026-11-13T05:05:00Z)여야 합니다.");
		}
		return at;
	}

	private static long positive(long id) {
		if (id < 1) {
			throw ApiException.invalid("ID가 올바르지 않습니다.");
		}
		return id;
	}

	// If-Match: "3". 약한 비교(W/)와 *는 받지 않는다. 마지막으로 읽은 version을 정확히 보내야 한다(ADR-0011). 배치가 없는 항목은 "0".
	private static int expectedVersion(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
					"If-Match에 마지막으로 읽은 version을 보내야 합니다.");
		}
		Matcher m = VERSION.matcher(ifMatch.strip());
		if (!m.matches()) {
			throw ApiException.invalid("If-Match 형식이 올바르지 않습니다.");
		}
		return Integer.parseInt(m.group(1));
	}

	private static String etag(int version) {
		return "\"" + version + "\"";
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	record Name(String name) {
	}

	record Title(String title) {
	}

	record Place(Long folderId) {
	}

	record Versioned(Long id, Integer version) {
	}

	record FolderOrder(List<Versioned> folders) {
	}

	record FolderList(List<FolderView> folders) {
	}

}
