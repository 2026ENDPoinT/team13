package com.stackcorp.astra.integration.eclass;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.stackcorp.astra.identity.api.SchoolCourseRooms;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginException.Kind;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier;

/**
 * 학교 e-Class(IMAXSOFT ILOS) 로그인 화면에 학번·비밀번호로 로그인해 보고 프로필과 메인 화면의 수강과목을 읽는다. 학교에 공개 API가
 * 없어 브라우저와 같은 form 요청을 보낸다.
 * <ul>
 * <li>확인 한 번마다 cookie 저장소를 새로 만든다. 확인이 끝나면 그 cookie는 돌려준 {@link SchoolCourseRooms}의 메모리에만 남고,
 * 그것을 닫으면 버린다. 학교 session을 저장하거나 다른 확인에 다시 쓰지 않는다.</li>
 * <li>강의실은 과목을 session에 묶는 요청({@code eclass_room2.acl}) 뒤 강의실 첫 화면({@code submain_form.acl})을 열어 읽는다.
 * 화면 해석은 {@link CourseRoomPage}다.</li>
 * <li>로그인 form이 사라졌다는 것만으로 성공을 판단하지 않는다. 메인 화면이 로그인한 화면인지 확인한다.</li>
 * <li>redirect는 직접 따라가고 설정한 origin의 {@code /ilos/} 경로만 허용한다. 응답 본문은 2 MiB까지만 읽는다.</li>
 * <li>학교 서버가 중간 인증서(Thawte TLS RSA CA G1)를 보내지 않아 JDK 기본 신뢰 목록만으로는 연결되지 않는다. 그 중간 인증서를 이
 * client의 신뢰 목록에만 더한다.</li>
 * </ul>
 */
final class EclassPasswordVerifier implements SchoolPasswordVerifier {

	static final String INTERMEDIATE_CA = "thawte-tls-rsa-ca-g1.pem";

	private static final String LOGIN_PAGE = "/ilos/index.acl";

	private static final String DEFAULT_ACTION = "/ilos/lo/login.acl";

	private static final String MAIN = "/ilos/main/main_form.acl";

	private static final String MY_INFO = "/ilos/mp/myinfo_pop_form.acl";

	private static final String ROOM_BIND = "/ilos/cls/st/co/eclass_room2.acl";

	private static final String ROOM = "/ilos/cls/st/submain/submain_form.acl";

	private static final Pattern ROOM_ERROR = Pattern.compile("\"isError\"\s*:\s*(?:true|\"true\"|\"Y\")");

	private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	private static final int MAX_REDIRECTS = 5;

