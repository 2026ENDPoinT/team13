package com.stackcorp.astra.files.studyroom;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.files.Downloads;
import com.stackcorp.astra.files.FileReferences;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.files.api.StudyRoomDocumentSummaries;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Job;
import com.stackcorp.astra.files.studyroom.StudyRoomRecords.Document;
import com.stackcorp.astra.files.studyroom.StudyRoomRecords.Folder;
import com.stackcorp.astra.files.studyroom.StudyRoomRecords.Placement;
import com.stackcorp.astra.files.studyroom.StudyRoomRecords.Quota;
import com.stackcorp.astra.files.studyroom.StudyRoomRecords.Recording;
import com.stackcorp.astra.files.upload.FileNames;
import com.stackcorp.astra.files.upload.StudyRoomUploads;
import com.stackcorp.astra.files.upload.StudyRoomUploads.Kind;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * 학습실의 내 폴더, 항목 배치, 강의 녹음, 내 문서(ADR-0040). 모두 소유자 한 사람만 보고 바꾼다. 다른 사용자의 폴더, 녹음, 문서, 배치는
 * 없는 ID와 같은 404이고, 하나라도 섞인 요청은 아무것도 바꾸지 않는다. 담당자, 운영자, 학습 대화가 읽는 길은 없다. 녹음의 전사·요약
 * (ADR-0041)과 문서의 요약(summary 모듈, ADR-0040 14번)은 소유자가 요청하고 소유자만 읽는다.
 * <p>
 * 한 사용자의 변경 명령은 READ COMMITTED transaction 하나에서 study_room_quotas 행을 먼저 잠근 뒤 처리한다. 순서 바꾸기, 배치,
 * 용량 검사가 다른 명령과 엇갈리지 않는다. 교착이나 잠금 대기 초과로 실패한 transaction은 통째로 되돌린 뒤 정해진 횟수만큼 다시 한다.
 * <p>
 * 만들기(폴더, 녹음, 문서, 강의자료 배치)는 지금 그 과목의 구성원이어야 하고, 강의자료 배치는 그 과목에서 그 파일을 읽을 수 있어야
 * 한다. 읽기, 이름 바꾸기, 지우기는 소유만 본다. 과목을 잃어도 내 녹음, 문서, 폴더는 남고 내가 지울 수 있다.
 */
@Service
@EnableConfigurationProperties(StudyRoomProperties.class)
class StudyRoomService {

	static final int MAX_FOLDER_NAME = 60;

	static final int MAX_TITLE = 200;

	static final int MAX_DOCUMENT_TITLE = 300;

	/** 검사 중인 문서 내용 요청의 409에 붙이는 {@code Retry-After}. 검사 결과 poll 간격(10초)과 같다. */
	static final Duration SCAN_RETRY_AFTER = Duration.ofSeconds(10);

	private static final String PDF = "application/pdf";

	private static final Logger log = LoggerFactory.getLogger(StudyRoomService.class);

	private static final int ATTEMPTS = 3;

	/** 같은 session의 같은 녹음 재생을 감사 기록에 한 번만 남기는 구간. */
	static final Duration PLAY_AUDIT_WINDOW = Duration.ofMinutes(10);

	/** 녹음 정책의 확장자마다 재생 MIME. 정책 행(upload_policies)에 있어도 여기 없는 확장자는 받지 않는다. */
	static final Map<String, String> AUDIO_TYPES = Map.of("webm", "audio/webm", "weba", "audio/webm", "ogg",
			"audio/ogg", "opus", "audio/ogg", "mp3", "audio/mpeg", "m4a", "audio/mp4", "mp4", "audio/mp4", "wav",
			"audio/wav", "aac", "audio/aac", "flac", "audio/flac");

	private final StudyRoomRecords records;

	private final StudyRoomUploads uploads;

	private final FileReferences references;

	private final ObjectStore store;

	private final FileAccess access;

	private final CourseMemberships memberships;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final StudyRoomProperties properties;

	private final RecordingStudy recordingStudy;

	private final ObjectProvider<StudyRoomDocumentSummaries> summaries;

	StudyRoomService(StudyRoomRecords records, StudyRoomUploads uploads, FileReferences references,
			ObjectStore store, FileAccess access, CourseMemberships memberships, RateLimiter rateLimiter,
			Idempotency idempotency, AuditLog audit, PlatformTransactionManager transactionManager,
			StudyRoomProperties properties, RecordingStudy recordingStudy,
			ObjectProvider<StudyRoomDocumentSummaries> summaries) {
		this.records = records;
		this.uploads = uploads;
		this.references = references;
		this.store = store;
		this.access = access;
		this.memberships = memberships;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.properties = properties;
		this.recordingStudy = recordingStudy;
		this.summaries = summaries;
	}

	// ---------- 읽기 ----------

	/**
	 * 과목 하나의 내 학습실. 구성원이 아니어도 내 행은 보인다. 강의자료 배치는 지금 그 과목에서 그 파일을 읽을 수 있을 때만 보인다(행은 남아
	 * 권한이 돌아오면 다시 보인다).
	 */
	CourseView course(long owner, long courseId) {
		List<PlacementView> placements = new ArrayList<>();
		for (Placement placement : this.records.placements(owner, courseId)) {
			if (placement.fileId() == null
					|| this.access.allowed(owner, courseId, placement.fileId(), FileAction.READ)) {
				placements.add(PlacementView.of(placement));
			}
		}
		return new CourseView(courseId,
				this.records.folders(owner, courseId).stream().map(FolderView::of).toList(),
				this.records.recordings(owner, courseId).stream().map(RecordingView::of).toList(),
				documentViews(this.records.documents(owner, courseId)), placements,
				usage(owner));
	}

	RecordingList recordings(long owner) {
		return new RecordingList(this.records.recordings(owner, null).stream().map(RecordingView::of).toList(),
				usage(owner));
	}

