package com.stackcorp.astra.communication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.communication.CommunityRepository.AudienceCount;
import com.stackcorp.astra.communication.CommunityRepository.InquiryCounts;
import com.stackcorp.astra.communication.CommunityRepository.InquiryRow;
import com.stackcorp.astra.communication.CommunityRepository.MaterialRow;
import com.stackcorp.astra.communication.CommunityRepository.ReplyRow;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.identity.api.UserRole;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림마당 자료실과 1:1 문의의 조회(EP-0151~0153, EP-0160~0162, 화면 31·34). 쓰기는 운영 화면과 함께 만든다.
 * <ul>
 * <li>자료실의 대상(공통, 교수, 학생)은 과목이 없으므로 session 사용자의 기관 역할로 고른다. 학생은 공통과 학생 대상, 교수·조교는 공통과 교수
 * 대상, 직원은 모두 본다. 필터(audience)는 그 안에서만 좁힌다(EP-0151 SCH_TARGET).</li>
 * <li>1:1 문의는 요청한 사람이 쓴 문의만 다룬다. 원본의 MY_QNA 같은 값은 받지 않는다(EP-0160, COMM-09). 남의 문의는 404다.</li>
 * </ul>
 * 첨부는 아직 돌려주지 않는다. 파일 내려받기 경로가 과목 단위({@code /api/courses/{courseId}/files/...})라 전역 게시판의 파일을 열 곳이 없다.
 */
@Service
class CommunityService {

	static final int MAX_PAGE_SIZE = 50;

	private static final int MAX_REPLIES = 100;

	private static final Set<String> COMMON = Set.of("ALL", "COMMON");

	private final CommunityRepository community;

	private final PostReactions reactions;

	private final UserNames userNames;

	CommunityService(CommunityRepository community, PostReactions reactions, UserNames userNames) {
		this.community = community;
		this.reactions = reactions;
		this.userNames = userNames;
	}

	/**
	 * 자료 목록. 최근 자료 먼저다.
	 * @param audience all, common, professor, student
	 */
	@Transactional(readOnly = true)
	MaterialPage materials(Actor actor, String audience, String query, Long cursor, int size) {
		Set<String> allowed = allowed(actor.role());
		Set<String> selected = selected(allowed, audience);
		String title = PlainText.query(query);
		requirePage(cursor, size);
		long common = 0;
		long professor = 0;
		long student = 0;
		for (AudienceCount count : this.community.materialCounts(allowed, title)) {
			switch (count.audience()) {
				case "PROFESSOR" -> professor += count.count();
				case "STUDENT" -> student += count.count();
				default -> common += count.count();
			}
		}
		List<MaterialRow> rows = selected.isEmpty() ? List.of()
				: this.community.materials(actor.userId(), selected, title, cursor, size + 1);
		Long next = rows.size() > size ? rows.get(size - 1).postId() : null;
		return new MaterialPage(rows.subList(0, Math.min(size, rows.size())).stream().map(MaterialItem::of).toList(),
				new MaterialCounts(common + professor + student, common, professor, student), next);
	}

	@Transactional(readOnly = true)
	MaterialView material(Actor actor, long postId) {
		return MaterialView.of(this.community.material(actor.userId(), allowed(actor.role()), postId)
			.orElseThrow(ApiException::notFound));
	}

	/** 읽음으로 표시한다. 몇 번 보내도 한 행이고 조회수는 처음 한 번만 오른다(EP-0153). */
	@Transactional
	BoardPostService.ReadState readMaterial(Actor actor, long postId) {
		int views = this.community.lockMaterial(allowed(actor.role()), postId).orElseThrow(ApiException::notFound);
		return new BoardPostService.ReadState(postId, true,
				views + (this.reactions.markRead(postId, actor.userId()) ? 1 : 0));
	}

