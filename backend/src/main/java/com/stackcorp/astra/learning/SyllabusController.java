package com.stackcorp.astra.learning;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.CourseProfiles;
import com.stackcorp.astra.catalog.api.CourseProfiles.CourseProfile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.learning.SyllabusService.Card;
import com.stackcorp.astra.learning.SyllabusService.Content;
import com.stackcorp.astra.learning.SyllabusService.SyllabusRow;
import com.stackcorp.astra.learning.SyllabusService.WeekPlan;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의계획서 팝업과 정규과목 상세의 계획서(EP-0218, EP-0219의 대체 계약, 화면 41·22).
 * <ul>
 * <li>과목 구성원은 계획서 전체와 담당자의 연락처(이메일, 연락처, 연구실, 면담시간, 홈페이지)를 본다.</li>
 * <li>구성원이 아닌 로그인 사용자는 과목 목록에 나오는 정규과목에 한해 공개 항목만 본다. 과목 기본 정보, 담당자 이름과 소속, 서술 항목,
 * 주별 계획이다. 담당자의 연락처와 version은 응답에 없다(EP-0218 개인정보 노출 범위).</li>
 * <li>담당자는 서술 항목과 주별 계획, 자기 강사 정보를 고친다. 과목명·학점 같은 학사 값은 catalog의 값이고 본문으로 받지 않는다(400).
 * 모두 일반 텍스트이고 HTML로 해석하지 않는다.</li>
 * </ul>
 */
@RestController
class SyllabusController {

	private static final int MAX_TEXT = 4000;

	private static final int MAX_WEEKS = 16;

	private static final Set<String> OUTCOME_LEVELS = Set.of("L1", "L2", "L3");

	private static final Pattern EMAIL = Pattern.compile("[^\\s@]{1,64}@[^\\s@]{1,190}");

	private static final Pattern HOMEPAGE = Pattern.compile("https?://\\S{1,290}");

	private final LearningAccess access;

	private final CourseProfiles profiles;

	private final SyllabusService service;

	private final UserNames names;

	private final RateLimiter rateLimiter;

	SyllabusController(LearningAccess access, CourseProfiles profiles, SyllabusService service, UserNames names,
			RateLimiter rateLimiter) {
		this.access = access;
		this.profiles = profiles;
		this.service = service;
		this.names = names;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/api/courses/{courseId}/syllabus")
	ResponseEntity<?> syllabus(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		Optional<CourseRole> role = this.access.roleIn(actor.userId(), courseId);
		CourseProfile profile = this.profiles.find(courseId)
			.filter((p) -> role.isPresent() || p.regular())
			.orElseThrow(ApiException::notFound);
		if (role.isPresent()) {
			SyllabusView view = memberView(profile);
			return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.eTag(Versions.etag(view.version()))
				.body(view);
		}
		Optional<SyllabusRow> row = this.service.find(courseId);
		Map<Long, Card> cards = row.map((r) -> this.service.cards(r.syllabusId())).orElse(Map.of());
		List<WeekPlan> weeks = row.map((r) -> this.service.weeks(r.syllabusId())).orElse(List.of());
		Map<Long, String> instructorNames = this.names.namesOf(profile.instructorIds());
		SyllabusRow content = row.orElse(null);
		List<PublicInstructor> instructors = profile.instructorIds()
			.stream()
			.map((id) -> new PublicInstructor(instructorNames.get(id),
					cards.containsKey(id) ? cards.get(id).department() : null))
			.toList();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new PublicSyllabusView(profile.courseId(), profile.name(), profile.code(), profile.section(),
					profile.termName(), profile.completionType(), profile.credit(),
					content == null ? null : content.classTimeRoom(), content == null ? null : content.overview(),
					content == null ? null : content.objectives(), content == null ? null : content.outcomeRelevance(),
					content == null ? null : content.textbooks(), content == null ? null : content.equipment(),
					content == null ? null : content.gradingPolicy(), instructors, weeks));
	}

