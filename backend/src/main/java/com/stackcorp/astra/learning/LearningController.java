package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningAccess.Activity;
import com.stackcorp.astra.learning.MaterialService.MaterialChange;
import com.stackcorp.astra.learning.MaterialService.NewMaterial;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의실의 주차, 학습활동, 강의 자료(EP-0009, EP-0010, EP-0042의 대체 계약).
 * 과목은 경로로 명시한다(SC-03). 과목의 구성원이 아니거나 볼 수 없는 활동이면 존재를 드러내지 않도록 404로 응답한다.
 * 학생에게는 공개한 활동만 목록에 나오고, 시작 전 활동은 제목과 기간만 보인다. 본문과 첨부는 시작한 뒤에 볼 수 있다.
 * 설명은 일반 텍스트로만 받고 내보낸다. HTML 본문(learning_material_details.body_html)은 서버 정규화(SC-08)를 만들 때까지 쓰지 않는다.
 * 지운 활동(deleted_at)은 담당자에게도 어느 경로로도 보이지 않는다(ADR-0011).
 */
@RestController
class LearningController {

	private static final int MAX_TITLE = 300;

	private static final int MAX_DESCRIPTION = 10_000;

	private static final int MAX_WEEK = 60;

	private static final int MAX_UPLOADS = 10;

	private static final Pattern VERSION = Pattern.compile("\"([1-9][0-9]{0,8})\"");

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final Attachments attachments;

	private final MaterialService materials;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	LearningController(JdbcClient jdbc, LearningAccess access, Attachments attachments, MaterialService materials,
			RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit) {
		this.jdbc = jdbc;
		this.access = access;
		this.attachments = attachments;
		this.materials = materials;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
	}

