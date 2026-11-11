package com.stackcorp.astra.identity.account;

import java.text.BreakIterator;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.account.AccountProfiles.Profile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.RecentAuthentication;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * 내정보와 아바타(EP-0270, EP-0271, EP-0272, EP-0278, EP-0265, EP-0266의 대체 계약). 대상은 언제나 요청한 사용자다.
 * <ul>
 * <li>바꿀 수 있는 것은 아바타 색상과 약칭, 학사 원천이 아닌 연락처뿐이다. 이름, 소속, 학년은 학사 원천이라 받지 않는다(문서에 없는
 * 필드라 400).</li>
 * <li>아바타 색상은 24색 allowlist, 약칭은 글자(grapheme) 2개 이하이고 제어 문자, 공백, HTML 특수 문자를 받지 않는다
 * (IDENTITY-10). 사진 업로드는 아직 없다.</li>
 * <li>연락처 변경은 2차 인증 수단의 대상을 바꾸므로 최근 재인증을 요구한다(IDENTITY-06, ADR-0029).</li>
 * </ul>
 */
@RestController
class ProfileController {

	/** V1 user_profiles.avatar_color 주석의 24색. 원본 아바타 팔레트와 같다. */
	static final Set<String> AVATAR_COLORS = Set.of("tomato", "tangerine", "pumpkin", "mango", "banana", "chick",
			"avocado", "pistachio", "basil", "eucalyptus", "sage", "peacock", "cobalt", "blueberry", "lavender",
			"light-purple", "amethyst", "grape", "purple", "cherry-blossoms", "light-pink", "cocoa", "graphite",
			"birch-tree");

	/** 연락처 변경 전 재인증이 이보다 오래되면 다시 요구한다. */
	static final Duration CONTACT_STEP_UP = Duration.ofMinutes(10);

	private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9-]{6,18}[0-9]");

	private static final Pattern EMAIL = Pattern
		.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+");

	private static final int MAX_EMAIL_LENGTH = 254;

	private final AccountProfiles profiles;

	private final RecentAuthentication recentAuthentication;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	ProfileController(AccountProfiles profiles, RecentAuthentication recentAuthentication, RateLimiter rateLimiter,
			AuditLog audit) {
		this.profiles = profiles;
		this.recentAuthentication = recentAuthentication;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
	}

	/** 내정보(EP-0270). 연락처는 본인에게 원본 화면처럼 그대로 보인다. */
	@GetMapping("/api/me/profile")
	ResponseEntity<Profile> profile(@AuthenticationPrincipal Actor actor) {
		return ok(this.profiles.find(actor.userId()));
	}

	/** 아바타 색상과 약칭 저장(EP-0271, EP-0278). 본문의 {@code version}이 저장된 값과 다르면 409 {@code version_conflict}. */
	@PutMapping("/api/me/profile")
	ResponseEntity<Profile> update(@AuthenticationPrincipal Actor actor, @RequestBody UpdateProfile body) {
		if (body.avatarColor() == null || !AVATAR_COLORS.contains(body.avatarColor())) {
			throw ApiException.invalid("avatarColor는 정해진 색상 중 하나여야 합니다.");
		}
		if (body.version() == null || body.version() < 0) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		String shortName = shortName(body.shortName());
		this.rateLimiter.check("profile-update", actor.userId(), 30, Duration.ofMinutes(10));
		this.profiles.updateAvatar(actor.userId(), body.avatarColor(), shortName, body.version());
		this.audit.success(actor.userId(), actor.sessionId(), "account.profile_update", "user_profile",
				actor.userId(), null);
		return ok(this.profiles.find(actor.userId()));
	}

	/**
	 * 연락처 변경(EP-0265, EP-0266). 최근 재인증이 없으면 403 {@code step_up_required}, 학사 원천 연락처면 409
	 * {@code contact_managed_by_registrar}.
	 */
	@PutMapping("/api/me/contact")
	ResponseEntity<Profile> contact(@AuthenticationPrincipal Actor actor, @RequestBody UpdateContact body) {
		String phone = body.phone() == null ? null : body.phone().strip();
		String email = body.email() == null ? null : body.email().strip();
		if (phone == null || !PHONE.matcher(phone).matches()) {
			throw ApiException.invalid("전화번호 형식이 올바르지 않습니다.");
		}
		if (email == null || email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
			throw ApiException.invalid("이메일 형식이 올바르지 않습니다.");
		}
		this.recentAuthentication.requireRecentAuth(actor, CONTACT_STEP_UP);
		this.rateLimiter.check("contact-update", actor.userId(), 10, Duration.ofMinutes(10));
		this.profiles.updateContact(actor.userId(), phone, email);
		this.audit.success(actor.userId(), actor.sessionId(), "account.contact_update", "user_contact", actor.userId(),
				null);
		return ok(this.profiles.find(actor.userId()));
	}

	/** 약칭 규칙(IDENTITY-10). 비우면 null로 저장하고 화면이 이름에서 만든다. */
	static String shortName(String value) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		if (value.codePointCount(0, value.length()) > 16) {
			throw ApiException.invalid("shortName은 2글자 이하여야 합니다.");
		}
		boolean unsafe = value.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)
					|| (Character.getType(cp) == Character.FORMAT && cp != 0x200D) || "<>&\"'`".indexOf(cp) >= 0);
		if (unsafe) {
			throw ApiException.invalid("shortName에 쓸 수 없는 문자가 있습니다.");
		}
		BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
		graphemes.setText(value);
		int count = 0;
		while (graphemes.next() != BreakIterator.DONE) {
			count++;
		}
		if (count > 2) {
			throw ApiException.invalid("shortName은 2글자 이하여야 합니다.");
		}
		return value;
	}

	private static ResponseEntity<Profile> ok(Profile profile) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile);
	}

	/**
	 * @param avatarColor 24색 키
	 * @param shortName 아바타 약칭. 비우면 지운다
	 * @param version GET이 돌려준 {@code avatar.version}
	 */
	record UpdateProfile(String avatarColor, String shortName, Integer version) {
	}

	record UpdateContact(String phone, String email) {

		@Override
		public String toString() {
			return "UpdateContact[]";
		}

	}

}