	RecordingView recording(long owner, long id) {
		Recording recording = this.records.recording(owner, id).orElseThrow(ApiException::notFound);
		return RecordingView.of(recording, this.recordingStudy.of(owner, id));
	}

	// ---------- 폴더 ----------

	/**
	 * 폴더를 과목 학습실 끝에 만든다. 같은 과목에 글자 그대로 같은 이름이 있으면 409다.
	 * <p>
	 * 멱등 원장의 receipt에는 폴더 ID만 둔다. 이름을 24시간 원장에 남기지 않기 위해서다. 같은 key의 재전송은 그 폴더의 지금 상태(그 사이 바뀐
	 * 이름과 version)를 돌려주고, 그 사이 지운 폴더면 410 {@code folder_deleted}다.
	 */
	FolderView createFolder(Actor actor, long courseId, String rawName, String idempotencyKey) {
		String name = folderName(rawName);
		long owner = actor.userId();
		limit(actor);
		requireMember(owner, courseId);
		return inTransaction(() -> {
			CreatedFolder created = this.idempotency.execute(owner, "study_room.folder.create", idempotencyKey,
					Arrays.asList(courseId, name), CreatedFolder.class, () -> {
						this.records.lockQuota(owner);
						List<Folder> folders = this.records.folders(owner, courseId);
						if (folders.size() >= this.properties.maxFoldersPerCourse()) {
							throw new ApiException(HttpStatus.CONFLICT, "too_many_folders",
									"한 과목에 폴더는 " + this.properties.maxFoldersPerCourse() + "개까지 만들 수 있습니다.");
						}
						int position = folders.stream().mapToInt(Folder::position).max().orElse(0) + 1;
						long id;
						try {
							id = this.records.insertFolder(owner, courseId, name, position);
						}
						catch (DuplicateKeyException ex) {
							throw nameConflict();
						}
						this.audit.success(owner, actor.sessionId(), "study_room.folder.create", "study_room_folder", id,
								courseId);
						return new CreatedFolder(id);
					});
			return FolderView.of(this.records.folder(owner, created.id())
				.orElseThrow(() -> new ApiException(HttpStatus.GONE, "folder_deleted",
						"이 요청으로 만든 폴더는 그 뒤 지워졌습니다.")));
		});
	}

