package com.stackcorp.astra.learning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.files.api.ImportedFiles;
import com.stackcorp.astra.files.api.ImportedFiles.ImportRejectedException;
import com.stackcorp.astra.files.api.ImportedFiles.Imported;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolSessions;
import com.stackcorp.astra.learning.api.FileSummaries;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 학교 e-Class 강의실 내용을 학생의 학교 session으로 필요할 때 읽어 서비스 화면의 모양으로 돌려준다(ADR-0043).
 * <ul>
 * <li>학교 session은 학생이 학번·학교 비밀번호로 로그인한 STack session에 붙어 있다({@link SchoolSessions}). 없거나 학교가 끝냈으면
 * 409 {@code school_signin_required}로 다시 로그인하라고 답한다.</li>
 * <li>목록(파일모음)은 학교 쪽 상태를 바꾸지 않으므로 목록 화면을 열 때 과목마다 {@code listInterval}에 한 번 읽어 저장한다. 학교
 * session이 없으면 저장해 둔 목록을 그대로 보여 준다.</li>
 * <li>상세(자료 본문)와 파일 받기는 학교에서 조회수가 늘 수 있으므로 그 학생이 그 항목을 열었을 때만 그 학생의 학교 session으로
 * 읽는다. 상세 본문은 저장하지 않는다.</li>
 * <li>받은 파일은 과목 파일로 저장해 같은 과목 구성원이 함께 연다. 그래서 두 번째 학생이 열 때는 학교에 요청하지 않고, 그 학생의
 * 학교 쪽 읽음·조회수는 바뀌지 않는다.</li>
 * <li>로그에는 과목 ID와 이유 코드만 남긴다.</li>
 * </ul>
 */
@Component
class SchoolContent {

	static final String ORIGIN = "school";

	static final String MATERIAL = "lecture_material";

	static final String FILE_PREFIX = "school-file-";

	static final String ACTIVITY_PREFIX = "school-";

	private static final Logger log = LoggerFactory.getLogger(SchoolContent.class);

	private final JdbcClient jdbc;

	private final SchoolFileStore files;

	private final SchoolSessions sessions;

	private final MemberCourses memberCourses;

	private final LearningAccess access;

	private final ImportedFiles imported;

	private final FileSummaries summaries;

	private final SchoolContentProperties properties;

	private final AuditLog audit;

	private final Map<Long, Object> copyLocks = new ConcurrentHashMap<>();

	SchoolContent(JdbcClient jdbc, SchoolFileStore files, SchoolSessions sessions, MemberCourses memberCourses,
			LearningAccess access, ImportedFiles imported, FileSummaries summaries, SchoolContentProperties properties,
			AuditLog audit) {
		this.jdbc = jdbc;
		this.files = files;
		this.sessions = sessions;
		this.memberCourses = memberCourses;
		this.access = access;
		this.imported = imported;
		this.summaries = summaries;
		this.properties = properties;
		this.audit = audit;
	}

	/**
	 * 학생에게 보이는 학교 자료 목록. 학교 강의실 화면 순서(주차, 화면 순)이다. 파일모음 목록을 읽을 때가 됐고 학교 session이 있으면 먼저
	 * 다시 읽는다.
	 */
	List<SchoolMaterialView> materials(Actor actor, long courseId, LocalDateTime now) {
		refreshList(actor, courseId);
		List<ActivityRow> rows = activities(courseId, MATERIAL, null);
		Map<String, List<SchoolFileStore.Row>> byItem = new LinkedHashMap<>();
		for (SchoolFileStore.Row file : this.files.of(courseId, MATERIAL,
				rows.stream().map(ActivityRow::itemId).toList())) {
			byItem.computeIfAbsent(file.schoolItemId(), (k) -> new ArrayList<>()).add(file);
		}
		Set<Long> summarized = summarized(byItem.values().stream().flatMap(List::stream).toList());
		return rows.stream()
			.map((row) -> new SchoolMaterialView(ACTIVITY_PREFIX + row.id(), ORIGIN, row.weekNo(), row.title(), null,
					row.openAt(), true, phase(row, now), null,
					fileViews(byItem.getOrDefault(row.itemId(), List.of()), summarized), null, row.schoolUrl()))
			.toList();
	}

