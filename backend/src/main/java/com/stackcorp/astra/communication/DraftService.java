package com.stackcorp.astra.communication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.communication.DraftRepository.DraftRow;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * 자동저장(EP-0115, EP-0116, EP-0117의 대체 계약). 본인 초안만 읽고 쓴다. 부르는 쪽이 {@link DraftKey}의 과목·게시판 권한을 먼저
 * 판정한다.
 * <p>
 * 마지막 쓰기가 이기되, 순서는 클라이언트가 보내는 revision으로 정한다. 저장된 값보다 큰 revision만 덮어쓰므로 늦게 도착한 이전 요청이
 * 새 초안을 덮지 못한다. 같은 revision을 같은 내용으로 다시 보내면 성공으로 답해 저장 실패 뒤의 재전송이 통한다(SEC-03). 같은
 * revision에 다른 내용이나 더 작은 revision은 409 {@code draft_stale}이다. 두 탭이 같은 초안을 고칠 때 뒤처진 탭이 이 응답을 받는다
 * (COMM-08, T-17).
 */
@Service
class DraftService {

	static final int MAX_TITLE = 300;

	static final int MAX_BODY = 20_000;

	static final int MAX_RECIPIENTS = MessageService.MAX_RECIPIENTS;

	static final long MAX_REVISION = 4_294_967_295L;

	private final DraftRepository drafts;

	private final RateLimiter rateLimiter;

	private final JsonMapper json;

	private final ReadCommitted readCommitted;

	DraftService(DraftRepository drafts, RateLimiter rateLimiter, JsonMapper json, ReadCommitted readCommitted) {
		this.drafts = drafts;
		this.rateLimiter = rateLimiter;
		this.json = json;
		this.readCommitted = readCommitted;
	}

	@Transactional(readOnly = true)
	Optional<Draft> get(long userId, DraftKey key) {
		return this.drafts.find(userId, key).map((row) -> {
			Contents contents = this.json.readValue(row.contents(), Contents.class);
			return new Draft(row.revision(), contents.title(), contents.body(), contents.courseId(),
					contents.recipientIds() == null ? List.of() : contents.recipientIds(), row.updatedAt());
		});
	}

	/**
	 * 부르는 쪽이 courseId의 권한을 먼저 판정한다. recipientIds는 모양(개수, 중복, 양수)만 본다. 받을 수 있는 사람인지는 초안을 읽을
	 * 때와 보낼 때 다시 판정한다.
	 */
	Saved save(long userId, DraftKey key, DraftRequest body) {
		if (body.revision() == null || body.revision() < 1 || body.revision() > MAX_REVISION) {
			throw ApiException.invalid("revision은 1 이상 " + MAX_REVISION + " 이하의 정수여야 합니다.");
		}
		List<Long> recipients = body.recipientIds() == null ? null : recipientIds(body.recipientIds());
		Contents contents = new Contents(PlainText.partialLine(body.title(), MAX_TITLE, "제목"),
				PlainText.block(body.body(), MAX_BODY, "본문"), body.courseId(), recipients);
		// 2초 debounce로 저장하는 화면이 10분 동안 쉬지 않고 쓸 수 있는 횟수다.
		this.rateLimiter.check("draft-save", userId, 300, Duration.ofMinutes(10));
		String serialized = this.json.writeValueAsString(contents);
		String hash = sha256(serialized);
		// upsert가 행을 잠그므로 commit 전에 다시 읽은 값이 이 요청의 결과다.
		DraftRow stored = this.readCommitted.execute(() -> {
			this.drafts.upsert(userId, key, body.revision(), serialized, hash);
			return this.drafts.find(userId, key).orElseThrow();
		});
		if (stored.revision() != body.revision() || !stored.hash().equals(hash)) {
			throw new ApiException(HttpStatus.CONFLICT, "draft_stale",
					"더 새로운 임시저장본이 있습니다. 임시저장본을 다시 불러온 뒤 저장해 주세요.");
		}
		return new Saved(stored.revision(), stored.updatedAt());
	}

	/** 없는 초안을 지워도 성공이다. */
	@Transactional
	void delete(long userId, DraftKey key) {
		this.drafts.delete(userId, key);
	}

	private static List<Long> recipientIds(List<Long> ids) {
		if (ids.size() > MAX_RECIPIENTS || ids.stream().anyMatch((id) -> id == null || id < 1)
				|| new LinkedHashSet<>(ids).size() != ids.size()) {
			throw ApiException.invalid("recipientIds는 " + MAX_RECIPIENTS + "명 이하의 서로 다른 ID여야 합니다.");
		}
		return List.copyOf(ids);
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * @param revision 클라이언트가 정하는 순번. 이 초안에 저장된 값보다 커야 한다. 조회 응답의 revision에서 이어 센다
	 * @param courseId 쪽지 초안만. 고른 과목. 없으면 null
	 * @param recipientIds 쪽지 초안만. 고른 받는사람. 없으면 null
	 */
	record DraftRequest(Long revision, String title, String body, Long courseId, List<Long> recipientIds) {
	}

	/** 저장된 초안 그대로. 받는사람과 과목은 아직 판정하지 않은 값이다. */
	record Draft(long revision, String title, String body, Long courseId, List<Long> recipientIds,
			LocalDateTime updatedAt) {
	}

	record Saved(long revision, LocalDateTime updatedAt) {
	}

	/** post_drafts.contents에 저장하는 JSON. 쪽지 초안만 courseId와 recipientIds를 가진다. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Contents(String title, String body, Long courseId, List<Long> recipientIds) {
	}

}
