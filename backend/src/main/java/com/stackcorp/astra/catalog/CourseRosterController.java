package com.stackcorp.astra.catalog;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.stackcorp.astra.catalog.CourseRosterQueries.Entry;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.RosterLearning;
import com.stackcorp.astra.catalog.api.RosterLearning.Learning;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserDirectory;
import com.stackcorp.astra.identity.api.UserDirectory.UserCard;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당자의 수강생 화면(교수자 learner_list_form). 그 과목의 담당자(교수, 조교, 비정규 담당자)만 본다. 학생과 구성원이 아닌 사람, 폐강한
 * 과목은 404다(SC-02). 항목은 화면이 보여 주는 이름, 학번, 수강 근거(학사 원장, 비정규, 청강)와 학습 열(최근 학습, 진도율)뿐이고 학과,
 * 학년, 연락처는 넣지 않는다. 명단은 {@link MembershipRule}이 STUDENT로 판정하는 사람이다(출석 명단, 학습 현황과 같은 사람).
 * <p>
 * 과목 하나의 명단은 수백 명이라 명단과 표시 정보를 한 번씩 읽고 서버에서 거르고 정렬해 쪽을 자른다. 학습 열은 자른 쪽의 학생만
 * {@link RosterLearning}(learning 구현)에 한 번 묻는다. 쿼리 수는 명단 크기와 관계없다.
 */
@RestController
class CourseRosterController {

	private static final int MAX_QUERY = 50;

	private static final int MAX_PAGE_SIZE = 100;

	private static final Map<String, Comparator<StudentItem>> SORTS = Map.of("name",
			by(StudentItem::name), "studentNumber", by(StudentItem::studentNumber));

	private static final Set<String> ORDERS = Set.of("asc", "desc");

	private final CourseMemberships memberships;

	private final CourseRosterQueries rosters;

	private final UserDirectory users;

	private final RosterLearning learning;

	CourseRosterController(CourseMemberships memberships, CourseRosterQueries rosters, UserDirectory users,
			RosterLearning learning) {
		this.memberships = memberships;
		this.rosters = rosters;
		this.users = users;
		this.learning = learning;
	}

	/**
	 * @param q 이름이나 학번에 들어 있는 글자(대소문자 무시)
	 * @param sort name(기본) 또는 studentNumber
	 * @param order asc(기본) 또는 desc. 같은 값이면 사용자 ID 순이다
	 */
	@GetMapping("/api/courses/{courseId}/students")
	ResponseEntity<StudentPage> students(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "name") String sort,
			@RequestParam(defaultValue = "asc") String order, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size) {
		if (this.memberships.roleOf(actor.userId(), courseId).filter((r) -> r == CourseRole.INSTRUCTOR).isEmpty()) {
			throw ApiException.notFound();
		}
		Comparator<StudentItem> comparator = SORTS.get(sort);
		if (comparator == null || !ORDERS.contains(order)) {
			throw ApiException.invalid("sort는 name 또는 studentNumber, order는 asc 또는 desc여야 합니다.");
		}
		if (page < 1 || page > 10_000 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		String needle = needle(q);
		List<Entry> entries = this.rosters.entries(courseId);
		Map<Long, UserCard> cards = this.users.find(entries.stream().map(Entry::userId).toList());
		Comparator<StudentItem> ordered = order.equals("asc") ? comparator : comparator.reversed();
		List<StudentItem> matched = entries.stream()
			.map((e) -> {
				UserCard card = cards.get(e.userId());
				return new StudentItem(e.userId(), card == null ? null : card.name(),
						card == null ? null : card.externalRef(), e.kind(), e.role(), null, null);
			})
			.filter((s) -> needle == null || contains(s.name(), needle) || contains(s.studentNumber(), needle))
			.sorted(ordered.thenComparingLong(StudentItem::studentId))
			.toList();
		int from = Math.min(matched.size(), (page - 1) * size);
		List<StudentItem> slice = matched.subList(from, Math.min(matched.size(), from + size));
		Map<Long, Learning> learned = this.learning.of(courseId, slice.stream().map(StudentItem::studentId).toList());
		List<StudentItem> items = slice.stream()
			.map((s) -> s.with(learned.getOrDefault(s.studentId(), Learning.NONE)))
			.toList();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new StudentPage(items, matched.size(), page, size));
	}

	private static Comparator<StudentItem> by(Function<StudentItem, String> key) {
		return Comparator.comparing(key, Comparator.nullsLast(Comparator.naturalOrder()));
	}

	private static String needle(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		String text = q.strip();
		if (text.length() > MAX_QUERY || text.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("검색어는 " + MAX_QUERY + "자 이하여야 합니다.");
		}
		return text.toLowerCase(Locale.ROOT);
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

	/**
	 * @param studentNumber 학번(학사 식별자)
	 * @param kind 수강 근거: REGISTRAR(학사 원장)|NONREGULAR|AUDIT
	 * @param role STUDENT|AUDITOR(청강생)
	 * @param lastAccessAt 그 과목에서 마지막으로 영상 학습을 시작한 일시. 없으면 null
	 * @param progressPercent 학생에게 열린 영상 콘텐츠 가운데 출석인정을 채운 비율(0~100). 열린 콘텐츠가 없으면 null
	 */
	record StudentItem(long studentId, String name, String studentNumber, String kind, String role,
			LocalDateTime lastAccessAt, Integer progressPercent) {

		StudentItem with(Learning learning) {
			return new StudentItem(this.studentId, this.name, this.studentNumber, this.kind, this.role,
					learning.lastAccessAt(), learning.progressPercent());
		}

	}

	/**
	 * @param total 검색어에 맞는 수강생 수
	 */
	record StudentPage(List<StudentItem> items, int total, int page, int size) {
	}

}