	/**
	 * 학교 학습활동 하나의 상세. 자료는 학생의 학교 session으로 학교 상세를 읽는다(학교에서 조회수가 하나 는다). 다른 유형은 학교에
	 * 요청하지 않고 저장해 둔 목록 값만 준다.
	 */
	SchoolActivityView activity(Actor actor, long courseId, long schoolActivityId, LocalDateTime now) {
		requireMember(actor, courseId);
		ActivityRow row = activities(courseId, null, schoolActivityId).stream()
			.findFirst()
			.orElseThrow(ApiException::notFound);
		String description = null;
		String author = null;
		LocalDateTime postedAt = null;
		if (MATERIAL.equals(row.type())) {
			SchoolPost material = read(actor, courseId,
					(rooms, key) -> rooms.material(key, row.itemId(), this.properties.requestTimeout(),
							this.properties.readBudget()));
			this.files.replaceFor(courseId, MATERIAL, row.itemId(), material.files());
			description = material.body();
			author = material.author();
			postedAt = material.postedAt();
		}
		List<SchoolFileStore.Row> attached = this.files.of(courseId, row.type(), List.of(row.itemId()));
		return new SchoolActivityView(ACTIVITY_PREFIX + row.id(), ORIGIN, row.type(), row.weekNo(), row.title(),
				description, row.openAt(), row.closeAt(), true, phase(row, now), now,
				fileViews(attached, summarized(attached)), List.of(), null, author, postedAt, row.attendance(),
				row.schoolUrl());
	}

	/**
	 * 학교 파일 하나를 연다. 받아 둔 사본이 있으면 그것을, 없으면 학생의 학교 session으로 받아 과목 파일로 저장한 뒤 그것을 준다.
	 * @return 서비스 파일 ID와 지금 열 수 있는지(악성코드 검사 중이면 false)
	 */
	OpenedFile open(Actor actor, long courseId, long schoolFileId) {
		requireMember(actor, courseId);
		SchoolFileStore.Row row = this.files.find(courseId, schoolFileId).orElseThrow(ApiException::notFound);
		if (row.fileId() != null) {
			return new OpenedFile(row.fileId(), true);
		}
		Object lock = this.copyLocks.computeIfAbsent(schoolFileId, (k) -> new Object());
		try {
			synchronized (lock) {
				row = this.files.find(courseId, schoolFileId).orElseThrow(ApiException::notFound);
				if (row.fileId() != null) {
					return new OpenedFile(row.fileId(), true);
				}
				return copy(actor, courseId, row);
			}
		}
		finally {
			this.copyLocks.remove(schoolFileId, lock);
		}
	}

