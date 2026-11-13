package com.stackcorp.astra.learning;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.files.api.AvatarPhotos;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserDirectory;
import com.stackcorp.astra.identity.api.UserDirectory.UserCard;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.learning.AttendanceCodes.GroupType;
import com.stackcorp.astra.learning.CourseGroupService.GroupRow;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 강의실 그룹활동(EP-0039, EP-0040의 대체 계약). 담당자는 과목의 그룹과 구성원을 관리하고 모든 그룹을 본다. 학생은 자기가 속한 그룹과
 * 그 그룹 구성원의 이름만 본다. 다른 그룹의 구성원, 학번, 사용자 ID는 학생에게 보이지 않는다. 이름과 설명은 일반 텍스트다.
 */
@RestController
class CourseGroupController {

	private static final int MAX_NAME = 100;

	private static final int MAX_DESCRIPTION = 1000;

	private final LearningAccess access;

	private final CourseGroupService service;

	private final UserDirectory directory;

	private final UserNames names;

	private final AvatarPhotos photos;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	CourseGroupController(LearningAccess access, CourseGroupService service, UserDirectory directory, UserNames names,
			AvatarPhotos photos, RateLimiter rateLimiter, Idempotency idempotency) {
		this.access = access;
		this.service = service;
		this.directory = directory;
		this.names = names;
		this.photos = photos;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
	}

	@GetMapping("/api/courses/{courseId}/groups")
	ResponseEntity<List<GroupView>> groups(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		boolean instructor = role == CourseRole.INSTRUCTOR;
		List<GroupRow> groups = this.service.groups(courseId,
				instructor ? null : this.service.groupsOf(courseId, actor.userId()));
		Map<Long, List<Long>> members = this.service.members(groups.stream().map(GroupRow::groupId).toList());
		Set<Long> userIds = members.values().stream().flatMap(List::stream).collect(Collectors.toSet());
		List<GroupView> body;
		if (instructor) {
			Map<Long, UserCard> cards = this.directory.find(userIds);
			Set<Long> withPhoto = this.photos.visibleTo(actor.userId(), userIds);
			body = groups.stream()
				.map((g) -> view(g, members.getOrDefault(g.groupId(), List.of())
					.stream()
					.map((id) -> MemberView.managed(id, cards.get(id), withPhoto))
					.toList()))
				.toList();
		}
		else {
			Map<Long, String> byId = this.names.namesOf(userIds);
			body = groups.stream()
				.map((g) -> view(g, members.getOrDefault(g.groupId(), List.of())
					.stream()
					.map((id) -> new MemberView(null, byId.get(id), null, false))
					.toList()))
				.toList();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 그룹을 만든다. {@code type}은 activity(그룹활동 팀) 또는 section(출석 분반)이다. {@code Idempotency-Key}를 받는다. */
	@PostMapping("/api/courses/{courseId}/groups")
	ResponseEntity<GroupView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody CreateGroup body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		requireInstructor(actor, courseId);
		String name = LearningText.required(body.name(), "그룹 이름", MAX_NAME);
		String description = LearningText.optional(body.description(), "그룹 설명", MAX_DESCRIPTION, true);
		GroupType type = GroupType.of(body.type() == null ? GroupType.ACTIVITY.code : body.type())
			.orElseThrow(() -> ApiException.invalid("type은 activity 또는 section이어야 합니다."));
		this.rateLimiter.check("course-group-write", actor.userId(), 300, Duration.ofMinutes(10));
		GroupView created = this.idempotency.execute(actor.userId(), "course_group.create", idempotencyKey,
				List.of(courseId, name, type, description == null ? "" : description), GroupView.class, () -> {
					long id = this.service.create(actor, courseId, name, type, description);
					return one(actor.userId(), courseId, id);
				});
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(created.version()))
			.body(created);
	}