	@GetMapping("/api/courses/{courseId}/weeks")
	ResponseEntity<List<WeekView>> weeks(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		boolean instructor = role(actor, courseId) == CourseRole.INSTRUCTOR;
		LocalDateTime now = this.access.now();
		Map<Integer, WeekView> weeks = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT w.week_no, w.title AS week_title, w.starts_on, w.ends_on, a.activity_id, a.activity_type, a.title,
				       a.open_at, a.close_at, a.published_yn
				  FROM course_weeks w LEFT JOIN learning_activities a ON a.course_week_id = w.course_week_id AND a.deleted_at IS NULL
				 WHERE w.course_id = :course
				 ORDER BY w.week_no, a.sort_order, a.activity_id
				""").param("course", courseId).query((rs) -> {
			String weekTitle = rs.getString("week_title");
			LocalDate startsOn = rs.getObject("starts_on", LocalDate.class);
			LocalDate endsOn = rs.getObject("ends_on", LocalDate.class);
			WeekView week = weeks.computeIfAbsent(rs.getInt("week_no"),
					(no) -> new WeekView(no, weekTitle, startsOn, endsOn, new ArrayList<>()));
			long activityId = rs.getLong("activity_id");
			if (rs.wasNull()) {
				return;
			}
			Activity activity = new Activity(activityId, courseId, rs.getBoolean("published_yn"),
					rs.getObject("open_at", LocalDateTime.class));
			if (!instructor && !activity.published()) {
				return;
			}
			week.activities()
				.add(new ActivityItem(activityId, rs.getString("activity_type"), rs.getString("title"), activity.openAt(),
						rs.getObject("close_at", LocalDateTime.class), activity.published(),
						instructor || LearningAccess.open(activity, now)));
		});
		return ok(List.copyOf(weeks.values()));
	}

	@GetMapping("/api/courses/{courseId}/materials")
	ResponseEntity<List<MaterialView>> materials(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		boolean instructor = role(actor, courseId) == CourseRole.INSTRUCTOR;
		LocalDateTime now = this.access.now();
		List<ActivityRow> rows = this.jdbc.sql(ACTIVITY + """
				 WHERE a.course_id = :course AND a.activity_type = 'lecture_material' AND a.deleted_at IS NULL
				 ORDER BY a.created_at DESC, a.activity_id DESC
				""")
			.param("course", courseId)
			.query(LearningController::activityRow)
			.list()
			.stream()
			.filter((row) -> instructor || LearningAccess.open(row.activity(), now))
			.toList();
		Map<Long, List<AttachedFile>> files = this.attachments
			.list(rows.stream().map(ActivityRow::attachmentGroupId).filter(Objects::nonNull).toList());
		return ok(rows.stream().map((row) -> MaterialView.of(row, files(files, row))).toList());
	}

	@GetMapping("/api/courses/{courseId}/activities/{activityId}")
	ResponseEntity<ActivityView> activity(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long activityId) {
		ActivityRow row = this.jdbc.sql(ACTIVITY + " WHERE a.activity_id = :activity AND a.course_id = :course AND a.deleted_at IS NULL")
			.param("activity", activityId)
			.param("course", courseId)
			.query(LearningController::activityRow)
			.optional()
			.filter((r) -> this.access.canView(actor.userId(), r.activity()))
			.orElseThrow(ApiException::notFound);
		List<AttachedFile> files = row.attachmentGroupId() == null ? List.of()
				: files(this.attachments.list(List.of(row.attachmentGroupId())), row);
		List<LectureItem> items = this.jdbc.sql("""
				SELECT online_lecture_item_id, title, duration_sec FROM online_lecture_items
				 WHERE activity_id = :activity ORDER BY link_seq
				""")
			.param("activity", activityId)
			.query((rs, n) -> new LectureItem(rs.getLong(1), rs.getString(2), rs.getObject(3, Integer.class)))
			.list();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(etag(row.version()))
			.body(new ActivityView(row.activity().activityId(), row.type(), row.weekNo(), row.title(), row.description(),
					row.activity().openAt(), row.closeAt(), row.activity().published(), files, items, row.version()));
	}

	/** {@code Idempotency-Key}를 보내면 같은 key의 재전송은 처음 응답을 다시 돌려받고 자료를 더 만들지 않는다(SC-05). */
	@PostMapping("/api/courses/{courseId}/materials")
	ResponseEntity<MaterialView> createMaterial(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody CreateMaterial body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		NewMaterial material = validated(body);
		this.rateLimiter.check("material-create", actor.userId(), 60, Duration.ofMinutes(10));
		MaterialView created = this.idempotency.execute(actor.userId(), "material.create", idempotencyKey,
				List.of(courseId, material), MaterialView.class, () -> create(actor, courseId, material));
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(created);
	}

	// 활동 저장, 응답 생성, 감사 기록이 멱등 원장과 같은 transaction에 들어간다.
	private MaterialView create(Actor actor, long courseId, NewMaterial material) {
		long activityId = this.materials.create(actor.userId(), courseId, material);
		ActivityRow row = this.jdbc.sql(ACTIVITY + " WHERE a.activity_id = :activity")
			.param("activity", activityId)
			.query(LearningController::activityRow)
			.single();
		List<AttachedFile> files = row.attachmentGroupId() == null ? List.of()
				: files(this.attachments.list(List.of(row.attachmentGroupId())), row);
		this.audit.success(actor.userId(), actor.sessionId(), "material.create", "learning_activity", activityId,
				courseId);
		return MaterialView.of(row, files);
	}

	/**
	 * 강의 자료 수정. 본문은 수정한 뒤의 전체 상태이고 첨부는 최종 집합(keep: 이 자료의 파일, add: 내 업로드)이다(SC-05-B).
	 * If-Match에 마지막으로 읽은 version을 보내야 하고, 그 사이 다른 수정이 있었으면 아무것도 바꾸지 않고 409로 거부한다(SC-05).
	 * 집합에서 빠진 파일은 바로 누구도 읽지 못한다.
	 */
	@PutMapping("/api/courses/{courseId}/materials/{activityId}")
	ResponseEntity<MaterialView> updateMaterial(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long activityId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody UpdateMaterial body) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		int version = expectedVersion(ifMatch);
		MaterialChange change = validated(body);
		this.rateLimiter.check("material-update", actor.userId(), 60, Duration.ofMinutes(10));
		this.materials.update(actor.userId(), courseId, activityId, version, change);
		ActivityRow row = this.jdbc.sql(ACTIVITY + " WHERE a.activity_id = :activity")
			.param("activity", activityId)
			.query(LearningController::activityRow)
			.single();
		List<AttachedFile> files = row.attachmentGroupId() == null ? List.of()
				: files(this.attachments.list(List.of(row.attachmentGroupId())), row);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(etag(row.version()))
			.body(MaterialView.of(row, files));
	}

	/**
	 * 강의 자료 삭제. 활동에는 삭제 표시만 남기고 학습 기록은 지우지 않는다. 첨부는 끊고 파일은 지운다. 그 순간부터 학생과 담당자
	 * 모두 목록, 상세, 파일, 요약, 쪽 이미지에 닿지 못한다(ADR-0011). If-Match는 수정과 같다.
	 */
	@DeleteMapping("/api/courses/{courseId}/materials/{activityId}")
	ResponseEntity<Void> deleteMaterial(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long activityId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
		this.materials.delete(actor.userId(), courseId, activityId, expectedVersion(ifMatch));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	// If-Match: "3". 약한 비교(W/)와 *는 받지 않는다. 마지막으로 읽은 version을 정확히 보내야 한다.
	private static int expectedVersion(String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
					"If-Match에 자료의 version을 보내야 합니다.");
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

	private static MaterialChange validated(UpdateMaterial body) {
		if (body.published() == null) {
			throw ApiException.invalid("published 값이 필요합니다.");
		}
		if (body.attachments() == null) {
			throw ApiException.invalid("attachments 값이 필요합니다.");
		}
		List<Long> keep = ids(body.attachments().keep(), "attachments.keep");
		List<Long> add = ids(body.attachments().add(), "attachments.add");
		if (keep.size() + add.size() > MAX_UPLOADS) {
			throw ApiException.invalid("첨부는 " + MAX_UPLOADS + "개까지입니다.");
		}
		NewMaterial fields = validated(new CreateMaterial(body.weekNo(), body.title(), body.description(), body.openAt(),
				body.published(), add));
		return new MaterialChange(fields.weekNo(), fields.title(), fields.description(), fields.openAt(),
				fields.published(), keep, add);
	}

	private static List<Long> ids(List<Long> ids, String field) {
		List<Long> list = ids == null ? List.of() : ids;
		if (list.size() > MAX_UPLOADS || list.stream().anyMatch((id) -> id == null || id < 1)
				|| new LinkedHashSet<>(list).size() != list.size()) {
			throw ApiException.invalid(field + "가 올바르지 않습니다.");
		}
		return list;
	}

	private CourseRole role(Actor actor, long courseId) {
		return this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
	}

	private static NewMaterial validated(CreateMaterial body) {
		if (body.weekNo() == null || body.weekNo() < 1 || body.weekNo() > MAX_WEEK) {
			throw ApiException.invalid("weekNo는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		String title = body.title() == null ? "" : body.title().strip();
		if (title.isEmpty() || title.length() > MAX_TITLE || hasControl(title, false)) {
			throw ApiException.invalid("title은 1자 이상 " + MAX_TITLE + "자 이하여야 합니다.");
		}
		String description = body.description() == null || body.description().isBlank() ? null : body.description();
		if (description != null && (description.length() > MAX_DESCRIPTION || hasControl(description, true))) {
			throw ApiException.invalid("description은 " + MAX_DESCRIPTION + "자 이하여야 합니다.");
		}
		List<Long> uploadIds = body.uploadIds() == null ? List.of() : body.uploadIds();
		if (uploadIds.size() > MAX_UPLOADS || uploadIds.stream().anyMatch((id) -> id == null || id < 1)
				|| new LinkedHashSet<>(uploadIds).size() != uploadIds.size()) {
			throw ApiException.invalid("uploadIds가 올바르지 않습니다.");
		}
		return new NewMaterial(body.weekNo(), title, description, body.openAt(),
				body.published() == null || body.published(), uploadIds);
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

	private static List<AttachedFile> files(Map<Long, List<AttachedFile>> byGroup, ActivityRow row) {
		return row.attachmentGroupId() == null ? List.of() : byGroup.getOrDefault(row.attachmentGroupId(), List.of());
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private static final String ACTIVITY = """
			SELECT a.activity_id, a.course_id, a.activity_type, w.week_no, a.title, a.description, a.open_at, a.close_at,
			       a.published_yn, a.created_at, a.attachment_group_id, a.row_version
			  FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
			""";

	private static ActivityRow activityRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new ActivityRow(
				new Activity(rs.getLong("activity_id"), rs.getLong("course_id"), rs.getBoolean("published_yn"),
						rs.getObject("open_at", LocalDateTime.class)),
				rs.getString("activity_type"), rs.getInt("week_no"), rs.getString("title"), rs.getString("description"),
				rs.getObject("close_at", LocalDateTime.class), rs.getTimestamp("created_at").toInstant(),
				rs.getObject("attachment_group_id", Long.class), rs.getInt("row_version"));
	}

	private record ActivityRow(Activity activity, String type, int weekNo, String title, String description,
			LocalDateTime closeAt, Instant createdAt, Long attachmentGroupId, int version) {
	}

	/** 요청 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02). */
	record CreateMaterial(Integer weekNo, String title, String description, LocalDateTime openAt, Boolean published,
			List<Long> uploadIds) {
	}

	/** 수정 요청 본문. 모든 값을 보낸다. 빠진 선택 값(description, openAt)은 비운다는 뜻이다. */
	record UpdateMaterial(Integer weekNo, String title, String description, LocalDateTime openAt, Boolean published,
			AttachmentSet attachments) {
	}

	/**
	 * @param keep 남길 파일 ID. 이 자료에 첨부된 파일만 된다
	 * @param add 새로 첨부할 업로드 ID. 내가 이 과목의 강의 자료로 올린 업로드만 된다
	 */
	record AttachmentSet(List<Long> keep, List<Long> add) {
	}

	record WeekView(int weekNo, String title, LocalDate startsOn, LocalDate endsOn, List<ActivityItem> activities) {
	}

	/**
	 * @param accessible 지금 상세와 첨부를 볼 수 있는지
	 */
	record ActivityItem(long activityId, String type, String title, LocalDateTime openAt, LocalDateTime closeAt,
			boolean published, boolean accessible) {
	}

	/**
	 * @param version 수정·삭제할 때 If-Match로 보내는 값
	 */
	record MaterialView(long activityId, int weekNo, String title, String description, LocalDateTime openAt,
			boolean published, Instant createdAt, List<AttachedFile> files, int version) {

		static MaterialView of(ActivityRow row, List<AttachedFile> files) {
			return new MaterialView(row.activity().activityId(), row.weekNo(), row.title(), row.description(),
					row.activity().openAt(), row.activity().published(), row.createdAt(), files, row.version());
		}

	}

	record LectureItem(long itemId, String title, Integer durationSec) {
	}

	record ActivityView(long activityId, String type, int weekNo, String title, String description,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published, List<AttachedFile> files,
			List<LectureItem> lectureItems, int version) {
	}

}
