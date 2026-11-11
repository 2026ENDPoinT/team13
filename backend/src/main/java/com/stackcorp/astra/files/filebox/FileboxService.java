package com.stackcorp.astra.files.filebox;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.files.Downloads;
import com.stackcorp.astra.files.FileReferences;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.files.filebox.FileboxRecords.Content;
import com.stackcorp.astra.files.filebox.FileboxRecords.Item;
import com.stackcorp.astra.files.filebox.FileboxRecords.PathEntry;
import com.stackcorp.astra.files.filebox.FileboxRecords.Purgeable;
import com.stackcorp.astra.files.filebox.FileboxRecords.Quota;
import com.stackcorp.astra.files.filebox.FileboxRecords.TrashEntry;
import com.stackcorp.astra.files.filebox.ListCursor.Position;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.upload.FileNames;
import com.stackcorp.astra.files.upload.FileboxUploads;
import com.stackcorp.astra.files.upload.FileboxUploads.Reused;
import com.stackcorp.astra.files.upload.FileboxUploads.Source;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 개인 파일함(EP-0244~0261의 대체 계약, ADR-0028). 항목은 소유자 한 사람만 보고 바꾼다(SEC-08). 다른 사용자의 항목 ID나 폴더 ID가
 * 하나라도 섞이면 요청 전체를 404로 거부하고 아무것도 바꾸지 않는다. 하위 항목(폐포)은 서버가 계산한다(FILES-07).
 * <p>
 * 한 사용자의 변경 명령은 READ COMMITTED transaction 하나에서 filebox_quotas 행을 먼저 잠근 뒤 처리한다. 순환 이동, 깊이와 개수,
 * 용량 검사가 다른 명령과 엇갈리지 않는다. 교착이나 잠금 대기 초과로 실패한 transaction은 통째로 되돌린 뒤 정해진 횟수만큼 다시 한다.
 */
@Service
@EnableConfigurationProperties(FileboxProperties.class)
class FileboxService {

	private static final Logger log = LoggerFactory.getLogger(FileboxService.class);

	private static final int ATTEMPTS = 3;

	private final FileboxRecords records;

	private final FileboxUploads uploads;

	private final FileReferences references;

	private final ObjectStore store;

	private final FileAccess access;

	private final MemberCourses courses;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final FileboxProperties properties;

	FileboxService(FileboxRecords records, FileboxUploads uploads, FileReferences references, ObjectStore store,
			FileAccess access, MemberCourses courses, RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit,
			PlatformTransactionManager transactionManager, FileboxProperties properties) {
		this.records = records;
		this.uploads = uploads;
		this.references = references;
		this.store = store;
		this.access = access;
		this.courses = courses;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.properties = properties;
	}

	/**
	 * 폴더 하나의 활성 항목(EP-0257, EP-0250). 폴더가 먼저이고 그 안에서 sort 순서다. 검색어는 그 폴더 바로 아래 항목의 이름에서 찾고,
	 * 와일드카드 문자는 글자 그대로 찾는다.
	 */
	Listing list(long owner, Long parentId, String query, ItemSort sort, boolean ascending, String cursor, int size) {
		List<PathEntry> path = List.of();
		if (parentId != null) {
			activeFolder(owner, parentId);
			path = this.records.path(parentId);
		}
		String scope = "items:" + sort.key() + ":" + (ascending ? "asc" : "desc");
		Position after = cursor == null ? null : ListCursor.decode(scope, cursor);
		Object afterValue = after == null ? null : sort.parse(after.value());
		String like = query == null ? null : "%" + escapeLike(query) + "%";
		List<Item> rows = this.records.children(owner, parentId, like, sort, ascending, after, afterValue, size + 1);
		String next = null;
		if (rows.size() > size) {
			rows = rows.subList(0, size);
			Item last = rows.get(size - 1);
			next = ListCursor.encode(scope, new Position(last.folder() ? 0 : 1, sort.valueOf(last), last.id()));
		}
		return new Listing(path, rows.stream().map(FileboxService::view).toList(), next, usage(owner));
	}

