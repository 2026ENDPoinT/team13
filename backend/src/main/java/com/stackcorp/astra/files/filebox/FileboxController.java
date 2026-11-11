package com.stackcorp.astra.files.filebox;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.filebox.FileboxService.ItemView;
import com.stackcorp.astra.files.filebox.FileboxService.Listing;
import com.stackcorp.astra.files.filebox.FileboxService.ReferenceView;
import com.stackcorp.astra.files.filebox.FileboxService.ReuseReceipt;
import com.stackcorp.astra.files.filebox.FileboxService.TrashPage;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 개인 파일함 API(EP-0244~0261, EP-0126의 대체 계약, backend/60 7절). 로그인한 사용자 누구나 자기 파일함만 쓴다. 사용자는 session
 * principal에서만 정하고 요청의 사용자 값은 받지 않는다(SC-02). 폼 셸(EP-0244, EP-0247, EP-0249, EP-0253~0255, EP-0258, EP-0261)은
 * 정적 client 화면이 대신하고 서버는 JSON만 응답한다. 모든 명령은 결과를 JSON으로 돌려준다(FILES-19).
 */
@RestController
class FileboxController {

	static final int MAX_IDS = 100;

	private static final int MAX_PAGE = 100;

	private static final int MAX_QUERY = 100;

	private static final Pattern VERSION = Pattern.compile("\"([1-9][0-9]{0,8})\"");

	private final FileboxService filebox;

	FileboxController(FileboxService filebox) {
		this.filebox = filebox;
	}

