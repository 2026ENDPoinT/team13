package com.stackcorp.astra.identity.sso;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.breakglass.BreakGlassProperties;
import com.stackcorp.astra.identity.session.DevLoginMarker;

/**
 * 지금 쓸 수 있는 로그인 수단. 로그인 화면이 어떤 버튼을 보일지 정한다. 로그인 없이 부른다. 설정 여부만 알리고 IdP를 부르지
 * 않는다.
 * <ul>
 * <li>{@code oidc}: 학교 IdP가 설정되었을 때. {@code GET /api/auth/login}으로 시작한다.</li>
 * <li>{@code breakGlass}: break-glass 계정 설정이 모두 있을 때. {@code POST /api/auth/break-glass}.</li>
 * <li>{@code devLogin}: 로컬 개발 실행에서만. {@code POST /api/auth/dev-login}.</li>
 * </ul>
 */
@RestController
class LoginMethodsController {

	private final ObjectProvider<ExternalIdentityProvider> providers;

	private final BreakGlassProperties breakGlass;

	private final ObjectProvider<DevLoginMarker> devLogin;

	LoginMethodsController(ObjectProvider<ExternalIdentityProvider> providers, BreakGlassProperties breakGlass,
			ObjectProvider<DevLoginMarker> devLogin) {
		this.providers = providers;
		this.breakGlass = breakGlass;
		this.devLogin = devLogin;
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
		if (this.devLogin.getIfAvailable() != null) {
			methods.add("devLogin");
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginMethods(methods));
	}

	record LoginMethods(List<String> methods) {
	}

}
