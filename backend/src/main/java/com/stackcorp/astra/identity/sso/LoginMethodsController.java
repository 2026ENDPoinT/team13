package com.stackcorp.astra.identity.sso;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier;
import com.stackcorp.astra.identity.breakglass.BreakGlassProperties;
import com.stackcorp.astra.identity.session.DevLoginMarker;
import com.stackcorp.astra.shared.time.InstitutionTime;

/**
 * 지금 쓸 수 있는 로그인 수단. 로그인 화면이 어떤 버튼을 보일지 정한다. 로그인 없이 부른다. 설정 여부만 알리고 IdP를 부르지
 * 않는다.
 * <ul>
 * <li>{@code oidc}: 학교 IdP가 설정되었을 때. {@code GET /api/auth/login}으로 시작한다.</li>
 * <li>{@code breakGlass}: break-glass 계정 설정이 모두 있을 때. {@code POST /api/auth/break-glass}.</li>
 * <li>{@code eclassPassword}: 학교 e-Class 주소가 설정되었을 때. {@code POST /api/auth/eclass-login}.</li>
 * <li>{@code devLogin}: 로컬 개발 실행에서만. {@code POST /api/auth/dev-login}.</li>
 * </ul>
 * 앱이 처음 뜰 때 늘 부르므로 기관 시간대({@code timeZone}, IANA ID)도 여기서 알린다. API의 offset 없는 일시는 모두 이 시간대의
 * 벽시계 시각이다(ADR-0039).
 */
@RestController
class LoginMethodsController {

	private final ObjectProvider<ExternalIdentityProvider> providers;

	private final BreakGlassProperties breakGlass;

	private final ObjectProvider<DevLoginMarker> devLogin;

	private final ObjectProvider<SchoolPasswordVerifier> schoolPassword;

	private final InstitutionTime time;

	LoginMethodsController(ObjectProvider<ExternalIdentityProvider> providers, BreakGlassProperties breakGlass,
			ObjectProvider<DevLoginMarker> devLogin, ObjectProvider<SchoolPasswordVerifier> schoolPassword,
			InstitutionTime time) {
		this.providers = providers;
		this.breakGlass = breakGlass;
		this.devLogin = devLogin;
		this.schoolPassword = schoolPassword;
		this.time = time;
	}

	@GetMapping("/api/auth/methods")
	ResponseEntity<LoginMethods> methods() {
		List<String> methods = new ArrayList<>();
		if (this.providers.getIfAvailable() != null) {
			methods.add("oidc");
		}
		if (this.breakGlass.enabled()) {
			methods.add("breakGlass");
		}
		if (this.schoolPassword.getIfAvailable() != null) {
			methods.add("eclassPassword");
		}
		if (this.devLogin.getIfAvailable() != null) {
			methods.add("devLogin");
		}
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new LoginMethods(methods, this.time.zone().getId()));
	}

	/** @param timeZone 기관 시간대. offset 없는 일시를 이 시간대로 읽는다 */
	record LoginMethods(List<String> methods, String timeZone) {
	}

}