	/**
	 * 목록(EP-0257, EP-0250). parentId가 없으면 루트다. sort는 name, size, date이고 order는 asc, desc다. 기본은 date desc,
	 * name은 asc다. q는 그 폴더 바로 아래 항목 이름에서 찾는다. 다음 쪽은 nextCursor로 받는다.
	 */
	@GetMapping("/api/filebox/items")
	ResponseEntity<Listing> list(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) Long parentId,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "date") String sort,
			@RequestParam(required = false) String order, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		ItemSort key = ItemSort.of(sort).orElseThrow(() -> ApiException.invalid("sort 값이 올바르지 않습니다."));
		boolean ascending;
		if (order == null) {
			ascending = key.ascendingByDefault();
		}
		else if ("asc".equals(order) || "desc".equals(order)) {
			ascending = "asc".equals(order);
		}
		else {
			throw ApiException.invalid("order 값이 올바르지 않습니다.");
		}
		String query = q == null || q.isBlank() ? null : q.strip();
		if (query != null && (query.length() > MAX_QUERY || query.codePoints().anyMatch(Character::isISOControl))) {
			throw ApiException.invalid("q는 " + MAX_QUERY + "자 이하여야 합니다.");
		}
		return ok(this.filebox.list(actor.userId(), positive(parentId, "parentId"), query, key, ascending,
				cursor, pageSize(size)));
	}

	/** 휴지통(EP-0260). 휴지통에 넣은 root 항목을 넣은 일시 역순으로. */
	@GetMapping("/api/filebox/trash")
	ResponseEntity<TrashPage> trash(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		return ok(this.filebox.trash(actor.userId(), cursor, pageSize(size)));
	}

	/** 폴더 만들기(EP-0248). 201. Idempotency-Key 지원. */
	@PostMapping("/api/filebox/folders")
	ResponseEntity<ItemView> createFolder(@AuthenticationPrincipal Actor actor, @RequestBody CreateFolder body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		ItemView created = this.filebox.createFolder(actor, positive(body.parentId(), "parentId"), body.name(),
				idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
	}

	/** 이름 바꾸기(EP-0252). If-Match에 항목의 version을 보낸다. 없으면 428, 다르면 409. */
	@PatchMapping("/api/filebox/items/{itemId}")
	ResponseEntity<ItemView> rename(@AuthenticationPrincipal Actor actor, @PathVariable long itemId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody Rename body) {
		int version = expectedVersion(ifMatch);
		return ok(this.filebox.rename(actor, itemId, version, body.name()));
	}

	/** 이동(EP-0251). 항목마다 마지막으로 읽은 version을 보낸다. targetFolderId가 없으면 루트. */
	@PostMapping("/api/filebox/items/move")
	ResponseEntity<Count> move(@AuthenticationPrincipal Actor actor, @RequestBody Move body) {
		if (body.items() == null || body.items().isEmpty() || body.items().size() > MAX_IDS) {
			throw ApiException.invalid("items는 1개 이상 " + MAX_IDS + "개 이하여야 합니다.");
		}
		Map<Long, Integer> versions = new LinkedHashMap<>();
		for (Versioned item : body.items()) {
			if (item == null || item.id() == null || item.id() < 1 || item.version() == null || item.version() < 1
					|| versions.put(item.id(), item.version()) != null) {
				throw ApiException.invalid("items가 올바르지 않습니다.");
			}
		}
		return ok(new Count(
				this.filebox.move(actor, positive(body.targetFolderId(), "targetFolderId"), versions)));
	}

	/** 휴지통에 넣기(EP-0245). 하위 항목은 서버가 계산한다. */
	@PostMapping("/api/filebox/items/trash")
	ResponseEntity<Count> trashItems(@AuthenticationPrincipal Actor actor, @RequestBody Ids body) {
		return ok(new Count(this.filebox.trash(actor, ids(body))));
	}

	/** 복원(EP-0259). 휴지통 root만 받는다. */
	@PostMapping("/api/filebox/items/restore")
	ResponseEntity<Count> restore(@AuthenticationPrincipal Actor actor, @RequestBody Ids body) {
		return ok(new Count(this.filebox.restore(actor, ids(body))));
	}

	/** 영구 삭제(EP-0246). 휴지통 root만 받는다. */
	@PostMapping("/api/filebox/items/purge")
	ResponseEntity<Count> purge(@AuthenticationPrincipal Actor actor, @RequestBody Ids body) {
		return ok(new Count(this.filebox.purge(actor, ids(body))));
	}

	/** 휴지통 비우기. */
	@PostMapping("/api/filebox/trash/empty")
	ResponseEntity<Count> emptyTrash(@AuthenticationPrincipal Actor actor) {
		return ok(new Count(this.filebox.emptyTrash(actor)));
	}

	/** 파일함에 올리기. 201. Idempotency-Key 지원(업로드와 같은 형식). */
	@PostMapping(path = "/api/filebox/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<ItemView> upload(@AuthenticationPrincipal Actor actor, @RequestParam("file") MultipartFile file,
			@RequestParam(required = false) Long parentId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		ItemView created = this.filebox.upload(actor, positive(parentId, "parentId"), file, idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
	}

	/** 내 파일 내려받기(EP-0129의 파일함 경로). 언제나 attachment다. */
	@GetMapping("/api/filebox/items/{itemId}/content")
	ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal Actor actor, @PathVariable long itemId) {
		return this.filebox.content(actor, itemId);
	}

	/** 이 파일을 다시 써서 첨부한 곳(EP-0256). 지금 볼 수 있는 곳만. */
	@GetMapping("/api/filebox/items/{itemId}/references")
	ResponseEntity<References> references(@AuthenticationPrincipal Actor actor, @PathVariable long itemId) {
		return ok(new References(this.filebox.references(actor.userId(), itemId)));
	}

	/**
	 * 파일함 파일로 과목 업로드를 만든다(EP-0126). 201. 만든 업로드는 clean이고, 부모 저장의 첨부(uploadIds, attachments.add)로
	 * 보낸다. Idempotency-Key 지원. 거부는 다른 사람의 것을 드러내지 않는 범위에서 구분한다.
	 * <ul>
	 * <li>내 항목이 아니거나 없는 항목, 구성원이 아닌 과목: 404</li>
	 * <li>구성원인 과목이지만 내 역할로 그 용도에 올릴 수 없음: 400 {@code purpose_not_allowed}</li>
	 * <li>내 항목이 휴지통에 있음: 409 {@code not_active}, 폴더임: 409 {@code folder_not_allowed}</li>
	 * </ul>
	 */
	@PostMapping("/api/uploads/from-filebox")
	ResponseEntity<ReuseReceipt> fromFilebox(@AuthenticationPrincipal Actor actor, @RequestBody FromFilebox body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (body.courseId() == null || body.courseId() < 1) {
			throw ApiException.invalid("courseId가 올바르지 않습니다.");
		}
		if (body.purpose() == null || body.purpose().isBlank()) {
			throw ApiException.invalid("purpose 값이 올바르지 않습니다.");
		}
		ReuseReceipt receipt = this.filebox.reuse(actor, body.courseId(), body.purpose(),
				ids(new Ids(body.itemIds())), idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(receipt);
	}

	static List<Long> ids(Ids body) {
		List<Long> ids = body == null ? null : body.ids();
		if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch((id) -> id == null || id < 1)
				|| new LinkedHashSet<>(ids).size() != ids.size()) {
			throw ApiException.invalid("ids는 중복 없는 1개 이상 " + MAX_IDS + "개 이하의 ID여야 합니다.");
		}
		return List.copyOf(ids);
	}

	private static Long positive(Long id, String field) {
		if (id != null && id < 1) {
			throw ApiException.invalid(field + "가 올바르지 않습니다.");
		}
		return id;
	}

	private static int pageSize(int size) {
		if (size < 1 || size > MAX_PAGE) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE + " 이하여야 합니다.");
		}
		return size;
	}

	// If-Match: "3". 약한 비교(W/)와 *는 받지 않는다. 마지막으로 읽은 version을 정확히 보내야 한다(ADR-0011).
	private static int expectedVersion(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
					"If-Match에 항목의 version을 보내야 합니다.");
		}
		Matcher m = VERSION.matcher(ifMatch.strip());
		if (!m.matches()) {
			throw ApiException.invalid("If-Match 형식이 올바르지 않습니다.");
		}
		return Integer.parseInt(m.group(1));
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	record CreateFolder(Long parentId, String name) {
	}

	record Rename(String name) {
	}

	record Versioned(Long id, Integer version) {
	}

	record Move(Long targetFolderId, List<Versioned> items) {
	}

	record Ids(List<Long> ids) {
	}

	record FromFilebox(List<Long> itemIds, Long courseId, String purpose) {
	}

	record Count(int count) {
	}

	record References(List<ReferenceView> references) {
	}

}
