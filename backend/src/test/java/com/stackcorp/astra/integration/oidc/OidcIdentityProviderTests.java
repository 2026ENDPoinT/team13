package com.stackcorp.astra.integration.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider.ExternalLogin;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider.LoginRedirect;
import com.stackcorp.astra.identity.api.ExternalLoginException;

/**
 * adapter만 떼어 가짜 IdP와 맞춘다. 시계를 바꿔야 하거나 discovery 문서가 달라야 하는 경우를 여기서 본다. HTTP 경계와 사용자
 * 매핑은 {@code identity.sso.SsoLoginTests}가 본다.
 */
class OidcIdentityProviderTests {

	private static final URI REDIRECT_URI = URI.create("http://127.0.0.1/api/auth/callback");

	private MockOpenIdProvider idp;

	@BeforeEach
	void startIdp() {
		this.idp = MockOpenIdProvider.start(REDIRECT_URI.toString());
	}

	@AfterEach
	void stopIdp() {
		this.idp.close();
	}

	@Test
	void completesCodeFlowWithPkceAndMapsConfiguredClaim() {
		OidcIdentityProvider provider = provider(Clock.systemUTC());

		LoginRedirect redirect = provider.begin("/courses/7");
		Map<String, String> query = MockOpenIdProvider.query(redirect.location().getRawQuery());
		ExternalLogin login = provider.complete(redirect.binding(), callback(redirect, "20231234"));

		assertThat(login).isEqualTo(new ExternalLogin("20231234", "/courses/7", false));
		assertThat(query).doesNotContainKeys("prompt", "max_age");
		assertThat(redirect.location().toString()).startsWith(this.idp.issuer() + "/protocol/openid-connect/auth?");
		assertThat(query).containsEntry("code_challenge_method", "S256").containsEntry("scope", "openid");
		assertThat(query.get("code_challenge")).hasSize(43);
		assertThat(redirect.binding()).doesNotContain(query.get("state"), query.get("nonce"));
	}

	@Test
	void rejectsBindingOlderThanLoginTimeout() {
		Instant start = Instant.now();
		LoginRedirect redirect = provider(Clock.fixed(start, ZoneOffset.UTC)).begin("/");
		Map<String, String> callback = callback(redirect, "20231234");
		OidcIdentityProvider later = provider(
				Clock.fixed(start.plus(OidcIdentityProvider.LOGIN_TIMEOUT).plusSeconds(1), ZoneOffset.UTC));

		assertReason("binding_expired", () -> later.complete(redirect.binding(), callback));
	}

	@Test
	void rejectsAlteredForeignOrMissingBinding() {
		OidcIdentityProvider provider = provider(Clock.systemUTC());
		LoginRedirect redirect = provider.begin("/");
		Map<String, String> callback = callback(redirect, "20231234");
		String binding = redirect.binding();
		String altered = binding.substring(0, 20) + (binding.charAt(20) == 'A' ? 'B' : 'A') + binding.substring(21);
		LoginRedirect foreign = provider(properties(this.idp.issuer(), "another-client-secret", List.of(),
				Duration.ofSeconds(5)), Clock.systemUTC()).begin("/");

		assertReason("binding_invalid", () -> provider.complete(altered, callback));
		assertReason("binding_invalid", () -> provider.complete(foreign.binding(), callback));
		assertReason("binding_missing", () -> provider.complete(null, callback));
	}

	@Test
	void rejectsIdpErrorAndMixedUpIssuerParameter() {
		OidcIdentityProvider provider = provider(Clock.systemUTC());
		LoginRedirect redirect = provider.begin("/");
		Map<String, String> callback = callback(redirect, "20231234");
		String state = callback.get("state");

		assertReason("idp_error",
				() -> provider.complete(redirect.binding(), Map.of("error", "access_denied", "state", state)));
		assertReason("iss_parameter", () -> provider.complete(redirect.binding(),
				Map.of("code", callback.get("code"), "state", state, "iss", "https://other-idp.example")));
	}

	@Test
	void sendsClientSecretInBodyWhenIdpOnlySupportsThat() {
		this.idp.discovery((document) -> {
			document.put("token_endpoint_auth_methods_supported", List.of("client_secret_post"));
			return document;
		});
		OidcIdentityProvider provider = provider(Clock.systemUTC());
		LoginRedirect redirect = provider.begin("/");

		assertThat(provider.complete(redirect.binding(), callback(redirect, "20231234")).externalRef())
			.isEqualTo("20231234");
	}

	@Test
	void doesNotFollowRedirectsFromTheIdp() {
		AtomicInteger followed = new AtomicInteger();
		this.idp.override("/elsewhere", (exchange) -> {
			followed.incrementAndGet();
			MockOpenIdProvider.respond(exchange, 200, Map.of());
		});
		this.idp.override("/.well-known/openid-configuration", (exchange) -> {
			exchange.getResponseHeaders().set("location", this.idp.issuer() + "/elsewhere");
			exchange.sendResponseHeaders(302, -1);
		});

		assertReason("discovery_status_302", () -> provider(Clock.systemUTC()).begin("/"));
		assertThat(followed).hasValue(0);
	}

	@Test
	void capsResponseSize() {
		this.idp.discovery((document) -> {
			document.put("padding", "x".repeat((int) IssuerHttp.MAX_RESPONSE_BYTES));
			return document;
		});

		assertReason("discovery_failed", () -> provider(Clock.systemUTC()).begin("/"));
	}

