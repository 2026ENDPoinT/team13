package com.stackcorp.astra.identity.account;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * 개인설정(EP-0169, EP-0220, EP-0283~EP-0286의 대체 계약, 화면 13). 화면 모드, 언어, 시간 표기, 푸시 알림 에티켓 시간을
 * user_preferences 한 행으로 둔다. 대상은 요청한 사용자뿐이다.
 * <ul>
 * <li>값은 allowlist만 받는다. 범위 밖 값은 400이고 저장된 값은 바뀌지 않는다(IDENTITY-11).</li>
 * <li>저장은 전체 교체다. 본문의 {@code version}이 저장된 값과 다르면 409 {@code version_conflict}. 행이 없으면 version은 0이다.</li>
 * <li>에티켓 시간은 {@code HH:mm}(00:00~23:59)이다. 사용할 때는 시작과 끝이 모두 있고 서로 달라야 한다. 끝이 시작보다 이르면 다음
 * 날까지다.</li>
 * </ul>
 */
@RestController
class PreferencesController {

	static final Set<String> LANGUAGES = Set.of("ko", "en");

	static final Set<String> TIME_FORMATS = Set.of("12", "24");

	static final Set<String> THEMES = Set.of("system", "light", "dark");

	private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

	private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

	private final JdbcClient jdbc;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	PreferencesController(JdbcClient jdbc, RateLimiter rateLimiter, AuditLog audit) {
		this.jdbc = jdbc;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
	}

	/** 개인설정 목록과 지금 값(EP-0286, EP-0283). 저장한 적이 없으면 기본값이다. */
	@GetMapping("/api/me/preferences")
	ResponseEntity<Preferences> preferences(@AuthenticationPrincipal Actor actor) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(find(actor.userId()));
	}

	/** 개인설정 저장(EP-0169 언어, EP-0220 화면 모드, EP-0284·EP-0285의 저장부). */
	@PutMapping("/api/me/preferences")
	ResponseEntity<Preferences> update(@AuthenticationPrincipal Actor actor, @RequestBody Preferences body) {
		Preferences valid = validated(body);
		this.rateLimiter.check("preferences-update", actor.userId(), 60, Duration.ofMinutes(10));
		if (!save(actor.userId(), valid)) {
			throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
					"다른 창에서 먼저 바꾸었습니다. 다시 불러온 뒤 저장해 주세요.");
		}
		this.audit.success(actor.userId(), actor.sessionId(), "account.preferences_update", "user_preference",
				actor.userId(), null);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(find(actor.userId()));
	}

	private Preferences find(long userId) {
		return this.jdbc.sql("""
				SELECT language_code, time_format, theme_mode, dnd_enabled, dnd_start_time, dnd_end_time, preference_version
				  FROM user_preferences WHERE user_id = :user
				""")
			.param("user", userId)
			.query((rs, n) -> new Preferences(rs.getString(1), rs.getString(2), rs.getString(3),
					new DoNotDisturb(rs.getBoolean(4), time(rs.getObject(5, LocalTime.class)),
							time(rs.getObject(6, LocalTime.class))),
					rs.getInt(7)))
			.optional()
			.orElseGet(() -> new Preferences("ko", "12", "system", new DoNotDisturb(false, null, null), 0));
	}

	private boolean save(long userId, Preferences p) {
		if (p.version() == 0) {
			try {
				return this.jdbc.sql("""
						INSERT INTO user_preferences (user_id, language_code, time_format, theme_mode, dnd_enabled,
						                              dnd_start_time, dnd_end_time, preference_version)
						VALUES (:user, :language, :time, :theme, :dnd, :start, :end, 1)
						""").params(params(userId, p)).update() == 1;
			}
			catch (DuplicateKeyException ex) {
				return false;
			}
		}
		return this.jdbc.sql("""
				UPDATE user_preferences
				   SET language_code = :language, time_format = :time, theme_mode = :theme, dnd_enabled = :dnd,
				       dnd_start_time = :start, dnd_end_time = :end, preference_version = preference_version + 1
				 WHERE user_id = :user AND preference_version = :version
				""").params(params(userId, p)).update() == 1;
	}

	private static Map<String, Object> params(long userId, Preferences p) {
		Map<String, Object> params = new HashMap<>();
		params.put("user", userId);
		params.put("language", p.language());
		params.put("time", p.timeFormat());
		params.put("theme", p.theme());
		params.put("dnd", p.doNotDisturb().enabled());
		params.put("start", p.doNotDisturb().start() == null ? null : LocalTime.parse(p.doNotDisturb().start()));
		params.put("end", p.doNotDisturb().end() == null ? null : LocalTime.parse(p.doNotDisturb().end()));
		params.put("version", p.version());
		return params;
	}

	private static Preferences validated(Preferences body) {
		if (body.language() == null || !LANGUAGES.contains(body.language())) {
			throw ApiException.invalid("language는 ko 또는 en이어야 합니다.");
		}
		if (body.timeFormat() == null || !TIME_FORMATS.contains(body.timeFormat())) {
			throw ApiException.invalid("timeFormat은 12 또는 24여야 합니다.");
		}
		if (body.theme() == null || !THEMES.contains(body.theme())) {
			throw ApiException.invalid("theme은 system, light, dark 중 하나여야 합니다.");
		}
		DoNotDisturb dnd = body.doNotDisturb();
		if (dnd == null || dnd.enabled() == null) {
			throw ApiException.invalid("doNotDisturb.enabled가 필요합니다.");
		}
		for (String time : new String[] { dnd.start(), dnd.end() }) {
			if (time != null && !TIME.matcher(time).matches()) {
				throw ApiException.invalid("에티켓 시간은 HH:mm 형식이어야 합니다.");
			}
		}
		if (dnd.enabled() && (dnd.start() == null || dnd.end() == null || dnd.start().equals(dnd.end()))) {
			throw ApiException.invalid("에티켓 시간을 쓰려면 서로 다른 시작과 끝 시각이 필요합니다.");
		}
		if (body.version() == null || body.version() < 0) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		return body;
	}

	private static String time(LocalTime value) {
		return value == null ? null : value.format(HH_MM);
	}

	/**
	 * @param language ko | en
	 * @param timeFormat 12 | 24
	 * @param theme system(기기 기본값) | light | dark
	 * @param version 저장할 때 보낼 값. 저장한 적이 없으면 0
	 */
	record Preferences(String language, String timeFormat, String theme, DoNotDisturb doNotDisturb, Integer version) {
	}

	/**
	 * 푸시 알림 에티켓(방해 금지) 시간.
	 * @param start 시작 시각 HH:mm
	 * @param end 끝 시각 HH:mm
	 */
	record DoNotDisturb(Boolean enabled, String start, String end) {
	}

}