	private OpenedFile copy(Actor actor, long courseId, SchoolFileStore.Row row) {
		Path temp;
		try {
			temp = Files.createTempFile("school-file-", ".bin");
		}
		catch (IOException ex) {
			throw new IllegalStateException("임시 파일을 만들지 못했습니다.", ex);
		}
		try {
			long started = System.nanoTime();
			SchoolFile file = row.schoolFile();
			read(actor, courseId, (rooms, key) -> rooms.download(key, file, this.imported.maxFileBytes(), temp,
					this.properties.requestTimeout(), this.properties.downloadBudget()));
			Imported stored;
			try {
				stored = this.imported.store(actor.userId(), courseId, row.name(), temp);
			}
			catch (ImportRejectedException ex) {
				log.info("School file not kept: course={} reason={}", courseId, ex.getMessage());
				throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "school_file_unsupported",
						"이 파일은 STack에서 열 수 없습니다. 학교 e-Class에서 열어 주세요.");
			}
			if (!this.files.copied(row.id(), stored.attachmentGroupId(), stored.fileId())) {
				this.files.discard(stored.attachmentGroupId());
				return this.files.find(courseId, row.id())
					.filter((r) -> r.fileId() != null)
					.map((r) -> new OpenedFile(r.fileId(), true))
					.orElseThrow(ApiException::notFound);
			}
			this.audit.success(actor.userId(), actor.sessionId(), "school_file.copy", "file", stored.fileId(), courseId);
			log.info("School file kept: course={} file={} ms={}", courseId, stored.fileId(),
					(System.nanoTime() - started) / 1_000_000);
			return new OpenedFile(stored.fileId(), stored.ready());
		}
		finally {
			try {
				Files.deleteIfExists(temp);
			}
			catch (IOException ex) {
				log.warn("School file temp not deleted: type={}", ex.getClass().getSimpleName());
			}
		}
	}

	/** 목록을 읽을 때가 됐으면 학교 파일모음 목록을 다시 읽는다. 실패해도 저장해 둔 목록을 쓰므로 화면은 실패시키지 않는다. */
	private void refreshList(Actor actor, long courseId) {
		if (!this.files.listDue(courseId, this.properties.listInterval())) {
			return;
		}
		Optional<SchoolCourseRooms> rooms = this.sessions.of(actor.sessionId());
		Optional<String> key = courseKey(actor.userId(), courseId);
		if (rooms.isEmpty() || key.isEmpty()) {
			return;
		}
		try {
			long started = System.nanoTime();
			List<SchoolFile> list = rooms.get()
				.files(key.get(), this.properties.requestTimeout(), this.properties.readBudget());
			this.files.replace(courseId, list);
			log.info("School file list read: course={} files={} ms={}", courseId, list.size(),
					(System.nanoTime() - started) / 1_000_000);
		}
		catch (SchoolLoginException ex) {
			if (ex.kind() == SchoolLoginException.Kind.EXPIRED) {
				this.sessions.forget(actor.sessionId());
			}
			log.warn("School file list read failed: course={} reason={}", courseId, ex.getMessage());
		}
	}

	/**
	 * 학생의 학교 session으로 학교를 읽는다. 학교 session이 없거나 학교가 끝냈으면 409 {@code school_signin_required}다.
	 */
	<T> T read(Actor actor, long courseId, SchoolRead<T> reader) {
		String key = courseKey(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		SchoolCourseRooms rooms = this.sessions.of(actor.sessionId()).orElseThrow(SchoolContent::signInRequired);
		try {
			return reader.read(rooms, key);
		}
		catch (SchoolLoginException ex) {
			switch (ex.kind()) {
				case EXPIRED -> {
					this.sessions.forget(actor.sessionId());
					throw signInRequired();
				}
				case NOT_FOUND -> throw ApiException.notFound();
				default -> {
					log.warn("School content read failed: course={} reason={}", courseId, ex.getMessage());
					if ("too_large".equals(ex.getMessage())) {
						throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "school_file_too_large",
								"파일이 너무 커서 STack에서 열 수 없습니다. 학교 e-Class에서 열어 주세요.");
					}
					throw new ApiException(HttpStatus.BAD_GATEWAY, "school_unavailable",
							"학교 e-Class 서버에서 내용을 읽지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
				}
			}
		}
	}

	static ApiException signInRequired() {
		return new ApiException(HttpStatus.CONFLICT, "school_signin_required",
				"학교 e-Class 연결이 끝났습니다. 학번과 학교 비밀번호로 다시 로그인해 주세요.");
	}

	void requireMember(Actor actor, long courseId) {
		this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
	}

	/** 학생으로 수강 중인 과목의 학교 과목 키. */
	Optional<String> courseKey(long userId, long courseId) {
		return this.memberCourses.of(userId)
			.stream()
			.filter((c) -> c.courseId() == courseId && c.role() == CourseRole.STUDENT && c.key() != null)
			.map(MemberCourse::key)
			.findFirst();
	}

	private List<ActivityRow> activities(long courseId, String type, Long schoolActivityId) {
		return this.jdbc.sql("""
				SELECT school_activity_id, school_type, school_item_id, week_no, title, open_at, close_at, expired_yn,
				       attendance_label, school_url
				  FROM school_activities
				 WHERE course_id = :course AND (:type IS NULL OR school_type = :type)
				   AND (:id IS NULL OR school_activity_id = :id)
				 ORDER BY week_no, sort_order
				""")
			.param("course", courseId)
			.param("type", type)
			.param("id", schoolActivityId)
			.query((rs, n) -> new ActivityRow(rs.getLong("school_activity_id"), rs.getString("school_type"),
					rs.getString("school_item_id"), rs.getInt("week_no"), rs.getString("title"),
					rs.getObject("open_at", LocalDateTime.class), rs.getObject("close_at", LocalDateTime.class),
					rs.getBoolean("expired_yn"), rs.getString("attendance_label"), rs.getString("school_url")))
			.list();
	}

	private static String phase(ActivityRow row, LocalDateTime now) {
		return row.expired() ? "closed" : ActivityPhase.of(row.openAt(), row.closeAt(), null, now);
	}

	Set<Long> summarized(List<SchoolFileStore.Row> rows) {
		List<Long> ids = rows.stream().map(SchoolFileStore.Row::fileId).filter((id) -> id != null).toList();
		return ids.isEmpty() ? Set.of() : this.summaries.publishedAmong(ids);
	}

	static List<SchoolFileView> fileViews(List<SchoolFileStore.Row> rows, Set<Long> summarized) {
		return rows.stream().map((row) -> {
			String extension = extension(row.name());
			Object id = row.fileId() != null ? row.fileId() : FILE_PREFIX + row.id();
			return new SchoolFileView(id, row.name(), row.sizeBytes(), extension, "pdf".equals(extension),
					row.fileId() != null && summarized.contains(row.fileId()), ORIGIN, row.fileId() != null);
		}).toList();
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 || dot == name.length() - 1 ? null : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	@FunctionalInterface
	interface SchoolRead<T> {

		T read(SchoolCourseRooms rooms, String courseKey);

	}

	record OpenedFile(long fileId, boolean ready) {
	}

	private record ActivityRow(long id, String type, String itemId, int weekNo, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean expired, String attendance, String schoolUrl) {
	}

	/**
	 * 학교 파일. 서비스 파일({@code MaterialFile})과 같은 이름의 값을 가진다.
	 * @param fileId 받아 둔 사본이 있으면 서비스 파일 ID(숫자). 없으면 {@code "school-file-<id>"}이고 그 경로({@code /files/school-file-<id>/…})
	 * 로 열면 받아 둔 뒤 서비스 파일 경로로 넘긴다
	 * @param sizeBytes 학교 화면 크기 표시의 근사값. 모르면 null
	 * @param copied 받아 둔 사본이 있는지
	 */
	record SchoolFileView(Object fileId, String name, Long sizeBytes, String extension, boolean pdf,
			boolean summaryPublished, String origin, boolean copied) {
	}

	/**
	 * 자료 목록의 학교 자료. 서비스 자료({@code MaterialView})와 같은 이름의 값에 {@code origin}, {@code schoolUrl}을 더한다.
	 * {@code createdAt}과 {@code version}은 없다(null).
	 */
	record SchoolMaterialView(String activityId, String origin, int weekNo, String title, String description,
			LocalDateTime openAt, boolean published, String phase, java.time.Instant createdAt,
			List<SchoolFileView> files, Integer version, String schoolUrl) implements LearningController.MaterialItem {
	}

	/**
	 * 학교 학습활동 상세. 서비스 활동 상세({@code ActivityView})와 같은 이름의 값에 학교 값을 더한다.
	 * @param type 학교 메뉴 ID. 서비스 활동 유형과 같은 말이다(lecture_material, lecture_weeks, report, test, survey 등)
	 * @param description 자료 본문(글자). 자료가 아니면 null
	 * @param attendance 영상의 출석부 반영 표시(예 {@code 3 주 1차시}). 없으면 null
	 */
	record SchoolActivityView(String activityId, String origin, String type, int weekNo, String title,
			String description, LocalDateTime openAt, LocalDateTime closeAt, boolean published, String phase,
			LocalDateTime serverTime, List<SchoolFileView> files, List<Object> lectureItems, Integer version,
			@JsonInclude(JsonInclude.Include.NON_NULL) String author,
			@JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime postedAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) String attendance, String schoolUrl) {
	}

}