	@Test
	void timesOutSlowIdp() {
		this.idp.override("/.well-known/openid-configuration", (exchange) -> {
			try {
				Thread.sleep(1500);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		OidcIdentityProvider provider = provider(
				properties(this.idp.issuer(), MockOpenIdProvider.CLIENT_SECRET, List.of(), Duration.ofMillis(300)),
				Clock.systemUTC());

		assertReason("discovery_timeout", () -> provider.begin("/"));
	}

	@Test
	void failsWhenIdpIsUnreachable() {
		OidcIdentityProvider provider = provider(Clock.systemUTC());
		this.idp.close();

		assertReason("discovery_failed", () -> provider.begin("/"));
	}

	@Test
	void callsOnlyTheIssuerOriginAndConfiguredHosts() {
		this.idp.discovery((document) -> {
			document.put("token_endpoint", "https://evil.example/token");
			return document;
		});
		assertReason("discovery_host_not_allowed", () -> provider(Clock.systemUTC()).begin("/"));

		this.idp.discovery((document) -> {
			document.put("jwks_uri", "http://login.school.example/jwks");
			return document;
		});
		OidcIdentityProvider allowing = provider(properties(this.idp.issuer(), MockOpenIdProvider.CLIENT_SECRET,
				List.of("login.school.example"), Duration.ofSeconds(5)), Clock.systemUTC());
		assertReason("discovery_host_not_allowed", () -> allowing.begin("/"));

		this.idp.discovery((document) -> {
			document.put("jwks_uri", "https://login.school.example/jwks");
			return document;
		});
		assertThat(allowing.begin("/").location().toString()).startsWith(this.idp.issuer());
	}

	@Test
	void refusesDiscoveryDocumentOfAnotherIssuer() {
		this.idp.discovery((document) -> {
			document.put("issuer", "https://other-idp.example");
			return document;
		});

		assertReason("discovery_issuer", () -> provider(Clock.systemUTC()).begin("/"));
	}

	@Test
	void validatesConfigurationAndHidesSecret() {
		assertThatIllegalArgumentException().isThrownBy(() -> properties("http://idp.school.example",
				MockOpenIdProvider.CLIENT_SECRET, List.of(), Duration.ofSeconds(5)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> properties(this.idp.issuer(), " ", List.of(), Duration.ofSeconds(5)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OidcProperties(this.idp.issuer(), MockOpenIdProvider.CLIENT_ID,
					MockOpenIdProvider.CLIENT_SECRET, REDIRECT_URI, List.of("profile"), "sub", List.of(),
					Duration.ofSeconds(5), Duration.ofSeconds(60)));
		assertThat(properties(this.idp.issuer(), MockOpenIdProvider.CLIENT_SECRET, List.of(), Duration.ofSeconds(5))
			.toString()).doesNotContain(MockOpenIdProvider.CLIENT_SECRET);
	}

	@Test
	void reauthenticationForcesIdpLoginAndRequiresFreshAuthTime() {
		OidcIdentityProvider provider = provider(Clock.systemUTC());

		LoginRedirect redirect = provider.beginReauthentication("/me/info");
		Map<String, String> query = MockOpenIdProvider.query(redirect.location().getRawQuery());
		assertThat(query).containsEntry("prompt", "login").containsEntry("max_age", "0");
		assertThat(provider.complete(redirect.binding(), callback(redirect, "20231234")))
			.isEqualTo(new ExternalLogin("20231234", "/me/info", true));

		// IdP가 기존 IdP session으로 넘어가 auth_time을 주지 않거나 오래된 값을 주면 재인증으로 치지 않는다.
		this.idp.claims((c) -> c.remove("auth_time"));
		LoginRedirect missing = provider.beginReauthentication("/");
		assertReason("auth_time_missing", () -> provider.complete(missing.binding(), callback(missing, "20231234")));

		long stale = Instant.now().minus(Duration.ofMinutes(5)).getEpochSecond();
		this.idp.claims((c) -> c.put("auth_time", stale));
		LoginRedirect old = provider.beginReauthentication("/");
		assertReason("auth_time_stale", () -> provider.complete(old.binding(), callback(old, "20231234")));

		this.idp.claims((c) -> c.put("auth_time", "now"));
		LoginRedirect odd = provider.beginReauthentication("/");
		assertReason("auth_time_missing", () -> provider.complete(odd.binding(), callback(odd, "20231234")));
	}

	private Map<String, String> callback(LoginRedirect redirect, String studentNumber) {
		return MockOpenIdProvider.query(this.idp.authorize(redirect.location(), studentNumber).getRawQuery());
	}

	private OidcIdentityProvider provider(Clock clock) {
		return provider(properties(this.idp.issuer(), MockOpenIdProvider.CLIENT_SECRET, List.of(),
				Duration.ofSeconds(5)), clock);
	}

	private static OidcIdentityProvider provider(OidcProperties properties, Clock clock) {
		return new OidcIdentityProvider(properties, clock);
	}

	private static OidcProperties properties(String issuer, String secret, List<String> allowedHosts,
			Duration timeout) {
		return new OidcProperties(issuer, MockOpenIdProvider.CLIENT_ID, secret, REDIRECT_URI, List.of("openid"),
				MockOpenIdProvider.USER_CLAIM, allowedHosts, timeout, Duration.ofSeconds(60));
	}

	private static void assertReason(String reason, Executable call) {
		ExternalLoginException ex = catchThrowableOfType(ExternalLoginException.class, call::execute);
		assertThat(ex).as("expected rejection with reason %s", reason).isNotNull();
		assertThat(ex.reason()).isEqualTo(reason);
	}

}