	/** 이름과 설명을 바꾼다. If-Match 필수. */
	@PutMapping("/api/courses/{courseId}/groups/{groupId}")
	ResponseEntity<GroupView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long groupId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody UpdateGroup body) {
		requireInstructor(actor, courseId);
		int version = Versions.expected(ifMatch);
		String name = LearningText.required(body.name(), "그룹 이름", MAX_NAME);
		String description = LearningText.optional(body.description(), "그룹 설명", MAX_DESCRIPTION, true);
		this.rateLimiter.check("course-group-write", actor.userId(), 300, Duration.ofMinutes(10));
		this.service.rename(actor, courseId, groupId, version, name, description);
		GroupView updated = one(actor.userId(), courseId, groupId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(updated.version()))
			.body(updated);
	}

	/** 그룹을 지운다. 구성원 관계도 함께 지운다. 출석에 쓴 그룹은 409다. If-Match 필수. */
	@DeleteMapping("/api/courses/{courseId}/groups/{groupId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long groupId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		requireInstructor(actor, courseId);
		int version = Versions.expected(ifMatch);
		this.rateLimiter.check("course-group-write", actor.userId(), 300, Duration.ofMinutes(10));
		this.service.delete(actor, courseId, groupId, version);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 수강생을 구성원으로 넣는다. 이미 구성원이면 그대로다. */
	@PutMapping("/api/courses/{courseId}/groups/{groupId}/members/{studentId}")
	ResponseEntity<GroupView> addMember(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long groupId, @PathVariable long studentId) {
		requireInstructor(actor, courseId);
		this.rateLimiter.check("course-group-write", actor.userId(), 300, Duration.ofMinutes(10));
		this.service.add(actor, courseId, groupId, studentId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(one(actor.userId(), courseId, groupId));
	}

	/** 구성원에서 뺀다. 구성원이 아니어도 204다. */
	@DeleteMapping("/api/courses/{courseId}/groups/{groupId}/members/{studentId}")
	ResponseEntity<Void> removeMember(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long groupId, @PathVariable long studentId) {
		requireInstructor(actor, courseId);
		this.rateLimiter.check("course-group-write", actor.userId(), 300, Duration.ofMinutes(10));
		this.service.remove(actor, courseId, groupId, studentId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 구성원을 같은 과목의 다른 그룹으로 옮긴다. 한 transaction이다. 응답은 옮겨 간 그룹이다. */
	@PostMapping("/api/courses/{courseId}/groups/{groupId}/members/{studentId}/move")
	ResponseEntity<GroupView> moveMember(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long groupId, @PathVariable long studentId, @RequestBody MoveMember body) {
		requireInstructor(actor, courseId);
		if (body.toGroupId() == null || body.toGroupId() < 1) {
			throw ApiException.invalid("toGroupId가 필요합니다.");
		}
		this.rateLimiter.check("course-group-write", actor.userId(), 300, Duration.ofMinutes(10));
		this.service.move(actor, courseId, groupId, body.toGroupId(), studentId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(one(actor.userId(), courseId, body.toGroupId()));
	}

	// 담당자에게 보이는 그룹 하나.
	private GroupView one(long viewerId, long courseId, long groupId) {
		GroupRow group = this.service.groups(courseId, List.of(groupId))
			.stream()
			.findFirst()
			.orElseThrow(ApiException::notFound);
		List<Long> members = this.service.members(List.of(groupId)).getOrDefault(groupId, List.of());
		Map<Long, UserCard> cards = this.directory.find(members);
		Set<Long> withPhoto = this.photos.visibleTo(viewerId, members);
		return view(group, members.stream().map((id) -> MemberView.managed(id, cards.get(id), withPhoto)).toList());
	}

	private void requireInstructor(Actor actor, long courseId) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
	}

	private static GroupView view(GroupRow group, List<MemberView> members) {
		return new GroupView(group.groupId(), group.name(), GroupType.code(group.type()), group.description(), members,
				group.version());
	}

	/** 요청 본문. 만든 사람과 과목은 session과 경로에서 정한다. */
	record CreateGroup(String name, String type, String description) {
	}

	record UpdateGroup(String name, String description) {
	}

	record MoveMember(Long toGroupId) {
	}

	/**
	 * @param type activity|section
	 * @param version 이름 바꾸기·삭제 때 If-Match로 보내는 값
	 */
	record GroupView(long groupId, String name, String type, String description, List<MemberView> members,
			int version) {
	}

	/**
	 * @param userId 담당자에게만 보인다
	 * @param studentNo 학번. 담당자에게만 보인다
	 * @param hasPhoto 요청한 사람이 {@code GET /api/users/{userId}/avatar}로 사진을 받을 수 있으면 true(ADR-0038). userId가 없는
	 * 학생 응답에서는 언제나 false다
	 */
	record MemberView(Long userId, String name, String studentNo, boolean hasPhoto) {

		static MemberView managed(long userId, UserCard card, Set<Long> withPhoto) {
			return new MemberView(userId, card == null ? null : card.name(), card == null ? null : card.externalRef(),
					withPhoto.contains(userId));
		}

	}

}