	FolderView renameFolder(Actor actor, long id, int version, String rawName) {
		String name = folderName(rawName);
		long owner = actor.userId();
		limit(actor);
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			Folder folder = this.records.folder(owner, id).orElseThrow(ApiException::notFound);
			if (folder.version() != version) {
				throw versionConflict();
			}
			try {
				if (this.records.renameFolder(owner, id, version, name) == 0) {
					throw versionConflict();
				}
			}
			catch (DuplicateKeyException ex) {
				throw nameConflict();
			}
			this.audit.success(owner, actor.sessionId(), "study_room.folder.rename", "study_room_folder", id,
					folder.courseId());
			return FolderView.of(this.records.folder(owner, id).orElseThrow());
		});
	}

	/** 폴더를 지운다. 그 안에 둔 항목은 과목 학습실 맨 위로 돌아가고, 녹음은 지우지 않는다. */
	void deleteFolder(Actor actor, long id, int version) {
		long owner = actor.userId();
		limit(actor);
		inTransaction(() -> {
			this.records.lockQuota(owner);
			Folder folder = this.records.folder(owner, id).orElseThrow(ApiException::notFound);
			if (folder.version() != version || this.records.deleteFolder(owner, id, version) == 0) {
				throw versionConflict();
			}
			this.audit.success(owner, actor.sessionId(), "study_room.folder.delete", "study_room_folder", id,
					folder.courseId());
			return null;
		});
	}

	/**
	 * 과목 하나의 폴더 순서를 바꾼다. 요청은 그 과목의 내 폴더 전부를 새 순서로, 마지막으로 읽은 version과 함께 담는다. 남의 폴더나 없는
	 * ID가 있으면 404, 그 사이 폴더가 생기거나 지워져 목록이 지금 폴더와 다르면 409 {@code folder_set_changed}, version이 하나라도
	 * 다르면 409 {@code version_conflict}이고 아무것도 바꾸지 않는다.
	 *
	 * @param order 폴더 ID와 version, 새 순서대로
	 */
	List<FolderView> reorder(Actor actor, long courseId, LinkedHashMap<Long, Integer> order) {
		long owner = actor.userId();
		limit(actor);
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			Map<Long, Folder> current = new HashMap<>();
			for (Folder folder : this.records.folders(owner, courseId)) {
				current.put(folder.id(), folder);
			}
			for (long id : order.keySet()) {
				if (!current.containsKey(id)) {
					if (this.records.folder(owner, id).isEmpty()) {
						throw ApiException.notFound();
					}
					throw folderSetChanged();
				}
			}
			if (current.size() != order.size()) {
				throw folderSetChanged();
			}
			for (Map.Entry<Long, Integer> entry : order.entrySet()) {
				if (current.get(entry.getKey()).version() != entry.getValue()) {
					throw versionConflict();
				}
			}
			int position = 0;
			for (Map.Entry<Long, Integer> entry : order.entrySet()) {
				position++;
				Folder folder = current.get(entry.getKey());
				if (folder.position() != position
						&& this.records.positionFolder(owner, folder.id(), folder.version(), position) == 0) {
					throw versionConflict();
				}
			}
			this.audit.success(owner, actor.sessionId(), "study_room.folder.reorder", "course", courseId, courseId);
			return this.records.folders(owner, courseId).stream().map(FolderView::of).toList();
		});
	}

	// ---------- 배치 ----------

	/**
	 * 항목을 폴더에 둔다. 배치가 없는 항목은 expectedVersion 0, 있으면 그 배치의 version을 보낸다. 이미 그 폴더에 있으면 바꾸지 않고
	 * 돌려준다.
	 */
	PlacementView place(Actor actor, ItemKind kind, long itemId, int expectedVersion, long folderId) {
		long owner = actor.userId();
		limit(actor);
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			Folder folder = this.records.folder(owner, folderId).orElseThrow(ApiException::notFound);
			switch (kind) {
				case RECORDING -> {
					Recording recording = this.records.recording(owner, itemId).orElseThrow(ApiException::notFound);
					if (recording.courseId() != folder.courseId()) {
						throw new ApiException(HttpStatus.CONFLICT, "course_mismatch", "녹음과 폴더의 과목이 다릅니다.");
					}
				}
				case DOCUMENT -> {
					Document document = this.records.document(owner, itemId).orElseThrow(ApiException::notFound);
					if (document.courseId() != folder.courseId()) {
						throw new ApiException(HttpStatus.CONFLICT, "course_mismatch", "문서와 폴더의 과목이 다릅니다.");
					}
				}
				case COURSE_FILE -> {
					// 강의자료는 폴더의 과목에서 지금 읽을 수 있어야 한다. 남의 과목, 지운 자료, 공개 전 자료는 없는 파일과 같은 404다.
					if (!this.access.allowed(owner, folder.courseId(), itemId, FileAction.READ)) {
						throw ApiException.notFound();
					}
				}
			}
			Optional<Placement> existing = placement(owner, kind, itemId);
			int current = existing.map(Placement::version).orElse(0);
			if (current != expectedVersion) {
				throw versionConflict();
			}
			if (existing.isPresent() && existing.get().folderId() == folderId) {
				return PlacementView.of(existing.get());
			}
			long id;
			if (existing.isPresent()) {
				id = existing.get().id();
				if (this.records.movePlacement(owner, id, expectedVersion, folderId) == 0) {
					throw versionConflict();
				}
			}
			else {
				try {
					id = this.records.insertPlacement(owner, folderId, kind == ItemKind.COURSE_FILE ? itemId : null,
							kind == ItemKind.RECORDING ? itemId : null, kind == ItemKind.DOCUMENT ? itemId : null);
				}
				catch (DuplicateKeyException ex) {
					throw versionConflict();
				}
			}
			this.audit.success(owner, actor.sessionId(), "study_room.placement.set", "study_room_placement", id,
					folder.courseId());
			return PlacementView.of(placement(owner, kind, itemId).orElseThrow());
		});
	}

	/** 배치를 지워 항목을 과목 학습실 맨 위로 돌려보낸다. 배치가 없으면 404다. */
	void unplace(Actor actor, ItemKind kind, long itemId, int version) {
		long owner = actor.userId();
		limit(actor);
		inTransaction(() -> {
			this.records.lockQuota(owner);
			Placement placement = placement(owner, kind, itemId).orElseThrow(ApiException::notFound);
			if (placement.version() != version || this.records.deletePlacement(owner, placement.id(), version) == 0) {
				throw versionConflict();
			}
			this.audit.success(owner, actor.sessionId(), "study_room.placement.clear", "study_room_placement",
					placement.id(), placement.courseId());
			return null;
		});
	}

	private Optional<Placement> placement(long owner, ItemKind kind, long itemId) {
		return switch (kind) {
			case RECORDING -> this.records.placementOfRecording(owner, itemId);
			case DOCUMENT -> this.records.placementOfDocument(owner, itemId);
			case COURSE_FILE -> this.records.placementOfFile(owner, itemId);
		};
	}

	// ---------- 녹음 ----------

	/**
	 * 녹음 파일을 올려 녹음을 만든다. 업로드 정책 study_recording으로 검사하고, 업로드 기록과 같은 transaction에서 용량을 잡고 녹음 행과
	 * (폴더가 있으면) 배치를 만든다. 검사기가 있으면 검사를 마칠 때까지 재생하지 못한다.
	 * <p>
	 * 같은 Idempotency-Key로 같은 파일과 값을 다시 보내면 처음 만든 녹음의 지금 상태를 돌려준다. 하나라도 다르면 409
	 * {@code idempotency_conflict}이고 아무것도 만들지 않는다. 처음 만든 녹음을 그 사이 지웠으면 410 {@code recording_deleted}다. 이미
	 * 쓴 key의 재전송은 한도, 폴더 검사를 다시 하지 않는다. 동시에 온 같은 key의 두 요청은 사용자 행을 잠근 뒤 먼저 끝난 쪽을 보고 검사를
	 * 건너뛰므로, 한도에 닿아 있어도 같은 녹음을 받는다.
	 */
	RecordingView createRecording(Actor actor, long courseId, MultipartFile file, NewRecording meta,
			String idempotencyKey) {
		long owner = actor.userId();
		limit(actor);
		requireMember(owner, courseId);
		// 정책에 있어도 재생 형식을 정하지 못하는 확장자는 저장하기 전에 거부한다. 응답에 파일 이름을 넣지 않는다.
		FileNames.sanitize(file.getOriginalFilename()).flatMap(FileNames::extension).ifPresent((extension) -> {
			if (!AUDIO_TYPES.containsKey(extension)) {
				throw extensionNotAllowed();
			}
		});
		if (!keyUsed(owner, idempotencyKey)) {
			if (meta.folderId() != null) {
				folderIn(owner, meta.folderId(), courseId);
			}
			// 저장하기 전에 한 번 본다. 정확한 검사는 기록할 때 잠근 뒤에 한다.
			requireRoom(Kind.RECORDING, this.records.quota(owner), file.getSize());
		}
		long uploadId = this.uploads.receive(Kind.RECORDING, owner, file, idempotencyKey,
				new RecordingRequest(courseId, meta), new StudyRoomUploads.Target() {

					@Override
					public void reserve(long sizeBytes) {
						Quota quota = StudyRoomService.this.records.lockQuota(owner);
						if (keyUsed(owner, idempotencyKey)) {
							// 같은 key의 요청이 먼저 끝났다. 이 transaction은 업로드 key 중복으로 되돌아가고 먼저 만든 녹음을 돌려준다.
							return;
						}
						if (meta.folderId() != null) {
							folderIn(owner, meta.folderId(), courseId);
						}
						requireRoom(Kind.RECORDING, quota, sizeBytes);
						StudyRoomService.this.records.addUsage(owner, sizeBytes, 1, 0);
					}

					@Override
					public void place(long fileId, String name) {
						String mimeType = FileNames.extension(name)
							.map(AUDIO_TYPES::get)
							.orElseThrow(StudyRoomService::extensionNotAllowed);
						long id = StudyRoomService.this.records.insertRecording(owner, courseId, fileId, meta.title(),
								meta.source(), mimeType, meta.durationMs(), meta.recordedAt());
						// 같은 transaction에서 작업을 만든다. 첫 응답부터 queued다(ADR-0041).
						StudyRoomService.this.recordingStudy.created(owner, id);
						if (meta.folderId() != null) {
							StudyRoomService.this.records.insertPlacement(owner, meta.folderId(), null, id, null);
						}
						StudyRoomService.this.audit.success(owner, actor.sessionId(), "study_room.recording.create",
								"study_room_recording", id, courseId);
					}

				});
		// 지문이 같은 업로드가 있는데 녹음이 없으면, 그 업로드로 만든 녹음을 그 사이 지운 것이다.
		long id = this.records.recordingOfUpload(owner, uploadId)
			.orElseThrow(() -> new ApiException(HttpStatus.GONE, "recording_deleted",
					"이 요청으로 만든 녹음은 그 뒤 지워졌습니다. 다시 올리려면 새 Idempotency-Key를 쓰세요."));
		return recording(owner, id);
	}

	private boolean keyUsed(long owner, String idempotencyKey) {
		return idempotencyKey != null && this.records.uploadKeyUsed(owner, idempotencyKey);
	}

	RecordingView renameRecording(Actor actor, long id, int version, String rawTitle) {
		String title = title(rawTitle);
		long owner = actor.userId();
		limit(actor);
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			Recording recording = this.records.recording(owner, id).orElseThrow(ApiException::notFound);
			if (recording.version() != version || this.records.renameRecording(owner, id, version, title) == 0) {
				throw versionConflict();
			}
			this.audit.success(owner, actor.sessionId(), "study_room.recording.rename", "study_room_recording", id,
					recording.courseId());
			return recording(owner, id);
		});
	}

	/**
	 * 녹음과 그 배치를 지우고 파일 참조를 놓는다. 마지막 참조였으면 저장소 GC가 원본을 지운다(ADR-0011). 녹음 바이트는 사용량에서 바로
	 * 빠진다.
	 */
	void deleteRecording(Actor actor, long id, int version) {
		long owner = actor.userId();
		limit(actor);
		inTransaction(() -> {
			this.records.lockQuota(owner);
			Recording recording = this.records.recording(owner, id).orElseThrow(ApiException::notFound);
			if (recording.version() != version) {
				throw versionConflict();
			}
			// 작업과 결과를 녹음 행보다 먼저 지운다. 처리 중인 worker의 기록은 행이 없어 버려진다(ADR-0041).
			this.recordingStudy.deleted(owner, id);
			if (this.records.deleteRecording(owner, id, version) == 0) {
				throw versionConflict();
			}
			if (this.references.release(recording.fileId())) {
				this.uploads.deleteUploadOf(recording.fileId());
			}
			// 지운 파일 행에 가져온 파일 이름(곧 제목)이 남지 않게 한다.
			this.uploads.forgetName(Kind.RECORDING, recording.fileId());
			this.records.removeUsage(owner, recording.sizeBytes(), 1, 0);
			this.audit.success(owner, actor.sessionId(), "study_room.recording.delete", "study_room_recording", id,
					recording.courseId());
			return null;
		});
	}

	/**
	 * 녹음 재생과 내려받기. 구간 요청(Range) 하나를 받아 206으로 그 구간만 보낸다. 검사를 마치지 않았으면 409 {@code not_ready},
	 * 거부했으면 409 {@code content_rejected}다. 저장소 객체는 언제나 보낼 구간만 읽고, client가 끝까지 읽지 않고 끊으면 남은 내용을
	 * 받지 않고 연결을 끊는다.
	 * <p>
	 * 감사 기록은 구간과 관계없이 같은 로그인 session의 같은 녹음 재생을 {@link #PLAY_AUDIT_WINDOW} 안에 한 번 남긴다. 탐색마다 남기지
	 * 않으면서, 처음이 아닌 구간({@code bytes=1-})만 읽어 기록을 피할 수 없다. 기록은 저장소를 열기 전에 남긴다. 연 stream은 응답
	 * 본문으로 넘길 때까지 다른 일을 하지 않아, 그 사이 실패해 닫히지 않은 채 남지 않는다. 이미 남겼는지 찾지 못하면 재생을 막지 않고
	 * 기록을 다시 남긴다({@link AuditLog#successOnceWithin}).
	 */
	ResponseEntity<InputStreamResource> content(Actor actor, long id, String rangeHeader) {
		Recording recording = playable(actor, id);
		long size = recording.sizeBytes();
		Optional<ByteRange> range = ByteRange.parse(rangeHeader, size);
		long first = range.map(ByteRange::first).orElse(0L);
		long last = range.map(ByteRange::last).orElse(size - 1);
		String name = downloadName(recording);
		this.audit.successOnceWithin(actor.userId(), actor.sessionId(), "study_room.recording.play",
				"study_room_recording", id, recording.courseId(), PLAY_AUDIT_WINDOW);
		InputStream content = this.store.openRange(Bucket.CONTENT, recording.storageKey(), first, last)
			.orElseThrow(ApiException::notFound);
		return Downloads.ranged(name, recording.mimeType(), size, first, last, range.isPresent(), content);
	}

	/** HEAD: 녹음 행만 읽어 GET과 같은 header를 돌려준다. 저장소를 열지 않고 감사 기록도 남기지 않는다. */
	ResponseEntity<Void> contentHead(Actor actor, long id) {
		Recording recording = playable(actor, id);
		return Downloads.rangedHead(downloadName(recording), recording.mimeType(), recording.sizeBytes());
	}

	private Recording playable(Actor actor, long id) {
		this.rateLimiter.check("study-room-play", actor.userId(), this.properties.playRateLimit(),
				this.properties.rateWindow());
		Recording recording = this.records.recording(actor.userId(), id).orElseThrow(ApiException::notFound);
		if (Recording.SCANNING.equals(recording.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "not_ready", "악성코드 검사를 마치지 않은 녹음입니다.");
		}
		if (Recording.REJECTED.equals(recording.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "content_rejected", "검사에서 거부된 녹음입니다. 지워 주세요.");
		}
		return recording;
	}

	// ---------- 문서 ----------

	DocumentView document(long owner, long id) {
		return documentView(this.records.document(owner, id).orElseThrow(ApiException::notFound));
	}

	private DocumentView documentView(Document document) {
		return documentViews(List.of(document)).getFirst();
	}

	// 요약 상태는 summary 모듈이 안다(ADR-0040 14번). 요약 기능이 없는 배포에서는 null이다.
	private List<DocumentView> documentViews(List<Document> documents) {
		StudyRoomDocumentSummaries summaries = this.summaries.getIfAvailable();
		Map<Long, String> statuses = summaries == null || documents.isEmpty() ? Map.of()
				: summaries.statuses(documents.stream().map(Document::fileId).toList());
		return documents.stream().map((d) -> DocumentView.of(d, statuses.get(d.fileId()))).toList();
	}

	/**
	 * 내 PDF를 올려 문서를 만든다. 업로드 정책 study_document로 검사하고, 녹음과 같이 업로드 기록과 같은 transaction에서 용량을 잡고
	 * 문서 행과 (폴더가 있으면) 배치를 만든다. 과목에 게시하지 않고, 전사·요약·학습 대화·외부 모델 어디에도 보내지 않는다. 검사기가 있으면
	 * 검사를 마칠 때까지 내용을 내보내지 않는다.
	 * <p>
	 * 멱등 key의 규칙은 녹음과 같다. 같은 key의 재전송은 처음 만든 문서의 지금 상태를 돌려주고 한도와 폴더 검사를 다시 하지 않는다. 값이
	 * 하나라도 다르면 409 {@code idempotency_conflict}, 처음 만든 문서를 그 사이 지웠으면 410 {@code document_deleted}다.
	 *
	 * @param rawTitle 없으면 파일 이름에서 {@code .pdf}를 뺀 값
	 */
	DocumentView createDocument(Actor actor, long courseId, MultipartFile file, String rawTitle, Long folderId,
			String idempotencyKey) {
		long owner = actor.userId();
		limit(actor);
		requireMember(owner, courseId);
		String title = rawTitle == null || rawTitle.isBlank() ? defaultDocumentTitle(file.getOriginalFilename())
				: documentTitle(rawTitle);
		if (!keyUsed(owner, idempotencyKey)) {
			if (folderId != null) {
				folderIn(owner, folderId, courseId);
			}
			// 저장하기 전에 한 번 본다. 정확한 검사는 기록할 때 잠근 뒤에 한다.
			requireRoom(Kind.DOCUMENT, this.records.quota(owner), file.getSize());
		}
		long uploadId = this.uploads.receive(Kind.DOCUMENT, owner, file, idempotencyKey,
				new DocumentRequest(courseId, title, folderId), new StudyRoomUploads.Target() {

					@Override
					public void reserve(long sizeBytes) {
						Quota quota = StudyRoomService.this.records.lockQuota(owner);
						if (keyUsed(owner, idempotencyKey)) {
							// 같은 key의 요청이 먼저 끝났다. 이 transaction은 업로드 key 중복으로 되돌아가고 먼저 만든 문서를 돌려준다.
							return;
						}
						if (folderId != null) {
							folderIn(owner, folderId, courseId);
						}
						requireRoom(Kind.DOCUMENT, quota, sizeBytes);
						StudyRoomService.this.records.addUsage(owner, sizeBytes, 0, 1);
					}

					@Override
					public void place(long fileId, String name) {
						long id = StudyRoomService.this.records.insertDocument(owner, courseId, fileId, title);
						if (folderId != null) {
							StudyRoomService.this.records.insertPlacement(owner, folderId, null, null, id);
						}
						StudyRoomService.this.audit.success(owner, actor.sessionId(), "study_room.document.create",
								"study_room_document", id, courseId);
					}

				});
		// 지문이 같은 업로드가 있는데 문서가 없으면, 그 업로드로 만든 문서를 그 사이 지운 것이다.
		long id = this.records.documentOfUpload(owner, uploadId)
			.orElseThrow(() -> new ApiException(HttpStatus.GONE, "document_deleted",
					"이 요청으로 만든 문서는 그 뒤 지워졌습니다. 다시 올리려면 새 Idempotency-Key를 쓰세요."));
		return document(owner, id);
	}

	/** @param rawTitle 새 제목. null이면 version만 확인하고 바꾸지 않는다 */
	DocumentView updateDocument(Actor actor, long id, int version, String rawTitle) {
		String title = rawTitle == null ? null : documentTitle(rawTitle);
		long owner = actor.userId();
		limit(actor);
		return inTransaction(() -> {
			this.records.lockQuota(owner);
			Document document = this.records.document(owner, id).orElseThrow(ApiException::notFound);
			if (document.version() != version) {
				throw versionConflict();
			}
			if (title == null) {
				return documentView(document);
			}
			if (this.records.renameDocument(owner, id, version, title) == 0) {
				throw versionConflict();
			}
			this.audit.success(owner, actor.sessionId(), "study_room.document.rename", "study_room_document", id,
					document.courseId());
			return document(owner, id);
		});
	}

	/** 문서와 그 배치를 지우고 파일 참조를 놓는다. 녹음 지우기와 같다. 문서 바이트는 사용량에서 바로 빠진다. */
	void deleteDocument(Actor actor, long id, int version) {
		long owner = actor.userId();
		limit(actor);
		inTransaction(() -> {
			this.records.lockQuota(owner);
			Document document = this.records.document(owner, id).orElseThrow(ApiException::notFound);
			if (document.version() != version || this.records.deleteDocument(owner, id, version) == 0) {
				throw versionConflict();
			}
			if (this.references.release(document.fileId())) {
				this.uploads.deleteUploadOf(document.fileId());
			}
			// 지운 파일 행에 올린 파일 이름(곧 기본 제목)이 남지 않게 한다.
			this.uploads.forgetName(Kind.DOCUMENT, document.fileId());
			this.records.removeUsage(owner, document.sizeBytes(), 0, 1);
			// 대기 중인 요약은 취소하고 실행 중인 요약에는 취소를 요청한다. 결과와 쪽 이미지는 저장소 GC가 파일과 함께 지운다.
			StudyRoomDocumentSummaries summaries = this.summaries.getIfAvailable();
			if (summaries != null) {
				summaries.discard(document.fileId());
			}
			this.audit.success(owner, actor.sessionId(), "study_room.document.delete", "study_room_document", id,
					document.courseId());
			return null;
		});
	}

	/**
	 * 문서 PDF. 소유자만 받는다. 검사를 마치지 않았으면 409 {@code file_scanning}과 {@code Retry-After}, 거부했으면 422
	 * {@code content_rejected}다(다시 받아도 바뀌지 않으므로 409가 아니다). Range 하나를 받는 것과 감사 기록을 구간과 관계없이 같은
	 * session의 같은 문서마다 {@link #PLAY_AUDIT_WINDOW}에 한 번 남기는 것은 녹음 재생과 같다.
	 */
	ResponseEntity<InputStreamResource> documentContent(Actor actor, long id, String rangeHeader) {
		this.rateLimiter.check("study-room-play", actor.userId(), this.properties.playRateLimit(),
				this.properties.rateWindow());
		Document document = this.records.document(actor.userId(), id).orElseThrow(ApiException::notFound);
		if (Recording.SCANNING.equals(document.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "file_scanning", "악성코드 검사를 마치지 않은 문서입니다.",
					SCAN_RETRY_AFTER);
		}
		if (Recording.REJECTED.equals(document.state())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "content_rejected", "검사에서 거부된 문서입니다. 지워 주세요.");
		}
		long size = document.sizeBytes();
		Optional<ByteRange> range = ByteRange.parse(rangeHeader, size);
		long first = range.map(ByteRange::first).orElse(0L);
		long last = range.map(ByteRange::last).orElse(size - 1);
		this.audit.successOnceWithin(actor.userId(), actor.sessionId(), "study_room.document.read",
				"study_room_document", id, document.courseId(), PLAY_AUDIT_WINDOW);
		InputStream content = this.store.openRange(Bucket.CONTENT, document.storageKey(), first, last)
			.orElseThrow(ApiException::notFound);
		return Downloads.ranged(document.fileName(), PDF, size, first, last, range.isPresent(), content);
	}

	/** 문서 제목: 앞뒤 공백을 빼고 1~300자(UTF-16 단위), 제어 문자는 받지 않는다. */
	static String documentTitle(String raw) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty() || title.length() > MAX_DOCUMENT_TITLE
				|| title.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid(
					"문서 제목은 1자 이상 " + MAX_DOCUMENT_TITLE + "자 이하여야 하고 줄바꿈 같은 제어 문자를 쓸 수 없습니다.");
		}
		return title;
	}

	// 제목을 보내지 않으면 업로드 이름 규칙으로 정리한 파일 이름에서 .pdf를 뺀다. 이름이 올바르지 않으면 업로드가 422로 거부한다.
	private static String defaultDocumentTitle(String originalName) {
		String title = FileNames.sanitize(originalName)
			.map((name) -> name.regionMatches(true, Math.max(0, name.length() - 4), ".pdf", 0, 4)
					? name.substring(0, name.length() - 4) : name)
			.map(String::strip)
			.filter((name) -> !name.isEmpty())
			.orElse("문서");
		return title.length() > MAX_DOCUMENT_TITLE ? title.substring(0, MAX_DOCUMENT_TITLE).strip() : title;
	}

	// ---------- 공통 ----------

	Usage usage(long owner) {
		Quota quota = this.records.quota(owner);
		return new Usage(quota.usedBytes(), this.properties.quota().toBytes(), quota.recordingCount(),
				this.properties.maxRecordings(), this.uploads.maxFileBytes(Kind.RECORDING).orElse(null),
				this.properties.maxFoldersPerCourse(), quota.documentCount(), this.properties.maxDocuments(),
				this.uploads.maxFileBytes(Kind.DOCUMENT).orElse(null));
	}

	/**
	 * 폴더 이름: 앞뒤 공백을 빼고 1~60자(UTF-16 단위, 화면 입력의 maxlength와 같다), 제어 문자는 받지 않는다. {@code /}와
	 * {@code \}는 경로가 아니라 이름의 글자다.
	 */
	static String folderName(String raw) {
		String name = raw == null ? "" : raw.strip();
		if (name.isEmpty() || name.length() > MAX_FOLDER_NAME) {
			throw ApiException.invalid("폴더 이름은 1자 이상 " + MAX_FOLDER_NAME + "자 이하여야 합니다.");
		}
		if (name.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("폴더 이름에 줄바꿈 같은 제어 문자를 쓸 수 없습니다.");
		}
		return name;
	}

	/** 녹음 제목: 앞뒤 공백을 빼고 1~200자(UTF-16 단위), 제어 문자는 받지 않는다. */
	static String title(String raw) {
		String title = raw == null ? "" : raw.strip();
		if (title.isEmpty() || title.length() > MAX_TITLE || title.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("녹음 제목은 1자 이상 " + MAX_TITLE + "자 이하여야 하고 줄바꿈 같은 제어 문자를 쓸 수 없습니다.");
		}
		return title;
	}

	// 가져온 오디오 파일은 원래 이름으로, 녹음은 제목에 형식의 확장자를 붙여 내려받는다.
	private static String downloadName(Recording recording) {
		if ("audio".equals(recording.source())) {
			return recording.fileName();
		}
		String extension = FileNames.extension(recording.fileName()).orElse("webm");
		return FileNames.sanitize(recording.title() + "." + extension).orElse(recording.fileName());
	}

	private void requireMember(long owner, long courseId) {
		if (this.memberships.roleOf(owner, courseId).isEmpty()) {
			throw ApiException.notFound();
		}
	}

	private Folder folderIn(long owner, long folderId, long courseId) {
		return this.records.folder(owner, folderId)
			.filter((f) -> f.courseId() == courseId)
			.orElseThrow(ApiException::notFound);
	}

	/**
	 * 녹음이나 문서 하나를 더 둘 수 있는지. 용량은 녹음과 문서가 함께 쓴다. 오래가는 이유부터 답한다. 파일 하나의 크기 상한(413
	 * {@code too_large})은 지워도 풀리지 않으므로 개수(409 {@code too_many_recordings}, {@code too_many_documents})와 용량(409
	 * {@code quota_exceeded})보다 먼저 본다.
	 */
	private void requireRoom(Kind kind, Quota quota, long bytes) {
		this.uploads.requireSize(kind, bytes);
		if (kind == Kind.RECORDING && quota.recordingCount() + 1 > this.properties.maxRecordings()) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_recordings",
					"녹음은 " + this.properties.maxRecordings() + "개까지 저장할 수 있습니다.");
		}
		if (kind == Kind.DOCUMENT && quota.documentCount() + 1 > this.properties.maxDocuments()) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_documents",
					"문서는 " + this.properties.maxDocuments() + "개까지 저장할 수 있습니다.");
		}
		if (quota.usedBytes() + bytes > this.properties.quota().toBytes()) {
			throw new ApiException(HttpStatus.CONFLICT, "quota_exceeded", "학습실 용량이 부족합니다.");
		}
	}

	private void limit(Actor actor) {
		this.rateLimiter.check("study-room-write", actor.userId(), this.properties.rateLimit(),
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
				log.info("study room transaction retried attempt={} error={}", attempt, ex.getClass().getSimpleName());
			}
		}
	}

	private static ApiException versionConflict() {
		return new ApiException(HttpStatus.CONFLICT, "version_conflict", "다른 곳에서 먼저 바뀌었습니다. 다시 불러와 주세요.");
	}

	private static ApiException extensionNotAllowed() {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "extension_not_allowed", "올릴 수 없는 파일 형식입니다.");
	}

	private static ApiException nameConflict() {
		return new ApiException(HttpStatus.CONFLICT, "name_conflict", "같은 이름의 폴더가 이미 있습니다.");
	}

	private static ApiException folderSetChanged() {
		return new ApiException(HttpStatus.CONFLICT, "folder_set_changed",
				"그 사이 폴더가 생기거나 지워졌습니다. 다시 불러와 주세요.");
	}

	enum ItemKind {

		COURSE_FILE("course_file"), RECORDING("recording"), DOCUMENT("document");

		private final String code;

		ItemKind(String code) {
			this.code = code;
		}

		String code() {
			return this.code;
		}

	}

	/**
	 * @param source mic, tab, audio
	 * @param recordedAt 녹음 시작 일시(가져온 파일은 그 파일의 마지막 수정 일시)
	 * @param folderId 바로 둘 폴더. 없으면 과목 학습실 맨 위
	 */
	record NewRecording(String title, String source, long durationMs, Instant recordedAt, Long folderId) {
	}

	/** 녹음 업로드의 요청 지문에 들어가는 값. */
	record RecordingRequest(long courseId, NewRecording recording) {
	}

	/** 문서 업로드의 요청 지문에 들어가는 값. */
	record DocumentRequest(long courseId, String title, Long folderId) {
	}

	/** 폴더 만들기의 멱등 receipt. 이름은 원장에 남기지 않는다. */
	record CreatedFolder(long id) {
	}

	record FolderView(long id, long courseId, String name, int position, Instant createdAt, int version) {

		static FolderView of(Folder folder) {
			return new FolderView(folder.id(), folder.courseId(), folder.name(), folder.position(), folder.createdAt(),
					folder.version());
		}

	}

	/**
	 * @param createdAt 녹음 시작 일시(올릴 때 보낸 recordedAt)
	 * @param fileName 가져온 오디오 파일(source audio)의 이름. 녹음이면 null
	 * @param state ready, scanning(검사 중, 재생 불가), rejected(검사에서 거부)
	 * @param transcription 전사 작업(ADR-0041). 녹음 하나를 돌려주는 응답(상세, 업로드, 제목 바꾸기)에만 있고, 작업이 없거나 목록과
	 * 과목 보기면 키 자체가 없다
	 * @param summary 요약 작업. transcription과 같다
	 */
	record RecordingView(long id, long courseId, String title, String source, String mimeType, long sizeBytes,
			long durationMs, Instant createdAt, String fileName, String state, int version,
			@JsonInclude(JsonInclude.Include.NON_NULL) Job transcription,
			@JsonInclude(JsonInclude.Include.NON_NULL) Job summary) {

		/** 목록과 과목 보기: 전사·요약을 싣지 않는다. */
		static RecordingView of(Recording r) {
			return of(r, Map.of());
		}

		static RecordingView of(Recording r, Map<RecordingJobKind, Job> jobs) {
			return new RecordingView(r.id(), r.courseId(), r.title(), r.source(), r.mimeType(), r.sizeBytes(),
					r.durationMs(), r.recordedAt(), "audio".equals(r.source()) ? r.fileName() : null, r.state(),
					r.version(), jobs.get(RecordingJobKind.TRANSCRIPTION), jobs.get(RecordingJobKind.SUMMARY));
		}

	}

	/**
	 * 내 문서.
	 *
	 * @param fileName 올린 PDF 파일 이름(업로드 이름 규칙으로 정리한 값). 내려받을 때의 이름이다
	 * @param state ready, scanning(검사 중, 내용을 받을 수 없음), rejected(검사에서 거부)
	 * @param folderId 둔 폴더. 과목 학습실 맨 위면 null
	 * @param updatedAt 마지막으로 바꾼 일시. 바꾼 적이 없으면 createdAt
	 * @param summaryStatus 가장 최근 요약의 상태: queued, processing, ready, failed. 요청한 적이 없으면 null
	 */
	record DocumentView(long documentId, long courseId, String title, String fileName, long sizeBytes, String state,
			Long folderId, Instant createdAt, Instant updatedAt, int version, String summaryStatus) {

		static DocumentView of(Document d, String summaryStatus) {
			return new DocumentView(d.id(), d.courseId(), d.title(), d.fileName(), d.sizeBytes(), d.state(),
					d.folderId(), d.createdAt(), d.updatedAt(), d.version(), summaryStatus);
		}

	}

	/**
	 * @param kind course_file(itemId는 파일 ID), recording(itemId는 녹음 ID), document(itemId는 문서 ID)
	 */
	record PlacementView(String kind, long itemId, long folderId, int version) {

		static PlacementView of(Placement p) {
			if (p.fileId() != null) {
				return new PlacementView(ItemKind.COURSE_FILE.code(), p.fileId(), p.folderId(), p.version());
			}
			if (p.recordingId() != null) {
				return new PlacementView(ItemKind.RECORDING.code(), p.recordingId(), p.folderId(), p.version());
			}
			return new PlacementView(ItemKind.DOCUMENT.code(), p.documentId(), p.folderId(), p.version());
		}

	}

	/**
	 * @param usedBytes 녹음과 문서 파일의 바이트 합. quotaBytes를 함께 쓴다
	 * @param maxFileBytes 녹음 파일 하나의 크기 상한(upload_policies study_recording). 상한이 없으면 null
	 * @param maxDocumentFileBytes 문서 파일 하나의 크기 상한(upload_policies study_document). 상한이 없으면 null
	 */
	record Usage(long usedBytes, long quotaBytes, int recordingCount, int maxRecordings, Long maxFileBytes,
			int maxFoldersPerCourse, int documentCount, int maxDocuments, Long maxDocumentFileBytes) {
	}

	/** @param documents 내 문서. 올린 일시 역순이다 */
	record CourseView(long courseId, List<FolderView> folders, List<RecordingView> recordings,
			List<DocumentView> documents, List<PlacementView> placements, Usage usage) {
	}

	record RecordingList(List<RecordingView> recordings, Usage usage) {
	}

	/** 요청한 구간. first와 last 모두 포함한다. */
	record ByteRange(long first, long last) {

		private static final Pattern SINGLE = Pattern.compile("bytes=([0-9]{0,18})-([0-9]{0,18})");

		/**
		 * Range header 하나를 해석한다(RFC 9110 14.2). 없거나, 형식이 틀리거나, 여러 구간이면 무시하고 전체를 보낸다(비어 있는 값).
		 *
		 * @throws RangeNotSatisfiable 시작이 내용 끝 뒤이거나 뒤에서부터 0 byte를 요청했을 때
		 */
		static Optional<ByteRange> parse(String header, long size) {
			if (header == null) {
				return Optional.empty();
			}
			Matcher m = SINGLE.matcher(header.strip());
			if (!m.matches() || (m.group(1).isEmpty() && m.group(2).isEmpty())) {
				return Optional.empty();
			}
			if (m.group(1).isEmpty()) {
				long suffix = Long.parseLong(m.group(2));
				if (suffix == 0 || size == 0) {
					throw new RangeNotSatisfiable(size);
				}
				return Optional.of(new ByteRange(Math.max(0, size - suffix), size - 1));
			}
			long first = Long.parseLong(m.group(1));
			if (!m.group(2).isEmpty() && Long.parseLong(m.group(2)) < first) {
				return Optional.empty();
			}
			if (first >= size) {
				throw new RangeNotSatisfiable(size);
			}
			long last = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
			return Optional.of(new ByteRange(first, last));
		}

	}

	/** 내용 밖의 구간을 요청했다. 416과 {@code Content-Range: bytes *}{@code /size}로 답한다. */
	static final class RangeNotSatisfiable extends RuntimeException {

		private final long size;

		RangeNotSatisfiable(long size) {
			super("range not satisfiable", null, false, false);
			this.size = size;
		}

		long size() {
			return this.size;
		}

	}

}
