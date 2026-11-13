package com.stackcorp.astra.identity.sso;

import java.time.Duration;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.ExternalLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolAccount;
import com.stackcorp.astra.identity.session.ClientAddresses;
import com.stackcorp.astra.identity.session.CsrfCookie;
import com.stackcorp.astra.identity.session.DeviceCookie;
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 학번과 학교 e-Class 비밀번호로 로그인한다. 학교 e-Class에 로그인해 보고 성공하면 이 서비스의 session 쿠키를 준다. 학교 주소가
 * 설정되지 않은 실행은 404다.
 * <ul>
 * <li>비밀번호는 학교 확인 요청 한 번에만 쓰고 저장하지 않는다. 학교 session은 강의실 학습활동 가져오기가 가져가 끝나면 버리고,
 * 가져가지 않았으면 응답 전에 버린다.</li>
 * <li>학교 요청은 DB transaction 밖에서 보낸다.</li>
 * <li>학교가 거절한 시도만 브라우저 주소와 학번 단위로 센다. 한도에 닿으면 학교에 묻지 않고 429다. 학교 계정 잠금과 비밀번호 대입을
 * 이 서비스가 돕지 않게 한다. 성공한 로그인은 세지 않아, 앞단 proxy 하나로 모인 주소에서도 정상 로그인끼리 서로 막지 않는다.</li>
 * </ul>
 */
@RestController
class SchoolPasswordController {

	static final String PATH = "/api/auth/eclass-login";

	static final int ADDRESS_FAILURES = 30;

	static final int STUDENT_FAILURES = 10;

	static final Duration WINDOW = Duration.ofMinutes(15);

	private static final Pattern STUDENT_ID = Pattern.compile("[0-9]{6,12}");

	private static final int MAX_PASSWORD = 256;

	private static final Logger log = LoggerFactory.getLogger(SchoolPasswordController.class);

	private final ObjectProvider<SchoolPasswordVerifier> verifiers;

	private final SchoolPasswordLogin login;

	private final SessionProperties properties;

	private final RateLimiter rateLimiter;

	private final ClientAddresses clientAddresses;

	SchoolPasswordController(ObjectProvider<SchoolPasswordVerifier> verifiers, SchoolPasswordLogin login,
			SessionProperties properties, RateLimiter rateLimiter, ClientAddresses clientAddresses) {
		this.verifiers = verifiers;
		this.login = login;
		this.properties = properties;
		this.rateLimiter = rateLimiter;
		this.clientAddresses = clientAddresses;
	}

	@PostMapping(PATH)
	ResponseEntity<Void> login(@RequestBody(required = false) Credentials body, HttpServletRequest request) {
		SchoolPasswordVerifier verifier = this.verifiers.getIfAvailable();
		if (verifier == null) {
			throw ApiException.notFound();
		}
		String studentId = body == null || body.studentId() == null ? "" : body.studentId().strip();
		String password = body == null ? null : body.password();
		if (!STUDENT_ID.matcher(studentId).matches() || password == null || password.isEmpty()
				|| password.length() > MAX_PASSWORD) {
			throw ApiException.invalid("학번과 비밀번호를 확인해 주세요.");
		}
		String address = this.clientAddresses.of(request);
		this.rateLimiter.checkFailures("eclass-login", address, ADDRESS_FAILURES);
		this.rateLimiter.checkFailures("eclass-login-student", studentId, STUDENT_FAILURES);
		SchoolAccount account;
		try {
			account = verifier.verify(studentId, password);
		}
		catch (SchoolLoginException ex) {
			if (ex.kind() == SchoolLoginException.Kind.REJECTED) {
				this.rateLimiter.recordFailure("eclass-login", address, WINDOW);
				this.rateLimiter.recordFailure("eclass-login-student", studentId, WINDOW);
				this.login.rejected(studentId, address);
				throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "학번 또는 비밀번호가 맞지 않습니다.");
			}
			log.warn("e-Class login unavailable: reason={}", ex.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, "school_unavailable",
					"학교 e-Class 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
		}
		IssuedSession session;
		try {
			if (!studentId.equals(account.studentId())) {
				throw new ApiException(HttpStatus.BAD_GATEWAY, "school_unavailable",
						"학교 e-Class 서버에 연결하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
			}
			session = this.login.signIn(account, DeviceCookie.read(request), address,
					request.getHeader(HttpHeaders.USER_AGENT));
		}
		catch (ExternalLoginException ex) {
			log.warn("e-Class login refused: reason={}", ex.reason());
			throw new ApiException(HttpStatus.UNAUTHORIZED, "rejected", "이 계정으로는 로그인할 수 없습니다.");
		}
		finally {
			// 강의실 가져오기가 가져가지 않은 학교 session(로그인 거절, 가져오기 꺼짐, 과목 없음)은 여기서 버린다.
			SchoolCourseRooms rooms = account.rooms();
			if (rooms != null && rooms.claim()) {
				rooms.close();
			}
		}
		ResponseCookie cookie = ResponseCookie.from(this.properties.cookieName(), session.token())
			.httpOnly(true)
			.secure(this.properties.secureCookie())
			.sameSite("Lax")
			.path("/")
			.maxAge(this.properties.absoluteTimeout())
			.build();
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, cookie.toString(),
					DeviceCookie.issue(session.deviceUuid(), this.properties).toString(),
					CsrfCookie.renew(request).toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.build();
	}

	/** 비밀번호를 담으므로 기본 toString을 쓰지 않는다. */
	record Credentials(String studentId, String password) {

		@Override
		public String toString() {
			return "Credentials[studentId=" + this.studentId + "]";
		}

	}

}
