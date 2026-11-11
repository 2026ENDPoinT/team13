package com.stackcorp.astra.learning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.LearningAccess.Activity;
import com.stackcorp.astra.learning.MaterialFiles.MaterialFile;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의실 파일모음(EP-0037, EP-0038의 대체 계약, 화면 52). 학습활동(자료, 과제 등)에 첨부한 파일을 주차, 주차 안 순서, 활동, 파일 순으로
 * 모은다. 학생이 제출한 파일은 활동 첨부가 아니므로 없다.
 * <p>
 * 보이는 파일은 그 파일을 내려받을 때의 권한과 같다. 파일 권한은 첨부한 활동의 권한이고({@link MaterialAttachmentAccess}), 활동 권한은
 * {@link LearningAccess#visible} 하나로 정한다. 담당자는 과목의 모든 활동, 학생은 공개했고 시작한 활동의 첨부만 본다(SC-06). 지운
 * 활동과 지운 파일은 없다(ADR-0011). 구성원이 아니면 404다.
 * <p>
 * 원본은 한 번에 모두 내지만 활동이 늘면 목록도 길어지므로 쪽을 나눈다. 다음 쪽은 nextCursor로 받는다. 쪽과 관계없이 권한 판정 뒤 쿼리는
 * 넷(시계, 활동, 첨부, 요약 게시 여부)이다. 검색(q)은 파일 이름과 활동 제목에서 대소문자 없이 찾는다.
 */
@RestController
class CourseFilesController {

	private static final int MAX_PAGE = 100;

	private static final int MAX_QUERY = 100;

	// 목록 cursor: 주차.주차 안 순서.활동 ID.파일 ID
	private static final Pattern CURSOR = Pattern
		.compile("([0-9]{1,9})\\.([0-9]{1,9})\\.([1-9][0-9]{0,17})\\.([1-9][0-9]{0,17})");

	private static final Comparator<Position> ORDER = Comparator.comparingInt(Position::weekNo)
		.thenComparingInt(Position::sortOrder)
		.thenComparingLong(Position::activityId)
		.thenComparingLong(Position::fileId);

	private final JdbcClient jdbc;

	private final LearningAccess access;

	private final MaterialFiles materialFiles;

	CourseFilesController(JdbcClient jdbc, LearningAccess access, MaterialFiles materialFiles) {
		this.jdbc = jdbc;
		this.access = access;
		this.materialFiles = materialFiles;
	}

	/**
	 * @param q 파일 이름이나 활동 제목에 들어 있는 글자. 없으면 전체
	 * @param size 1~{@value #MAX_PAGE}, 기본 50
	 */
	@GetMapping("/api/courses/{courseId}/files")
	ResponseEntity<CourseFilePage> files(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int size) {
		CourseRole role = this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		if (size < 1 || size > MAX_PAGE) {
			throw ApiException.invalid("한 번에 받을 수 있는 파일 수는 1개 이상 " + MAX_PAGE + "개 이하입니다.");
		}
		String query = query(q);
		Position after = cursor(cursor);
		LocalDateTime now = this.access.now();
		List<ActivityRow> activities = this.jdbc.sql("""
				SELECT w.week_no, w.title AS week_title, w.starts_on, w.ends_on, a.activity_id, a.activity_type, a.title,
				       a.sort_order, a.published_yn, a.open_at, a.attachment_group_id
				  FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
				 WHERE a.course_id = :course AND a.deleted_at IS NULL AND a.attachment_group_id IS NOT NULL
				""")
			.param("course", courseId)
			.query((rs, n) -> new ActivityRow(rs.getInt("week_no"), rs.getString("week_title"),
					rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class),
					new Activity(rs.getLong("activity_id"), courseId, rs.getBoolean("published_yn"),
							rs.getObject("open_at", LocalDateTime.class)),
					rs.getString("activity_type"), rs.getString("title"), rs.getInt("sort_order"),
					rs.getLong("attachment_group_id")))
			.list()
			.stream()
			.filter((row) -> LearningAccess.visible(role, row.activity(), now))
			.toList();
		Map<Long, List<MaterialFile>> byGroup = this.materialFiles
			.of(activities.stream().map(ActivityRow::attachmentGroupId).distinct().toList());
		List<Entry> entries = new ArrayList<>();
		for (ActivityRow row : activities) {
			boolean titleMatches = query == null || contains(row.title(), query);
			for (MaterialFile file : byGroup.getOrDefault(row.attachmentGroupId(), List.of())) {
				if (titleMatches || contains(file.name(), query)) {
					entries.add(new Entry(row, file, new Position(row.weekNo(), row.sortOrder(),
							row.activity().activityId(), file.fileId())));
				}
			}
		}
		entries.sort(Comparator.comparing(Entry::position, ORDER));
		List<Entry> rest = entries.stream()
			.filter((e) -> after == null || ORDER.compare(e.position(), after) > 0)
			.limit(size + 1L)
			.toList();
		List<Entry> page = rest.size() > size ? rest.subList(0, size) : rest;
		String next = null;
		if (rest.size() > size) {
			Position last = page.getLast().position();
			next = last.weekNo() + "." + last.sortOrder() + "." + last.activityId() + "." + last.fileId();
		}
		List<CourseFileItem> items = page.stream().map((e) -> {
			ActivityRow row = e.activity();
			return new CourseFileItem(row.weekNo(), row.weekTitle(), row.startsOn(), row.endsOn(),
					row.activity().activityId(), row.type(), row.title(), e.file());
		}).toList();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new CourseFilePage(now, items, entries.size(), next));
	}

	private static boolean contains(String text, String query) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(query);
	}

	private static String query(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String text = raw.strip();
		if (text.length() > MAX_QUERY || text.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("검색어는 " + MAX_QUERY + "자 이하의 한 줄 글이어야 합니다.");
		}
		return text.toLowerCase(Locale.ROOT);
	}

	private static Position cursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		Matcher m = CURSOR.matcher(cursor);
		if (!m.matches()) {
			throw ApiException.invalid("다음 쪽 위치가 올바르지 않습니다. 처음부터 다시 불러 주세요.");
		}
		return new Position(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Long.parseLong(m.group(3)),
				Long.parseLong(m.group(4)));
	}

	private record ActivityRow(int weekNo, String weekTitle, LocalDate startsOn, LocalDate endsOn, Activity activity,
			String type, String title, int sortOrder, long attachmentGroupId) {
	}

	private record Position(int weekNo, int sortOrder, long activityId, long fileId) {
	}

	private record Entry(ActivityRow activity, MaterialFile file, Position position) {
	}

	/**
	 * @param serverTime 판정에 쓴 DB 시각
	 * @param total 검색 조건에 맞는 파일 수
	 * @param nextCursor 다음 쪽이 있으면 그 cursor, 없으면 null
	 */
	record CourseFilePage(LocalDateTime serverTime, List<CourseFileItem> items, int total, String nextCursor) {
	}

	/**
	 * 파일 한 개와 그 파일을 첨부한 활동. 같은 활동의 파일은 이어서 나온다. 내려받기는
	 * {@code GET /api/courses/{courseId}/files/{fileId}/content}, 미리보기는 {@code POST …/preview-sessions}다.
	 *
	 * @param activityType learning_activities.activity_type(lecture_material, report 등). 활동 화면은 activityId로 연다
	 */
	record CourseFileItem(int weekNo, String weekTitle, LocalDate startsOn, LocalDate endsOn, long activityId,
			String activityType, String activityTitle, MaterialFile file) {
	}

}