	/**
	 * 계획서의 서술 항목과 주별 계획을 통째로 바꾼다. 빠진 항목은 비운다는 뜻이다. 주별 계획은 1~{@value #MAX_WEEKS}주이고 본문에 없는
	 * 주는 지운다. If-Match에 읽은 version(저장한 적이 없으면 0)을 보낸다. 다른 수정 명령처럼 바뀐 계획서 전체(GET과 같은 모양)를
	 * 돌려주고 ETag가 새 version이다.
	 */
	@PutMapping("/api/courses/{courseId}/syllabus")
	ResponseEntity<SyllabusView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody SyllabusBody body) {
		requireInstructor(actor, courseId);
		int expected = Versions.expected(ifMatch);
		if (body.outcomeRelevance() != null && !OUTCOME_LEVELS.contains(body.outcomeRelevance())) {
			throw ApiException.invalid("학습성과관련도는 L1, L2, L3 중 하나여야 합니다.");
		}
		Content content = new Content(LearningText.optional(body.classTimeRoom(), "강의시간/강의실", 200, false),
				text(body.overview(), "과목개요"), text(body.objectives(), "교육목표"), body.outcomeRelevance(),
				text(body.textbooks(), "교재 및 참고서적"), text(body.equipment(), "활용 기자재"),
				text(body.gradingPolicy(), "성적평가방법"));
		List<WeekPlan> weeks = weeks(body.weeks());
		this.rateLimiter.check("syllabus-write", actor.userId(), 60, Duration.ofMinutes(10));
		this.service.update(actor, courseId, expected, content, weeks);
		SyllabusView view = memberView(this.profiles.find(courseId).orElseThrow(ApiException::notFound));
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(view.version()))
			.body(view);
	}

	/**
	 * 요청한 담당자 자신의 강사 정보. 다른 담당자의 정보는 고치지 못한다. If-Match는 강사 정보의 version(처음이면 0)이다. 바뀐 자기 강사
	 * 정보(GET의 instructors 항목과 같은 모양)를 돌려주고 ETag가 새 version이다.
	 * <p>
	 * 계획서의 강사소개에는 과목의 담당자 명단(catalog의 course_instructors, 대표 담당 먼저)만 나온다(화면 41). 조교나 교수로 수강만
	 * 확정된 사람도 과목의 INSTRUCTOR라 서술 항목은 고치지만, 명단에 없으므로 강사 정보를 쓰지 못한다. 써도 어디에도 보이지 않기
	 * 때문이다. 이때는 409 {@code not_listed_instructor}다.
	 */
	@PutMapping("/api/courses/{courseId}/syllabus/instructors/me")
	ResponseEntity<InstructorView> updateMine(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody InstructorBody body) {
		requireInstructor(actor, courseId);
		List<Long> instructors = this.profiles.find(courseId)
			.map(CourseProfile::instructorIds)
			.orElseThrow(ApiException::notFound);
		int order = instructors.indexOf(actor.userId());
		if (order < 0) {
			throw new ApiException(HttpStatus.CONFLICT, "not_listed_instructor",
					"강의계획서의 담당자 명단에 없어 강사 정보를 쓸 수 없습니다.");
		}
		int expected = Versions.expected(ifMatch);
		String email = LearningText.optional(body.email(), "이메일", 200, false);
		if (email != null && !EMAIL.matcher(email).matches()) {
			throw ApiException.invalid("이메일 형식이 올바르지 않습니다.");
		}
		String homepage = LearningText.optional(body.homepage(), "홈페이지", 300, false);
		if (homepage != null && !HOMEPAGE.matcher(homepage).matches()) {
			throw ApiException.invalid("홈페이지는 http:// 또는 https://로 시작하는 주소여야 합니다.");
		}
		Card card = new Card(LearningText.optional(body.department(), "소속", 100, false), email,
				LearningText.optional(body.officeHours(), "면담시간", 200, false),
				LearningText.optional(body.phone(), "연락처", 50, false),
				LearningText.optional(body.office(), "연구실", 200, false), homepage, 0);
		this.rateLimiter.check("syllabus-write", actor.userId(), 60, Duration.ofMinutes(10));
		int version = this.service.updateCard(actor, courseId, expected, card, order);
		String name = this.names.namesOf(List.of(actor.userId())).get(actor.userId());
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(version))
			.body(InstructorView.of(actor.userId(), name, card, version));
	}

	/** 구성원에게 보이는 계획서. 강사소개는 과목의 담당자 명단 순서다. */
	private SyllabusView memberView(CourseProfile profile) {
		Optional<SyllabusRow> row = this.service.find(profile.courseId());
		Map<Long, Card> cards = row.map((r) -> this.service.cards(r.syllabusId())).orElse(Map.of());
		List<WeekPlan> weeks = row.map((r) -> this.service.weeks(r.syllabusId())).orElse(List.of());
		Map<Long, String> instructorNames = this.names.namesOf(profile.instructorIds());
		SyllabusRow content = row.orElse(null);
		List<InstructorView> instructors = profile.instructorIds().stream().map((id) -> {
			Card card = cards.get(id);
			return card == null ? new InstructorView(id, instructorNames.get(id), null, null, null, null, null, null, 0)
					: InstructorView.of(id, instructorNames.get(id), card, card.version());
		}).toList();
		return new SyllabusView(profile.courseId(), profile.name(), profile.code(), profile.section(),
				profile.termName(), profile.completionType(), profile.credit(),
				content == null ? null : content.classTimeRoom(), content == null ? null : content.overview(),
				content == null ? null : content.objectives(), content == null ? null : content.outcomeRelevance(),
				content == null ? null : content.textbooks(), content == null ? null : content.equipment(),
				content == null ? null : content.gradingPolicy(), instructors, weeks,
				content == null ? 0 : content.version());
	}

	private void requireInstructor(Actor actor, long courseId) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
	}

	private static String text(String value, String field) {
		return LearningText.optional(value, field, MAX_TEXT, true);
	}

	private static List<WeekPlan> weeks(List<WeekBody> body) {
		List<WeekBody> list = body == null ? List.of() : body;
		if (list.size() > MAX_WEEKS) {
			throw ApiException.invalid("주별 계획은 " + MAX_WEEKS + "주까지입니다.");
		}
		Set<Integer> seen = new HashSet<>();
		List<WeekPlan> weeks = new ArrayList<>();
		for (WeekBody week : list) {
			if (week == null || week.weekNo() == null || week.weekNo() < 1 || week.weekNo() > MAX_WEEKS
					|| !seen.add(week.weekNo())) {
				throw ApiException.invalid("주별 계획의 주차는 1주 이상 " + MAX_WEEKS + "주 이하이고 겹치지 않아야 합니다.");
			}
			weeks.add(new WeekPlan(week.weekNo(), LearningText.optional(week.content(), "주별 계획 내용", 2000, true),
					LearningText.optional(week.note(), "주별 계획 비고", 500, false)));
		}
		return weeks;
	}

	/** 요청 본문. 과목 기본 정보(과목명, 학수번호, 학기, 이수구분, 학점)는 받지 않는다. */
	record SyllabusBody(String classTimeRoom, String overview, String objectives, String outcomeRelevance,
			String textbooks, String equipment, String gradingPolicy, List<WeekBody> weeks) {
	}

	record WeekBody(Integer weekNo, String content, String note) {
	}

	record InstructorBody(String department, String email, String officeHours, String phone, String office,
			String homepage) {
	}

	/**
	 * 구성원에게 보이는 계획서.
	 * @param version 계획서를 고칠 때 If-Match로 보내는 값. 저장한 적이 없으면 0
	 */
	record SyllabusView(long courseId, String courseName, String courseCode, String section, String termName,
			String completionType, BigDecimal credit, String classTimeRoom, String overview, String objectives,
			String outcomeRelevance, String textbooks, String equipment, String gradingPolicy,
			List<InstructorView> instructors, List<WeekPlan> weeks, int version) {
	}

	/**
	 * @param version 자기 강사 정보를 고칠 때 If-Match로 보내는 값. 없으면 0
	 */
	record InstructorView(long userId, String name, String department, String email, String officeHours, String phone,
			String office, String homepage, int version) {

		static InstructorView of(long userId, String name, Card card, int version) {
			return new InstructorView(userId, name, card.department(), card.email(), card.officeHours(), card.phone(),
					card.office(), card.homepage(), version);
		}

	}

	/** 구성원이 아닌 사람에게 보이는 계획서. 연락처와 version이 없다. */
	record PublicSyllabusView(long courseId, String courseName, String courseCode, String section, String termName,
			String completionType, BigDecimal credit, String classTimeRoom, String overview, String objectives,
			String outcomeRelevance, String textbooks, String equipment, String gradingPolicy,
			List<PublicInstructor> instructors, List<WeekPlan> weeks) {
	}

	record PublicInstructor(String name, String department) {
	}

}