	/**
	 * 본인의 1:1 문의 목록. 최근 문의 먼저다.
	 * @param status all, answered, unanswered
	 */
	@Transactional(readOnly = true)
	InquiryPage inquiries(Actor actor, String status, String query, Long cursor, int size) {
		Boolean answered = switch (status == null || status.isBlank() ? "all" : status) {
			case "all" -> null;
			case "answered" -> true;
			case "unanswered" -> false;
			default -> throw ApiException.invalid("status는 all, answered, unanswered 중 하나여야 합니다.");
		};
		String title = PlainText.query(query);
		requirePage(cursor, size);
		InquiryCounts counts = this.community.inquiryCounts(actor.userId(), title);
		List<InquiryRow> rows = this.community.inquiries(actor.userId(), answered, title, cursor, size + 1);
		Long next = rows.size() > size ? rows.get(size - 1).postId() : null;
		return new InquiryPage(rows.subList(0, Math.min(size, rows.size())).stream().map(InquiryItem::of).toList(),
				counts, next);
	}

	@Transactional(readOnly = true)
	InquiryView inquiry(Actor actor, long postId) {
		InquiryRow row = this.community.inquiry(actor.userId(), postId).orElseThrow(ApiException::notFound);
		List<ReplyRow> replies = this.community.replies(postId, MAX_REPLIES);
		Map<Long, String> names = this.userNames.namesOf(replies.stream().map(ReplyRow::authorId).distinct().toList());
		return new InquiryView(row.postId(), row.title(), row.body(), row.answered(), row.answeredAt(), row.createdAt(),
				replies.stream()
					.map((r) -> new ReplyView(r.replyId(), names.get(r.authorId()), r.body(), r.createdAt()))
					.toList());
	}

	private static Set<String> allowed(UserRole role) {
		return switch (role) {
			case STUDENT -> Set.of("ALL", "COMMON", "STUDENT");
			case PROFESSOR, TA -> Set.of("ALL", "COMMON", "PROFESSOR");
			case STAFF -> Set.of("ALL", "COMMON", "PROFESSOR", "STUDENT");
		};
	}

	private static Set<String> selected(Set<String> allowed, String audience) {
		Set<String> wanted = switch (audience == null || audience.isBlank() ? "all" : audience) {
			case "all" -> allowed;
			case "common" -> COMMON;
			case "professor" -> Set.of("PROFESSOR");
			case "student" -> Set.of("STUDENT");
			default -> throw ApiException.invalid("audience는 all, common, professor, student 중 하나여야 합니다.");
		};
		return wanted.stream().filter(allowed::contains).collect(Collectors.toUnmodifiableSet());
	}

	private static void requirePage(Long cursor, int size) {
		if (size < 1 || size > MAX_PAGE_SIZE || (cursor != null && cursor < 1)) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하, cursor는 1 이상이어야 합니다.");
		}
	}

	private static String audienceName(String stored) {
		return COMMON.contains(stored) ? "common" : stored.toLowerCase(Locale.ROOT);
	}

	/** @param audience common, professor, student */
	record MaterialItem(long postId, String title, String excerpt, String audience, int viewCount,
			LocalDateTime publishedAt, boolean read) {

		static MaterialItem of(MaterialRow row) {
			return new MaterialItem(row.postId(), row.title(), row.excerpt(), audienceName(row.audience()),
					row.viewCount(), row.publishedAt(), row.read());
		}

	}

	record MaterialView(long postId, String title, String body, String audience, int viewCount,
			LocalDateTime publishedAt, LocalDateTime updatedAt, boolean read) {

		static MaterialView of(MaterialRow row) {
			return new MaterialView(row.postId(), row.title(), row.body(), audienceName(row.audience()),
					row.viewCount(), row.publishedAt(), row.updatedAt(), row.read());
		}

	}

	/** 요청한 사람이 볼 수 있는 대상만 센다. 볼 수 없는 대상은 0이다. */
	record MaterialCounts(long total, long common, long professor, long student) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MaterialPage(List<MaterialItem> items, MaterialCounts counts, Long nextCursor) {
	}

	record InquiryItem(long postId, String title, boolean answered, LocalDateTime answeredAt, int answerCount,
			LocalDateTime createdAt) {

		static InquiryItem of(InquiryRow row) {
			return new InquiryItem(row.postId(), row.title(), row.answered(), row.answeredAt(), row.answerCount(),
					row.createdAt());
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InquiryPage(List<InquiryItem> items, InquiryCounts counts, Long nextCursor) {
	}

	record InquiryView(long postId, String title, String body, boolean answered, LocalDateTime answeredAt,
			LocalDateTime createdAt, List<ReplyView> replies) {
	}

	record ReplyView(long replyId, String authorName, String body, LocalDateTime createdAt) {
	}

}