	/** 휴지통에 넣은 root 항목(EP-0260). 넣은 일시 역순이다. */
	TrashPage trash(long owner, String cursor, int size) {
		LocalDateTime afterAt = null;
		Long afterId = null;
		if (cursor != null) {
			Position after = ListCursor.decode("trash", cursor);
			afterAt = (LocalDateTime) ItemSort.DATE.parse(after.value());
			afterId = after.id();
		}
		List<TrashEntry> rows = this.records.trash(owner, afterAt, afterId, size + 1);
		String next = null;
		if (rows.size() > size) {
			rows = rows.subList(0, size);
			TrashEntry last = rows.get(size - 1);
			next = ListCursor.encode("trash", new Position(0, last.trashedAt().toString(), last.id()));
		}
		return new TrashPage(rows.stream()
			.map((t) -> new TrashView(t.id(), t.kind(), t.name(), t.sizeBytes(), t.itemCount(),
					FileboxRecords.instant(t.trashedAt())))
			.toList(), next);
	}

	/** 폴더 만들기(EP-0248). 같은 폴더에 같은 이름의 활성 항목이 있으면 409다. */
	ItemView createFolder(Actor actor, Long parentId, String rawName, String idempotencyKey) {
		String name = ItemNames.typed(rawName);
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> this.idempotency.execute(owner, "filebox.folder.create", idempotencyKey,
				java.util.Arrays.asList(parentId, name), ItemView.class, () -> {
					Quota quota = this.records.lockQuota(owner);
					if (parentId != null) {
						activeFolder(owner, parentId);
						if (this.records.path(parentId).size() + 1 > this.properties.maxDepth()) {
							throw tooDeep();
						}
					}
					requireRoom(quota, 0, 1);
					long id;
					try {
						id = this.records.insertFolder(owner, parentId, name);
					}
					catch (DuplicateKeyException ex) {
						throw nameConflict();
					}
					this.records.addUsage(owner, 0, 1);
					this.audit.success(owner, actor.sessionId(), "filebox.folder.create", "filebox_item", id, null);
					return view(this.records.item(owner, id).orElseThrow());
				}));
	}

	/** 이름 바꾸기(EP-0252). 이름만 바꾸고 부모는 바꾸지 않는다. 파일은 확장자를 바꾸지 못한다. */
	ItemView rename(Actor actor, long id, int version, String rawName) {
		String name = ItemNames.typed(rawName);
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			Item item = this.records.item(owner, id).filter(Item::active).orElseThrow(ApiException::notFound);
			if (item.version() != version) {
				throw versionConflict();
			}
			if (!item.folder() && !Objects.equals(FileNames.extension(name).orElse(null), item.extension())) {
				throw ApiException.invalid("파일의 확장자는 바꿀 수 없습니다.");
			}
			try {
				if (this.records.rename(owner, id, version, name) == 0) {
					throw versionConflict();
				}
			}
			catch (DuplicateKeyException ex) {
				throw nameConflict();
			}
			this.audit.success(owner, actor.sessionId(), "filebox.item.rename", "filebox_item", id, null);
			return view(this.records.item(owner, id).orElseThrow());
		});
	}

	/**
	 * 이동(EP-0251). 모든 항목이 요청한 사람의 활성 항목이고 version이 맞아야 한다. 대상이 옮기는 폴더 자신이거나 그 아래면 거부한다.
	 * 하나라도 안 되면 아무것도 옮기지 않는다.
	 *
	 * @param targetFolderId 루트면 null
	 * @return 부모가 바뀐 항목 수
	 */
	int move(Actor actor, Long targetFolderId, Map<Long, Integer> versions) {
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			List<Item> items = owned(owner, versions.keySet());
			int depth = 0;
			if (targetFolderId != null) {
				activeFolder(owner, targetFolderId);
				List<PathEntry> path = this.records.path(targetFolderId);
				depth = path.size();
				if (path.stream().anyMatch((p) -> versions.containsKey(p.id()))) {
					throw new ApiException(HttpStatus.CONFLICT, "cycle", "폴더를 자기 자신이나 그 아래 폴더로 옮길 수 없습니다.");
				}
			}
			for (Item item : items) {
				requireActive(item);
				if (item.version() != versions.get(item.id())) {
					throw versionConflict();
				}
				if (item.folder() && depth + this.records.activeFolderHeight(item.id()) > this.properties.maxDepth()) {
					throw tooDeep();
				}
			}
			int moved = 0;
			for (Item item : items) {
				if (Objects.equals(item.parentId(), targetFolderId)) {
					continue;
				}
				try {
					if (this.records.move(owner, item.id(), item.version(), targetFolderId) == 0) {
						throw versionConflict();
					}
				}
				catch (DuplicateKeyException ex) {
					throw nameConflict();
				}
				this.audit.success(owner, actor.sessionId(), "filebox.item.move", "filebox_item", item.id(), null);
				moved++;
			}
			return moved;
		});
	}

	/**
	 * 휴지통에 넣기(EP-0245). 고른 항목과 그 아래 활성 항목을 함께 넣는다. 고른 항목 가운데 다른 고른 폴더 아래에 있는 것은 그 폴더의
	 * 묶음으로 들어간다. 파일과 내용 객체는 그대로 두므로 용량은 영구 삭제할 때 돌아온다.
	 *
	 * @return 휴지통에 들어간 항목 수(하위 항목 포함)
	 */
	int trash(Actor actor, List<Long> ids) {
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			List<Item> items = owned(owner, ids);
			items.forEach(FileboxService::requireActive);
			Set<Long> selected = new HashSet<>(ids);
			int count = 0;
			for (Item item : items) {
				if (item.parentId() != null
						&& this.records.path(item.parentId()).stream().anyMatch((p) -> selected.contains(p.id()))) {
					continue;
				}
				count += this.records.trash(owner, this.records.activeSubtree(item.id()), item.id());
				this.audit.success(owner, actor.sessionId(), "filebox.item.trash", "filebox_item", item.id(), null);
			}
			return count;
		});
	}

	/**
	 * 복원(EP-0259). 휴지통 root만 받는다. 원래 폴더가 활성이고 깊이 상한 안이면 그 폴더로, 아니면 루트로 되돌린다. 이름이 겹치면 접미사를
	 * 붙인다. 함께 휴지통에 들어간 하위 항목도 되돌린다.
	 *
	 * @return 되돌린 항목 수(하위 항목 포함)
	 */
	int restore(Actor actor, List<Long> ids) {
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			List<Item> roots = trashRoots(owner, ids);
			// 위쪽 root부터 되돌린다. 아래 root의 원래 폴더가 위쪽 root와 함께 되돌아올 수 있다.
			Map<Long, Integer> depth = new HashMap<>();
			for (Item root : roots) {
				depth.put(root.id(), root.parentId() == null ? 0 : this.records.path(root.parentId()).size());
			}
			roots = roots.stream().sorted(Comparator.comparing((Item r) -> depth.get(r.id()))).toList();
			int count = 0;
			for (Item root : roots) {
				Long parent = root.parentId();
				if (parent != null) {
					Optional<Item> folder = this.records.item(owner, parent).filter(Item::active);
					if (folder.isEmpty() || this.records.path(parent).size()
							+ this.records.trashedFolderHeight(root.id()) > this.properties.maxDepth()) {
						parent = null;
					}
				}
				placeRestored(owner, root, parent);
				count += 1 + this.records.restoreBundle(owner, root.id());
				this.audit.success(owner, actor.sessionId(), "filebox.item.restore", "filebox_item", root.id(), null);
			}
			return count;
		});
	}

	private void placeRestored(long owner, Item root, Long parent) {
		for (String candidate : ItemNames.candidates(root.name())) {
			try {
				if (this.records.restoreRoot(owner, root.id(), parent, candidate) == 1) {
					return;
				}
				throw new IllegalStateException("잠근 휴지통 항목을 되돌리지 못했습니다: " + root.id());
			}
			catch (DuplicateKeyException ex) {
				// 다음 이름으로 다시 한다.
			}
		}
		throw nameConflict();
	}

	/**
	 * 영구 삭제(EP-0246). 휴지통 root만 받고 그 아래 휴지통 항목을 모두 지운다. 파일의 참조를 한 번씩 놓아 마지막 참조였던 내용 객체는
	 * 저장소 GC가 지운다. 이 파일을 다시 써서 만든 과목 첨부는 따로 참조하므로 남는다(FILES-06).
	 *
	 * @return 지운 항목 수(하위 항목 포함)
	 */
	int purge(Actor actor, List<Long> ids) {
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			return purgeRoots(actor, trashRoots(owner, ids).stream().map(Item::id).toList());
		});
	}

	/** 휴지통 비우기. 그 순간 휴지통에 있는 root 전부를 영구 삭제한다. */
	int emptyTrash(Actor actor) {
		limit(actor);
		long owner = actor.userId();
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			return purgeRoots(actor, this.records.trashRoots(owner));
		});
	}

	private int purgeRoots(Actor actor, List<Long> roots) {
		long owner = actor.userId();
		Set<Long> closure = new LinkedHashSet<>();
		for (long root : roots) {
			closure.addAll(this.records.trashedSubtree(root));
		}
		if (closure.isEmpty()) {
			return 0;
		}
		List<Purgeable> rows = this.records.purgeable(owner, closure);
		int purged = this.records.purge(owner, closure);
		if (purged != rows.size()) {
			throw new IllegalStateException("잠근 휴지통 항목의 영구 삭제 수가 맞지 않습니다: " + purged + "/" + rows.size());
		}
		long bytes = 0;
		for (Purgeable row : rows) {
			if (row.fileId() == null) {
				continue;
			}
			if (this.references.release(row.fileId())) {
				this.records.deleteUploadOf(row.fileId());
			}
			bytes += row.sizeBytes();
		}
		this.records.removeUsage(owner, bytes, rows.size());
		for (long root : roots) {
			this.audit.success(owner, actor.sessionId(), "filebox.item.purge", "filebox_item", root, null);
		}
		return purged;
	}

	/**
	 * 파일함에 올리기(EP-0255의 대체). 업로드 정책 filebox로 검사하고, 업로드 기록과 같은 transaction에서 용량을 잡고 항목을 만든다.
	 * 같은 폴더에 같은 이름이 있으면 접미사를 붙인다. 검사기가 있으면 검사를 마칠 때까지 내려받지 못한다.
	 *
	 * @param parentId 루트면 null
	 */
	ItemView upload(Actor actor, Long parentId, MultipartFile file, String idempotencyKey) {
		long owner = actor.userId();
		if (idempotencyKey != null) {
			Optional<Long> replay = this.records.itemOfUploadKey(owner, idempotencyKey);
			if (replay.isPresent()) {
				return view(this.records.item(owner, replay.get()).orElseThrow());
			}
		}
		if (parentId != null) {
			activeFolder(owner, parentId);
		}
		// 저장하기 전에 한 번 본다. 정확한 검사는 기록할 때 잠근 뒤에 한다.
		requireRoom(this.records.quota(owner), file.getSize(), 1);
		long uploadId = this.uploads.receive(owner, file, idempotencyKey, new FileboxUploads.Target() {

			@Override
			public void reserve(long sizeBytes) {
				Quota quota = FileboxService.this.records.lockQuota(owner);
				if (parentId != null) {
					activeFolder(owner, parentId);
				}
				requireRoom(quota, sizeBytes, 1);
				FileboxService.this.records.addUsage(owner, sizeBytes, 1);
			}

			@Override
			public void place(long fileId, String name) {
				for (String candidate : ItemNames.candidates(name)) {
					try {
						long id = FileboxService.this.records.insertFile(owner, parentId, candidate, fileId);
						FileboxService.this.audit.success(owner, actor.sessionId(), "filebox.file.upload", "filebox_item",
								id, null);
						return;
					}
					catch (DuplicateKeyException ex) {
						// 다음 이름으로 다시 한다.
					}
				}
				throw nameConflict();
			}

		});
		long itemId = this.records.itemOfUpload(owner, uploadId)
			.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
					"같은 Idempotency-Key를 다른 요청에 썼습니다."));
		return view(this.records.item(owner, itemId).orElseThrow());
	}

	/** 내 파일 내려받기. 응답 header는 과목 파일 내려받기와 같다(FILES-09). */
	ResponseEntity<InputStreamResource> content(Actor actor, long id) {
		Content content = this.records.content(actor.userId(), id).orElseThrow(ApiException::notFound);
		if (!content.ready()) {
			throw new ApiException(HttpStatus.CONFLICT, "not_ready", "검사를 마치지 않았거나 거부된 파일입니다.");
		}
		InputStream in = this.store.open(Bucket.CONTENT, content.storageKey()).orElseThrow(ApiException::notFound);
		this.audit.success(actor.userId(), actor.sessionId(), "filebox.file.download", "filebox_item", id, null);
		return Downloads.attachment(content.name(), content.mimeType(), content.sizeBytes(), in);
	}

	/**
	 * 이 파일을 다시 써서 첨부한 곳(EP-0256, link_cnt). 요청한 사람이 지금 그 부모 객체를 볼 수 있는 것만 돌려준다. 과목을 잃었거나
	 * 부모가 지워졌으면 빠진다.
	 */
	List<ReferenceView> references(long owner, long id) {
		Item item = this.records.item(owner, id)
			.filter((i) -> !i.folder() && !"purged".equals(i.state()))
			.orElseThrow(ApiException::notFound);
		Map<Long, String> names = new HashMap<>();
		for (MemberCourse course : this.courses.of(owner)) {
			names.put(course.courseId(), course.name());
		}
		List<ReferenceView> views = new ArrayList<>();
		for (FileboxRecords.Reference ref : this.records.references(item.id())) {
			if (ref.courseId() != null && this.access.allowed(owner, ref.courseId(), ref.fileId(), FileAction.READ)) {
				views.add(new ReferenceView(ref.courseId(), names.get(ref.courseId()), ref.purpose(), ref.attachedAt()));
			}
		}
		return views;
	}

	/**
	 * 파일함 파일을 과목 업로드로 다시 쓴다(EP-0126, POST /api/uploads:fromFilebox). 항목은 요청한 사람의 활성 파일이어야 하고,
	 * 대상 용도·과목의 업로드 권한, 확장자와 크기 정책, 검사 상태를 다시 본다. 만든 업로드는 부모 저장에서 첨부한다.
	 */
	ReuseReceipt reuse(Actor actor, long courseId, String purpose, List<Long> itemIds, String idempotencyKey) {
		long owner = actor.userId();
		// 권한을 먼저 본다. 권한을 잃은 사람은 저장한 receipt도 받지 못한다.
		if (owned(owner, itemIds).size() != itemIds.size()) {
			throw ApiException.notFound();
		}
		this.uploads.checkTarget(owner, courseId, purpose);
		return inTransaction(() -> this.idempotency.execute(owner, "upload.from_filebox", idempotencyKey,
				List.of(courseId, purpose, itemIds), ReuseReceipt.class, () -> {
					List<Item> items = this.records.itemsForShare(owner, itemIds);
					if (items.size() != itemIds.size()) {
						throw ApiException.notFound();
					}
					Map<Long, Item> byId = new HashMap<>();
					for (Item item : items) {
						requireActive(item);
						if (item.folder()) {
							throw new ApiException(HttpStatus.CONFLICT, "folder_not_allowed", "폴더는 쓸 수 없습니다.");
						}
						byId.put(item.id(), item);
					}
					List<Source> sources = itemIds.stream()
						.map(byId::get)
						.map((i) -> new Source(i.id(), i.fileId(), i.name()))
						.toList();
					List<Reused> reused = this.uploads.reuse(owner, courseId, purpose, sources);
					for (Reused r : reused) {
						this.audit.success(owner, actor.sessionId(), "upload.from_filebox", "upload", r.uploadId(),
								courseId);
					}
					return new ReuseReceipt(reused);
				}));
	}

	Usage usage(long owner) {
		Quota quota = this.records.quota(owner);
		return new Usage(quota.usedBytes(), quotaBytes(quota), quota.itemCount(), this.properties.maxItems());
	}

	private long quotaBytes(Quota quota) {
		return quota.quotaBytes() != null ? quota.quotaBytes() : this.properties.quota().toBytes();
	}

	private void requireRoom(Quota quota, long bytes, int items) {
		if (quota.itemCount() + items > this.properties.maxItems()) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_items", "파일함 항목 수 한도를 넘습니다.");
		}
		if (quota.usedBytes() + bytes > quotaBytes(quota)) {
			throw new ApiException(HttpStatus.CONFLICT, "quota_exceeded", "파일함 용량이 부족합니다.");
		}
	}

	private Item activeFolder(long owner, long folderId) {
		return this.records.item(owner, folderId)
			.filter((i) -> i.folder() && i.active())
			.orElseThrow(ApiException::notFound);
	}

	/** 요청한 사람의 항목. 하나라도 남의 것이거나 없으면 404다(SEC-08). */
	private List<Item> owned(long owner, java.util.Collection<Long> ids) {
		List<Item> items = this.records.items(owner, ids);
		if (items.size() != ids.size()) {
			throw ApiException.notFound();
		}
		return items;
	}

	private List<Item> trashRoots(long owner, List<Long> ids) {
		List<Item> items = owned(owner, ids);
		for (Item item : items) {
			if (!item.trashRoot()) {
				throw new ApiException(HttpStatus.CONFLICT, "not_in_trash", "휴지통에 넣은 항목만 고를 수 있습니다.");
			}
		}
		return items;
	}

	private static void requireActive(Item item) {
		if (!item.active()) {
			throw new ApiException(HttpStatus.CONFLICT, "not_active", "휴지통에 있는 항목입니다.");
		}
	}

	private void limit(Actor actor) {
		this.rateLimiter.check("filebox-write", actor.userId(), this.properties.rateLimit(),
				this.properties.rateWindow());
	}

	// 교착, 잠금 대기 초과처럼 다시 하면 될 수 있는 실패만 다시 한다. transaction은 실패할 때마다 통째로 되돌아간다.
	private <T> T inTransaction(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((tx) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
				log.info("filebox transaction retried attempt={} error={}", attempt, ex.getClass().getSimpleName());
			}
		}
	}

	static String escapeLike(String text) {
		return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	static ItemView view(Item item) {
		return new ItemView(item.id(), item.kind(), item.name(), item.parentId(), item.sizeBytes(),
				item.extension(), item.folder() ? null : fileState(item.scanState()),
				FileboxRecords.instant(item.createdAt()), item.version());
	}

	private static String fileState(String scanState) {
		return switch (scanState == null ? "" : scanState) {
			case "clean" -> "ready";
			case "pending" -> "scanning";
			default -> "rejected";
		};
	}

	private static ApiException versionConflict() {
		return new ApiException(HttpStatus.CONFLICT, "version_conflict", "다른 곳에서 먼저 바뀌었습니다. 다시 불러와 주세요.");
	}

	private static ApiException nameConflict() {
		return new ApiException(HttpStatus.CONFLICT, "name_conflict", "같은 이름의 항목이 이미 있습니다.");
	}

	private ApiException tooDeep() {
		return new ApiException(HttpStatus.CONFLICT, "too_deep",
				"폴더는 " + this.properties.maxDepth() + "단계까지 만들 수 있습니다.");
	}

	/**
	 * @param state 파일만. ready(내려받기 가능), scanning(검사 중), rejected(거부)
	 * @param version 이름 바꾸기의 If-Match와 이동의 version으로 보내는 값
	 */
	record ItemView(long id, String kind, String name, Long parentId, Long sizeBytes, String extension, String state,
			Instant createdAt, int version) {
	}

	record Listing(List<PathEntry> path, List<ItemView> items, String nextCursor, Usage usage) {
	}

	record Usage(long usedBytes, long quotaBytes, int itemCount, int maxItems) {
	}

	/** @param itemCount 함께 휴지통에 들어간 항목 수(자신 포함) */
	record TrashView(long id, String kind, String name, long sizeBytes, int itemCount, Instant trashedAt) {
	}

	record TrashPage(List<TrashView> items, String nextCursor) {
	}

	/** @param purpose 첨부한 곳의 업로드 용도(lecture_material, assignment_submission 등) */
	record ReferenceView(long courseId, String courseName, String purpose, Instant attachedAt) {
	}

	record ReuseReceipt(List<Reused> uploads) {
	}

}