	private static final Pattern DEPARTMENT_GRADE = Pattern.compile("^(.*?)\\s+([1-6])\\s*(?:학년|Grade)$",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([^;\\s\"']+)",
			Pattern.CASE_INSENSITIVE);

	private final URI base;

	private final Duration timeout;

	private final HttpClient client;

	EclassPasswordVerifier(EclassLoginProperties properties) {
		this.base = properties.baseUrl();
		this.timeout = properties.timeout();
		this.client = HttpClient.newBuilder()
			.connectTimeout(this.timeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.sslContext(sslContext())
			.build();
	}

	@Override
	public SchoolAccount verify(String studentId, String password) {
		Session session = new Session();
		session.limit(this.timeout, this.timeout);
		Page login = session.request(resolve(this.base, LOGIN_PAGE), "GET", null, false, this.base);
		Element form = authenticationForm(login.document());
		if (form == null) {
			throw unavailable("login_form_missing");
		}
		URI target = resolve(login.url(), form.attr("action").isBlank() ? DEFAULT_ACTION : form.attr("action"));
		if (!target.getPath().startsWith("/ilos/lo/login")) {
			throw unavailable("login_action");
		}
		Map<String, String> fields = new LinkedHashMap<>();
		for (Element input : form.select("input[name]")) {
			if (!input.hasAttr("disabled") && !"submit".equalsIgnoreCase(input.attr("type"))) {
				fields.put(input.attr("name"), input.val());
			}
		}
		fields.put("usr_id", studentId);
		fields.put("usr_pwd", password);
		String body = fields.entrySet()
			.stream()
			.map((e) -> encode(e.getKey()) + "=" + encode(e.getValue()))
			.collect(Collectors.joining("&"));
		Page check = session.request(target, "POST", body, false, login.url());
		if (loginForm(check.document()) || loginError(check.document())) {
			throw new SchoolLoginException(Kind.REJECTED, "rejected");
		}
		// 로그인 POST는 메인 화면으로 옮기는 script만 돌려주기도 한다. 메인 화면을 직접 열어 로그인 여부를 확인한다.
		if (!authenticated(check.document()) || loginPath(check.url())) {
			check = session.request(resolve(this.base, MAIN), "GET", null, false, login.url());
		}
		if (loginForm(check.document()) || loginPath(check.url())) {
			throw new SchoolLoginException(Kind.REJECTED, "rejected");
		}
		if (!authenticated(check.document())) {
			throw unavailable("main_unrecognized");
		}
		Page info = session.optional(resolve(this.base, MY_INFO), "POST", "encoding=utf-8", true, check.url());
		Document profile = info == null ? check.document() : info.document();
		String name = text(profile.selectFirst(".user_info .user_name"));
		if (name == null) {
			name = text(check.document().selectFirst(".user_info .user_name"));
		}
		String department = null;
		Integer grade = null;
		String deptGrade = text(profile.selectFirst(".mp_myinfo_user_info .dept_grade"));
		if (deptGrade != null) {
			Matcher match = DEPARTMENT_GRADE.matcher(deptGrade);
			if (match.matches()) {
				department = clean(match.group(1));
				grade = Integer.valueOf(match.group(2));
			}
		}
		return new SchoolAccount(studentId, name, department, grade, MainPageCourses.read(check.document()),
				new Rooms(session, check.url()));
	}

	/** 과목 하나의 강의실 첫 화면을 읽는다. 과목을 session에 묶은 뒤 같은 session으로 강의실 화면을 연다. */
	private SchoolCourseRoom room(Session session, URI referer, String courseKey) {
		Integer year = CourseRoomPage.yearOf(courseKey);
		if (year == null) {
			throw unavailable("room_key");
		}
		URI bind = resolve(this.base, ROOM_BIND);
		Page bound = session.request(bind, "POST", "KJKEY=" + encode(courseKey) + "&returnData=json&returnURI="
				+ encode(ROOM) + "&encoding=utf-8", true, referer);
		if (ROOM_ERROR.matcher(bound.body()).find()) {
			throw unavailable("room_denied");
		}
		Page room = session.request(resolve(this.base, ROOM), "GET", null, false, referer);
		if (loginForm(room.document()) || loginPath(room.url())) {
			throw unavailable("room_signed_out");
		}
		return CourseRoomPage.read(room.document(), courseKey, year, this.base);
	}

	private static Element authenticationForm(Document doc) {
		for (Element form : doc.select("form")) {
			if (form.selectFirst("input[name=usr_id]") != null && form.selectFirst("input[name=usr_pwd]") != null) {
				return form;
			}
		}
		return null;
	}

	private static boolean loginForm(Document doc) {
		return !doc.select("input[name=usr_id], input[name=usr_pwd], form[action*=/ilos/lo/login]").isEmpty();
	}

	// 틀린 학번·비밀번호에 학교는 로그인 화면(부모 문서)의 오류 문구를 켜는 script만 돌려준다.
	private static boolean loginError(Document doc) {
		for (Element script : doc.select("script")) {
			String code = script.data();
			if (code.contains("err_message") || code.contains("#usr_pwd")) {
				return true;
			}
		}
		return false;
	}

	// 로그인한 화면에만 있는 강의·할 일 영역이나 로그아웃 링크가 있어야 한다.
	private static boolean authenticated(Document doc) {
		if (loginForm(doc)) {
			return false;
		}
		if (!doc.select(".my_lecture_container, .todo_wrap").isEmpty()) {
			return true;
		}
		for (Element element : doc.select("a[href], form[action], [onclick]")) {
			String target = element.attr("href") + element.attr("action") + element.attr("onclick");
			if (target.toLowerCase(Locale.ROOT).contains("logout")) {
				return true;
			}
		}
		return false;
	}

	private static boolean loginPath(URI uri) {
		return uri.getPath().matches("(?i).*/(?:lo/)?login[^/]*");
	}

	private URI resolve(URI from, String path) {
		try {
			if (path.length() > 2048 || path.contains("\\") || path.chars().anyMatch((c) -> c < 32)) {
				throw unavailable("location");
			}
			URI uri = from.resolve(path.replace("&amp;", "&"));
			if (!this.base.getScheme().equalsIgnoreCase(uri.getScheme())
					|| !this.base.getHost().equalsIgnoreCase(uri.getHost()) || port(this.base) != port(uri)
					|| uri.getRawUserInfo() != null || uri.getRawFragment() != null || !uri.normalize().equals(uri)
					|| uri.getPath() == null || !uri.getPath().startsWith("/ilos/")) {
				throw unavailable("location");
			}
			return uri;
		}
		catch (IllegalArgumentException ex) {
			throw unavailable("location");
		}
	}

	private static int port(URI uri) {
		if (uri.getPort() >= 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String text(Element element) {
		return element == null ? null : clean(element.text());
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.replaceAll("\\s+", " ").strip();
	}

	private static SchoolLoginException unavailable(String reason) {
		return new SchoolLoginException(Kind.UNAVAILABLE, reason);
	}

	/** JDK 기본 신뢰 목록에 학교 서버가 보내지 않는 중간 인증서를 더한 TLS 설정. 이 client에만 쓴다. */
	private static SSLContext sslContext() {
		try (InputStream pem = EclassPasswordVerifier.class.getResourceAsStream(INTERMEDIATE_CA)) {
			if (pem == null) {
				throw new IllegalStateException(INTERMEDIATE_CA + "가 없습니다.");
			}
			KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
			trust.load(null, null);
			TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			defaults.init((KeyStore) null);
			int index = 0;
			for (var manager : defaults.getTrustManagers()) {
				if (manager instanceof X509TrustManager x509) {
					for (X509Certificate ca : x509.getAcceptedIssuers()) {
						trust.setCertificateEntry("jdk-" + index++, ca);
					}
				}
			}
			trust.setCertificateEntry("thawte-tls-rsa-ca-g1",
					CertificateFactory.getInstance("X.509").generateCertificate(pem));
			TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			factory.init(trust);
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, factory.getTrustManagers(), null);
			return context;
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new IllegalStateException("학교 e-Class TLS 설정을 만들지 못했습니다.", ex);
		}
	}

	private record Page(URI url, Document document, String body) {
	}

	/**
	 * 확인 한 번의 학교 session cookie와 지금 작업의 시간 제한. 확인이 끝나면 {@link Rooms}가 이어 받아 강의실을 읽고, 닫으면 cookie를
	 * 버린다. 한 번에 한 thread만 쓴다.
	 */
	private final class Session {

		private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);

		private long deadline;

		private Duration requestTimeout;

		/** 지금부터 {@code total} 안에 끝내고, 요청 하나는 {@code perRequest}를 넘지 않는다. */
		void limit(Duration perRequest, Duration total) {
			this.deadline = System.nanoTime() + total.toNanos();
			this.requestTimeout = perRequest;
		}

		void clear() {
			this.cookies.getCookieStore().removeAll();
		}

		Page optional(URI url, String method, String body, boolean ajax, URI referer) {
			return request(url, method, body, ajax, referer, true);
		}

		Page request(URI url, String method, String body, boolean ajax, URI referer) {
			return request(url, method, body, ajax, referer, false);
		}

		private Page request(URI url, String method, String body, boolean ajax, URI referer, boolean optional) {
			try {
				for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
					long remaining = this.deadline - System.nanoTime();
					if (remaining <= 0) {
						throw unavailable("timeout");
					}
					HttpRequest.Builder builder = HttpRequest.newBuilder(url)
						.timeout(Duration.ofNanos(Math.min(remaining, this.requestTimeout.toNanos())))
						.header("User-Agent", "ASTRA-eclass-login/1.0")
						.header("Accept", "text/html,application/xhtml+xml")
						.header("Referer", referer.toString());
					for (Map.Entry<String, List<String>> header : this.cookies.get(url, Map.of()).entrySet()) {
						for (String value : header.getValue()) {
							builder.header(header.getKey(), value);
						}
					}
					if (ajax) {
						builder.header("X-Requested-With", "XMLHttpRequest");
					}
					if ("POST".equals(method)) {
						builder.header("Content-Type", "application/x-www-form-urlencoded")
							.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
					}
					else {
						builder.GET();
					}
					HttpResponse<byte[]> response = EclassPasswordVerifier.this.client.send(builder.build(),
							(info) -> new LimitedBody());
					this.cookies.put(url, response.headers().map());
					int status = response.statusCode();
					if (status == 401 || status == 403) {
						throw new SchoolLoginException(Kind.REJECTED, "status_" + status);
					}
					if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
						String location = response.headers().firstValue("Location").orElse("");
						if (location.isBlank() || redirects == MAX_REDIRECTS) {
							throw unavailable("redirect");
						}
						if (status == 303 || (status == 301 || status == 302) && "POST".equals(method)) {
							method = "GET";
							body = null;
						}
						referer = url;
						url = resolve(url, location);
						continue;
					}
					if (optional && (status == 404 || status == 405)) {
						return null;
					}
					if (status < 200 || status >= 300) {
						throw unavailable("status_" + status);
					}
					String html = new String(response.body(), charset(response));
					return new Page(url, Jsoup.parse(html, url.toString()), html);
				}
				throw unavailable("redirect");
			}
			catch (IOException | IllegalArgumentException ex) {
				throw new SchoolLoginException(Kind.UNAVAILABLE, "io", ex);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw unavailable("interrupted");
			}
		}

	}

	/** 확인으로 열린 학교 session으로 강의실을 읽는 손잡이. cookie는 {@link Session} 안에만 있다. */
	private final class Rooms implements SchoolCourseRooms {

		private final AtomicBoolean claimed = new AtomicBoolean();

		private final URI referer;

		private Session session;

		Rooms(Session session, URI referer) {
			this.session = session;
			this.referer = referer;
		}

		@Override
		public boolean claim() {
			return this.claimed.compareAndSet(false, true);
		}

		@Override
		public synchronized SchoolCourseRoom read(String courseKey, Duration requestTimeout, Duration remaining) {
			if (this.session == null) {
				throw unavailable("closed");
			}
			if (requestTimeout.isNegative() || requestTimeout.isZero() || remaining.isNegative() || remaining.isZero()) {
				throw unavailable("timeout");
			}
			this.session.limit(requestTimeout, remaining);
			return room(this.session, this.referer, courseKey);
		}

		@Override
		public synchronized void close() {
			if (this.session != null) {
				this.session.clear();
				this.session = null;
			}
		}

		@Override
		public String toString() {
			return "SchoolCourseRooms";
		}

	}

	private static Charset charset(HttpResponse<?> response) {
		Matcher match = CHARSET.matcher(response.headers().firstValue("Content-Type").orElse(""));
		if (!match.find()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(match.group(1));
		}
		catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
			throw unavailable("charset");
		}
	}

	/** 본문이 한도를 넘으면 받는 도중에 끊는다. */
	private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

		private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();

		private Flow.Subscription subscription;

		private long size;

		private boolean failed;

		@Override
		public CompletionStage<byte[]> getBody() {
			return this.delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			this.delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (this.failed) {
				return;
			}
			for (ByteBuffer buffer : buffers) {
				this.size += buffer.remaining();
			}
			if (this.size > MAX_BODY_BYTES) {
				this.failed = true;
				this.subscription.cancel();
				this.delegate.onError(new IOException("response too large"));
			}
			else {
				this.delegate.onNext(buffers);
			}
		}

		@Override
		public void onError(Throwable error) {
			if (!this.failed) {
				this.delegate.onError(error);
			}
		}

		@Override
		public void onComplete() {
			if (!this.failed) {
				this.delegate.onComplete();
			}
		}

	}

}
