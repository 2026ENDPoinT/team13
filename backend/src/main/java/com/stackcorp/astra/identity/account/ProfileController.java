package com.stackcorp.astra.identity.account;

import java.text.BreakIterator;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.account.AccountProfiles.Profile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.RecentAuthentication;
import com.stackcorp.astra.identity.api.RegistrarDirectory;
import com.stackcorp.astra.identity.api.RegistrarDirectory.RegistrarRecord;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * 내정보와 아바타(EP-0270, EP-0271, EP-0272, EP-0278, EP-0279, EP-0265, EP-0266의 대체 계약). 대상은 언제나 요청한 사용자다.
 * <ul>
 * <li>바꿀 수 있는 것은 아바타 색상과 약칭, 학사 원천이 아닌 연락처뿐이다. 이름, 소속, 학년은 학사 원천이라 받지 않는다(문서에 없는
 * 필드라 400).</li>
 * <li>아바타 색상은 24색 allowlist, 약칭은 글자(grapheme) 2개 이하이고 제어 문자, 공백, HTML 특수 문자를 받지 않는다
 * (IDENTITY-10). 사진은 files 모듈의 아바타 사진 경로가 다룬다(ADR-0038).</li>
 * <li>연락처 변경은 2차 인증 수단의 대상을 바꾸므로 최근 재인증을 요구한다(IDENTITY-06, ADR-0029).</li>
 * <li>학사 동기화는 학사시스템 port({@link RegistrarDirectory})로 요청한 사용자의 값을 다시 읽는다. port가 없는 배포는 503
 * {@code not_configured}다. 내정보의 {@code registrarSyncAvailable}은 동기화 명령을 시도할 수 있는지(port가 있고 break-glass가
 * 아닌지)다.</li>
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

	private static final int MAX_NAME = 100;

	private static final int MAX_DEPARTMENT = 200;

	private static final Pattern EMAIL = Pattern
		.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(\\.[A-Za-z0-9-]{1,63})+");

	private static final int MAX_EMAIL_LENGTH = 254;

	private final AccountProfiles profiles;

	private final RecentAuthentication recentAuthentication;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	private final ObjectProvider<RegistrarDirectory> registrar;

	ProfileController(AccountProfiles profiles, RecentAuthentication recentAuthentication, RateLimiter rateLimiter,
			AuditLog audit, ObjectProvider<RegistrarDirectory> registrar) {
		this.profiles = profiles;
		this.recentAuthentication = recentAuthentication;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
		this.registrar = registrar;
	}

	/** 내정보(EP-0270). 연락처는 본인에게 원본 화면처럼 그대로 보인다. */
	@GetMapping("/api/me/profile")
	ResponseEntity<Profile> profile(@AuthenticationPrincipal Actor actor) {
		return ok(current(actor));
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
		return ok(current(actor));
	}

	/**
	 * 연락처 변경(EP-0265, EP-0266). 두 값은 언제나 문자열로 보낸다. 빈 문자열이나 공백뿐인 문자열은 그 연락처를 지운다는 뜻이라 NULL로
	 * 저장하고, 둘 다 비워도 된다. 값이 있으면 형식을 본다. 필드가 없거나 JSON null이면 일부만 보낸 본문이 다른 연락처를 지우지 않도록 400
	 * {@code invalid_request}다. 최근 재인증이 없으면 403 {@code step_up_required}, 학사 원천 연락처면 409
	 * {@code contact_managed_by_registrar}.
	 */
	@PutMapping("/api/me/contact")
	ResponseEntity<Profile> contact(@AuthenticationPrincipal Actor actor, @RequestBody UpdateContact body) {
		if (body.phone() == null) {
			throw ApiException.invalid("전화번호 형식이 올바르지 않습니다.");
		}
		if (body.email() == null) {
			throw ApiException.invalid("이메일 형식이 올바르지 않습니다.");
		}
		String phone = blankToNull(body.phone());
		String email = blankToNull(body.email());
		if (phone != null && !PHONE.matcher(phone).matches()) {
			throw ApiException.invalid("전화번호 형식이 올바르지 않습니다.");
		}
		if (email != null && (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches())) {
			throw ApiException.invalid("이메일 형식이 올바르지 않습니다.");
		}
		this.recentAuthentication.requireRecentAuth(actor, CONTACT_STEP_UP);
		this.rateLimiter.check("contact-update", actor.userId(), 10, Duration.ofMinutes(10));
		this.profiles.updateContact(actor.userId(), phone, email);
		this.audit.success(actor.userId(), actor.sessionId(), "account.contact_update", "user_contact", actor.userId(),
				null);
		return ok(current(actor));
	}

	/**
	 * 아바타 사진 지우기(EP-0278 초기화). 색상형으로 돌아가고 색상과 약칭은 그대로다. 처리 중인 사진도 버린다. 사진 객체는 files worker가
	 * 지운다. 지울 사진이 없어도 같은 결과다. 사진 올리기는 {@code POST /api/me/profile/image}(files)다.
	 */
	@DeleteMapping("/api/me/profile/image")
	ResponseEntity<Profile> removeImage(@AuthenticationPrincipal Actor actor) {
		this.rateLimiter.check("profile-update", actor.userId(), 30, Duration.ofMinutes(10));
		if (this.profiles.removeImage(actor.userId())) {
			this.audit.success(actor.userId(), actor.sessionId(), "account.avatar_image_remove", "user_profile",
					actor.userId(), null);
		}
		return ok(current(actor));
	}

	/**
	 * 학사 동기화(EP-0279). 학사시스템에서 이름, 소속, 학년, 연락처를 다시 읽어 저장하고 내정보를 돌려준다. 학사시스템 연동이 없는 배포는
	 * 503 {@code not_configured}, 학사시스템에 없는 사람이면 409 {@code registrar_record_not_found}, 학사시스템 장애나 형식에 맞지
	 * 않는 응답은 503 {@code registrar_unavailable}이다. break-glass 계정은 학사 원천이 없어 404다. 학사시스템 호출은 transaction
	 * 밖에서 한다.
	 */
	@PostMapping("/api/me/profile/sync")
	ResponseEntity<Profile> sync(@AuthenticationPrincipal Actor actor) {
		if (actor.breakGlass()) {
			throw ApiException.notFound();
		}
		RegistrarDirectory directory = this.registrar.getIfAvailable();
		if (directory == null) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "not_configured", "학사시스템 연동이 설정되지 않았습니다.");
		}
		this.rateLimiter.check("profile-sync", actor.userId(), 5, Duration.ofMinutes(10));
		RegistrarRecord record;
		try {
			record = directory.lookup(this.profiles.externalRef(actor.userId()))
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "registrar_record_not_found",
						"학사시스템에서 내 정보를 찾지 못했습니다."));
		}
		catch (RegistrarDirectory.Unavailable ex) {
			throw registrarUnavailable();
		}
		if (!valid(record)) {
			throw registrarUnavailable();
		}
		this.profiles.applyRegistrar(actor.userId(), record);
		this.audit.success(actor.userId(), actor.sessionId(), "account.registrar_sync", "user", actor.userId(), null);
		return ok(current(actor));
	}

	// 내정보의 registrarSyncAvailable. 동기화 명령이 요청 전에 거절하는 조건(break-glass 404, port 없음 503)과 같다. 요청 제한과
	// 학사시스템 응답은 시도해야 알 수 있어 넣지 않는다.
	private Profile current(Actor actor) {
		return this.profiles.find(actor.userId(), !actor.breakGlass() && this.registrar.getIfAvailable() != null);
	}

	// adapter 값도 저장 전에 컬럼 상한과 형식을 본다. 맞지 않으면 학사시스템 응답이 잘못된 것으로 본다.
	static boolean valid(RegistrarRecord record) {
		return record.name() != null && !record.name().isBlank() && record.name().length() <= MAX_NAME
				&& plain(record.name())
				&& (record.departmentName() == null
						|| record.departmentName().length() <= MAX_DEPARTMENT && plain(record.departmentName()))
				&& (record.grade() == null || record.grade() >= 1 && record.grade() <= 6)
				&& (record.phone() == null || PHONE.matcher(record.phone()).matches())
				&& (record.email() == null
						|| record.email().length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(record.email()).matches());
	}

	private static boolean plain(String text) {
		return text.codePoints().noneMatch(Character::isISOControl);
	}

	private static ApiException registrarUnavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "registrar_unavailable",
				"학사시스템에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
	}

	// 연락처 입력의 앞뒤 공백을 떼고, 남은 것이 없으면 지운다는 뜻의 null이다. 필드가 없는 경우(null)는 부르기 전에 거절한다.
	private static String blankToNull(String value) {
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
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

	/**
	 * @param phone 휴대전화번호. 빈 문자열이면 지운다. 없거나 null이면 400
	 * @param email 이메일 주소. 빈 문자열이면 지운다. 없거나 null이면 400
	 */
	record UpdateContact(String phone, String email) {

		@Override
		public String toString() {
			return "UpdateContact[]";
		}

	}

}
