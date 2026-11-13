package com.stackcorp.astra.integration.eclass;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.stackcorp.astra.identity.api.SchoolCourseContent;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolAssignment;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolDownload;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;
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
 * <li>강의실 안의 내용(파일모음, 자료 상세, 공지 목록과 상세, 과제 상세와 본인 제출 정보, 첨부 받기)은 과목을 묶은 뒤 같은
 * session으로 읽는다. 해석은 {@link SchoolContentPages}다. 영상 상세({@code online_view_form.acl}만), 강의계획서, 출석, 성적은
 * {@link SchoolScreenPages}다.
 * 이 class는 읽기 요청만 보낸다. 다음은 부르지 않는다(학교에 학습·출석을 알리거나 학교의 확인 절차를 건너뛰는 요청): 영상
 * {@code online_view_navi}·{@code online_view_hisno}·{@code online_view_at}·{@code online_view_time}·{@code online_view_status}·
 * {@code online_view_check}·{@code online_view_replace_*}·{@code online_learning*}, {@code accessone.hellolms.com},
 * {@code secondauth/*}, {@code activity_continue_update}, {@code notice_confirm}, 글·댓글·제출 쓰기와 지우기.</li>
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

	/** 학교 session이 끝났거나 다른 사용자로 바뀌었다는 JSON 응답({@code stRoomAuthCheck}가 로그인·로그아웃으로 보내는 코드). */
	private static final Pattern NO_SESSION = Pattern
		.compile("\"messageCode\"\\s*:\\s*\"(?:E_NOSESSION|E_USER_NOT_SAME)\"");

	private static final String FILES = "/ilos/cls/st/files/files_list.acl";

	private static final String MATERIAL = "/ilos/cls/st/material/lecture_material_view_form.acl";

	private static final String FILE_LIST = "/ilos/cls/st/co/efile_list.acl";

	private static final String DOWNLOAD = "/ilos/co/efile_download.acl";

	private static final String NOTICE_LIST = "/ilos/cls/st/notice/notice_list.acl";

	private static final String NOTICE_VIEW = "/ilos/cls/st/notice/notice_view_pop.acl";

	private static final String REPORT_VIEW = "/ilos/cls/st/report/report_view_form.acl";

	/** 공지 목록 한 쪽의 공지 수. 학교 화면은 8개씩 보이지만 요청한 수만큼 준다. */
	private static final int NOTICE_PAGE = 50;

	private static final int MAX_NOTICE_PAGES = 10;

	private static final String VIDEO = "/ilos/cls/st/online/online_view_form.acl";

	private static final String PLAN_POP = "/ilos/main/rg/regular_view_pop.acl";

	private static final String PLAN_VIEW = "/ilos/main/rg/regular_view_plan.acl";

	private static final String ATTENDANCE = "/ilos/cls/st/attend/attendance_list.acl";

	private static final String GRADES = "/ilos/cls/st/eval/eval3_result_view_detail_list.acl";

	/** 학교 활동·파일 번호. 숫자나 대문자·숫자 키다. */
	private static final Pattern ITEM_ID = Pattern.compile("[0-9A-Za-z]{1,40}");

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
				new Rooms(session, check.url(), studentId));
	}

	/** 과목 하나의 강의실 첫 화면을 읽는다. 과목을 session에 묶은 뒤 같은 session으로 강의실 화면을 연다. */
	private SchoolCourseRoom room(Session session, URI referer, String courseKey) {
		Integer year = CourseRoomPage.yearOf(courseKey);
		if (year == null) {
			throw unavailable("room_key");
		}
		session.boundKey = null;
		bind(session, referer, courseKey);
		Page room = session.request(resolve(this.base, ROOM), "GET", null, false, referer);
		if (signedOut(room)) {
			throw expired("room_signed_out");
		}
		return CourseRoomPage.read(room.document(), courseKey, year, this.base);
	}

	/**
	 * 과목을 학교 session에 묶는다. 강의실 안의 화면·조각 요청은 묶인 과목에 대해 답한다. 이 session이 이미 그 과목에 묶여 있으면 보내지
	 * 않는다(이 session은 이 서버만 쓴다).
	 */
	private void bind(Session session, URI referer, String courseKey) {
		if (courseKey.equals(session.boundKey)) {
			return;
		}
		Page bound = session.request(resolve(this.base, ROOM_BIND), "POST", "KJKEY=" + encode(courseKey)
				+ "&returnData=json&returnURI=" + encode(ROOM) + "&encoding=utf-8", true, referer);
		if (signedOut(bound)) {
			throw expired("bind_signed_out");
		}
		if (ROOM_ERROR.matcher(bound.body()).find()) {
			throw unavailable("room_denied");
		}
		session.boundKey = courseKey;
	}

	/** 파일모음 목록. 목록 화면이라 학교 쪽 상태를 바꾸지 않는다. */
	private List<SchoolFile> files(Session session, URI referer, String courseKey) {
		bind(session, referer, courseKey);
		Page list = session.request(resolve(this.base, FILES), "POST", "SCH_VALUE=&encoding=utf-8", true, referer);
		requireSignedIn(list);
		return SchoolContentPages.files(list.document());
	}

	/** 자료 상세와 첨부 목록. 학교에서 조회수가 는다. 이어 보기 기록({@code activity_continue_update.acl})은 보내지 않는다. */
	private SchoolPost material(Session session, URI referer, String courseKey, String itemId, String studentId) {
		if (!ITEM_ID.matcher(itemId).matches()) {
			throw notFound("item");
		}
		Integer year = CourseRoomPage.yearOf(courseKey);
		bind(session, referer, courseKey);
		Page detail = session.request(resolve(this.base, MATERIAL + "?ARTL_NUM=" + itemId + "&encoding=utf-8"), "GET",
				null, true, referer);
		requireSignedIn(detail);
		String contentSeq = SchoolContentPages.contentSeq(detail.body());
		List<SchoolFile> files = List.of();
		if (contentSeq != null) {
			files = attachments(session, referer, courseKey, studentId, contentSeq, "lecture_material", itemId);
		}
		return SchoolContentPages.post(detail.document(), files, year == null ? 0 : year);
	}

	/** 공지 목록 전체. 목록 화면이라 학교 쪽 읽음 표시를 바꾸지 않는다. 쪽마다 {@value #NOTICE_PAGE}개씩 차례로 읽는다. */
	private List<SchoolNotice> notices(Session session, URI referer, String courseKey) {
		Integer year = CourseRoomPage.yearOf(courseKey);
		if (year == null) {
			throw unavailable("room_key");
		}
		bind(session, referer, courseKey);
		List<SchoolNotice> notices = new ArrayList<>();
		for (int page = 1; page <= MAX_NOTICE_PAGES; page++) {
			Page list = session.request(resolve(this.base, NOTICE_LIST), "POST", "start=" + page + "&display="
					+ NOTICE_PAGE + "&SCH_VALUE=&ODR=&encoding=utf-8", true, referer);
			requireSignedIn(list);
			List<SchoolNotice> part = SchoolContentPages.notices(list.document(), list.body(), year,
					(id) -> CourseRoomPage.connect(this.base, courseKey, "notice", id));
			notices.addAll(part);
			Integer total = SchoolContentPages.noticeTotal(list.body());
			if (part.size() < NOTICE_PAGE || (total != null && notices.size() >= total)) {
				break;
			}
		}
		return notices.stream().distinct().toList();
	}

	/**
	 * 공지 상세와 첨부 목록. 학교에서 그 학생이 읽은 것으로 표시되고 조회수가 는다. "공지를 확인했습니다"({@code notice_confirm.acl})와 댓글
	 * 요청은 보내지 않는다.
	 */
	private SchoolPost notice(Session session, URI referer, String courseKey, String itemId, String studentId) {
		if (!ITEM_ID.matcher(itemId).matches()) {
			throw notFound("item");
		}
		Integer year = CourseRoomPage.yearOf(courseKey);
		bind(session, referer, courseKey);
		Page detail = session.request(resolve(this.base, NOTICE_VIEW), "POST",
				"ARTL_NUM=" + encode(itemId) + "&encoding=utf-8", true, referer);
		requireSignedIn(detail);
		String contentSeq = SchoolContentPages.contentSeq(detail.body());
		List<SchoolFile> files = List.of();
		if (contentSeq != null) {
			files = attachments(session, referer, courseKey, studentId, contentSeq, "notice", itemId);
		}
		return SchoolContentPages.post(detail.document(), files, year == null ? 0 : year);
	}

	/**
	 * 과제 상세, 과제 첨부, 학생 본인의 제출 파일. 상세 화면 스크립트가 부르는 첨부 목록({@code efile_list.acl}) 두 개만 같은 순서로
	 * 부른다. 상세가 스크립트로 부르는 다른 요청(제출 확인·삭제·수정 창, 평가·상호평가·차트, 질의응답)은 보내지 않는다.
	 */
	private SchoolAssignment assignment(Session session, URI referer, String courseKey, String itemId,
			String studentId) {
		if (!ITEM_ID.matcher(itemId).matches()) {
			throw notFound("item");
		}
		Integer year = CourseRoomPage.yearOf(courseKey);
		bind(session, referer, courseKey);
		Page detail = session.request(resolve(this.base, REPORT_VIEW + "?RT_SEQ=" + itemId + "&encoding=utf-8"), "GET",
				null, true, referer);
		requireSignedIn(detail);
		String contentSeq = SchoolContentPages.contentSeq(detail.body());
		String submittedSeq = SchoolContentPages.submittedContentSeq(detail.body());
		List<SchoolFile> files = contentSeq == null ? List.of()
				: attachments(session, referer, courseKey, studentId, contentSeq, "report", itemId);
		List<SchoolFile> submitted = submittedSeq == null || submittedSeq.equals(contentSeq) ? List.of()
				: attachments(session, referer, courseKey, studentId, submittedSeq, SchoolCourseContent.SUBMISSION,
						itemId);
		return SchoolContentPages.assignment(detail.document(), files, submitted, year == null ? 0 : year);
	}

	/** {@code efile_list.acl}: 내용 하나의 첨부 목록 조각. */
	private List<SchoolFile> attachments(Session session, URI referer, String courseKey, String studentId,
			String contentSeq, String activityType, String activityItemId) {
		Page list = session.request(resolve(this.base, FILE_LIST),
				"POST", "ud=" + encode(studentId) + "&ky=" + encode(courseKey) + "&pf_st_flag=2&CONTENT_SEQ="
						+ encode(contentSeq) + "&encoding=utf-8",
				true, referer);
		requireSignedIn(list);
		return SchoolContentPages.attachments(list.document(), activityType, activityItemId);
	}

	private SchoolDownload download(Session session, URI referer, String courseKey, String studentId, SchoolFile file,
			long maxBytes, Path target) {
		if (!ITEM_ID.matcher(file.fileSeq()).matches() || !ITEM_ID.matcher(file.contentSeq()).matches()) {
			throw notFound("file");
		}
		bind(session, referer, courseKey);
		URI url = resolve(this.base, DOWNLOAD + "?FILE_SEQ=" + encode(file.fileSeq()) + "&CONTENT_SEQ="
				+ encode(file.contentSeq()) + "&ky=" + encode(courseKey) + "&ud=" + encode(studentId) + "&pf_st_flag=2");
		return session.download(url, referer, maxBytes, target);
	}

	// 영상 상세, 강의계획서, 출석, 성적(ADR-0043 4·5단계). 해석은 SchoolScreenPages다. 모두 화면을 읽기만 한다.

	/**
	 * 영상 활동 상세({@code online_view_form.acl}) 하나만 읽는다. 카드를 눌렀을 때 학교 화면이 부르는 학습창·영상 주소·중복 시청·2차
	 * 인증 요청은 보내지 않는다.
	 */
	private SchoolVideo video(Session session, URI referer, String courseKey, String itemId) {
		if (!ITEM_ID.matcher(itemId).matches()) {
			throw notFound("item");
		}
		Integer year = CourseRoomPage.yearOf(courseKey);
		bind(session, referer, courseKey);
		Page detail = session.request(resolve(this.base, VIDEO + "?LECTURE_WEEKS=" + itemId + "&encoding=utf-8"), "GET",
				null, true, referer);
		requireSignedIn(detail);
		return SchoolScreenPages.video(detail.document(), year == null ? 0 : year, courseKey.startsWith("비"));
	}

	/**
	 * 강의계획서. 학교 화면처럼 강의실 첫 화면의 {@code lecPlanViewPop()} 값으로 팝업({@code regular_view_pop.acl})과 내용
	 * ({@code regular_view_plan.acl})을 차례로 읽는다. 계획서 버튼이 없으면(비정규 과정) {@code NOT_FOUND}다.
	 */
	private SchoolSyllabus syllabus(Session session, URI referer, String courseKey) {
		bind(session, referer, courseKey);
		Page room = session.request(resolve(this.base, ROOM), "GET", null, false, referer);
		requireSignedIn(room);
		Map<String, String> plan = SchoolScreenPages.planParameters(room.body());
		if (plan == null || !courseKey.equals(plan.get("KJKEY"))) {
			throw notFound("syllabus_absent");
		}
		String body = plan.entrySet()
			.stream()
			.map((e) -> e.getKey() + "=" + encode(e.getValue()))
			.collect(Collectors.joining("&")) + "&encoding=utf-8";
		requireSignedIn(session.request(resolve(this.base, PLAN_POP), "POST", body, true, referer));
		Page content = session.request(resolve(this.base, PLAN_VIEW), "POST", body, true, referer);
		requireSignedIn(content);
		return SchoolScreenPages.syllabus(content.document());
	}

	/** 학생 본인의 출석, 모든 주차({@code SCH_WEEK_NO} 빈 값). */
	private List<SchoolAttendanceWeek> attendance(Session session, URI referer, String courseKey) {
		Integer year = CourseRoomPage.yearOf(courseKey);
		bind(session, referer, courseKey);
		Page list = session.request(resolve(this.base, ATTENDANCE), "POST", "SCH_WEEK_NO=&encoding=utf-8", true,
				referer);
		requireSignedIn(list);
		return SchoolScreenPages.attendance(list.document(), year == null ? 0 : year);
	}

	/** 학생 본인의 평가 활동과 점수. 유형 값을 비우면 학교가 빈 목록을 주므로 {@code all}을 보낸다. */
	private List<SchoolGradeItem> grades(Session session, URI referer, String courseKey) {
		Integer year = CourseRoomPage.yearOf(courseKey);
		bind(session, referer, courseKey);
		Page list = session.request(resolve(this.base, GRADES), "POST",
				"SCH_EVAL_ACTIVITY_ID=all&SCH_VALUE=&encoding=utf-8", true, referer);
		requireSignedIn(list);
		return SchoolScreenPages.grades(list.document(), year == null ? 0 : year);
	}

	private static boolean signedOut(Page page) {
		return loginForm(page.document()) || loginPath(page.url()) || NO_SESSION.matcher(page.body()).find();
	}

	private static void requireSignedIn(Page page) {
		if (signedOut(page)) {
			throw expired("signed_out");
		}
	}

	private static SchoolLoginException expired(String reason) {
		return new SchoolLoginException(Kind.EXPIRED, reason);
	}

	private static SchoolLoginException notFound(String reason) {
		return new SchoolLoginException(Kind.NOT_FOUND, reason);
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

		/** 지금 이 session에 묶인 학교 과목 키. 모르면 null */
		private String boundKey;

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

		/**
		 * 첨부 파일 하나를 {@code target}에 받는다. 학교는 받기 주소({@code efile_download.acl})에서 실제 받기 주소
		 * ({@code file_download_v2.acl})로 넘긴다. 그 주소는 경로 앞에 {@code /}가 겹쳐 오므로 하나로 줄여 같은 origin의
		 * {@code /ilos/} 경로일 때만 따라간다. 학교가 파일 대신 화면(로그인 화면, 오류 화면)을 돌려주면 받지 않는다. 크기가
		 * {@code maxBytes}를 넘으면 받는 도중에 끊는다.
		 */
		SchoolDownload download(URI url, URI referer, long maxBytes, Path target) {
			return download(url, referer, maxBytes, target, 0);
		}

		private SchoolDownload download(URI url, URI referer, long maxBytes, Path target, int redirects) {
			long remaining = this.deadline - System.nanoTime();
			if (remaining <= 0) {
				throw unavailable("timeout");
			}
			HttpRequest.Builder builder = HttpRequest.newBuilder(url)
				.timeout(Duration.ofNanos(remaining))
				.header("User-Agent", "ASTRA-eclass-login/1.0")
				.header("Referer", referer.toString())
				.GET();
			try {
				for (Map.Entry<String, List<String>> header : this.cookies.get(url, Map.of()).entrySet()) {
					for (String value : header.getValue()) {
						builder.header(header.getKey(), value);
					}
				}
				HttpResponse<InputStream> response = EclassPasswordVerifier.this.client.send(builder.build(),
						HttpResponse.BodyHandlers.ofInputStream());
				try (InputStream in = response.body()) {
					this.cookies.put(url, response.headers().map());
					int status = response.statusCode();
					if (status >= 300 && status < 400) {
						String location = response.headers().firstValue("Location").orElse("").strip();
						if (location.isBlank() || redirects >= MAX_REDIRECTS) {
							throw unavailable("redirect");
						}
						URI next = URI.create(url.resolve(location.replaceFirst("^(https?://[^/]+)?/{2,}", "$1/"))
							.toString());
						if (loginPath(next)) {
							throw expired("signed_out");
						}
						return download(resolve(url, next.toString()), url, maxBytes, target, redirects + 1);
					}
					if (status == 404) {
						throw notFound("file");
					}
					if (status < 200 || status >= 300) {
						throw unavailable("status_" + status);
					}
					String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
					if (type.startsWith("text/html") || type.startsWith("application/json")) {
						byte[] head = in.readNBytes(MAX_BODY_BYTES);
						String html = new String(head, charset(response));
						Page page = new Page(url, Jsoup.parse(html, url.toString()), html);
						throw signedOut(page) ? expired("signed_out") : notFound("file");
					}
					long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
					if (declared > maxBytes) {
						throw unavailable("too_large");
					}
					long written = 0;
					try (OutputStream out = Files.newOutputStream(target)) {
						byte[] buffer = new byte[64 * 1024];
						for (int read; (read = in.read(buffer)) != -1;) {
							written += read;
							if (written > maxBytes) {
								throw unavailable("too_large");
							}
							if (System.nanoTime() > this.deadline) {
								throw unavailable("timeout");
							}
							out.write(buffer, 0, read);
						}
					}
					if (written == 0 || (declared >= 0 && declared != written)) {
						throw unavailable("truncated");
					}
					return new SchoolDownload(written);
				}
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

	/**
	 * 확인으로 열린 학교 session으로 강의실을 읽는 손잡이. cookie는 {@link Session} 안에만 있다. 읽기는 하나씩 차례로 한다(과목이
	 * session에 묶이는 구조).
	 */
	private final class Rooms implements SchoolCourseRooms {

		private final AtomicBoolean claimed = new AtomicBoolean();

		private final URI referer;

		private final String studentId;

		private Session session;

		Rooms(Session session, URI referer, String studentId) {
			this.session = session;
			this.referer = referer;
			this.studentId = studentId;
		}

		@Override
		public boolean claim() {
			return this.claimed.compareAndSet(false, true);
		}

		@Override
		public synchronized SchoolCourseRoom read(String courseKey, Duration requestTimeout, Duration remaining) {
			return room(open(requestTimeout, remaining), this.referer, courseKey);
		}

		@Override
		public synchronized List<SchoolFile> files(String courseKey, Duration requestTimeout, Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.files(open(requestTimeout, remaining), this.referer,
					courseKey));
		}

		@Override
		public synchronized SchoolPost material(String courseKey, String itemId, Duration requestTimeout,
				Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.material(open(requestTimeout, remaining), this.referer,
					courseKey, itemId, this.studentId));
		}

		@Override
		public synchronized List<SchoolNotice> notices(String courseKey, Duration requestTimeout, Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.notices(open(requestTimeout, remaining), this.referer,
					courseKey));
		}

		@Override
		public synchronized SchoolPost notice(String courseKey, String itemId, Duration requestTimeout,
				Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.notice(open(requestTimeout, remaining), this.referer,
					courseKey, itemId, this.studentId));
		}

		@Override
		public synchronized SchoolAssignment assignment(String courseKey, String itemId, Duration requestTimeout,
				Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.assignment(open(requestTimeout, remaining),
					this.referer, courseKey, itemId, this.studentId));
		}

		@Override
		public synchronized SchoolDownload download(String courseKey, SchoolFile file, long maxBytes, Path target,
				Duration requestTimeout, Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.download(open(requestTimeout, remaining), this.referer,
					courseKey, this.studentId, file, maxBytes, target));
		}

		@Override
		public synchronized SchoolVideo video(String courseKey, String itemId, Duration requestTimeout,
				Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.video(open(requestTimeout, remaining), this.referer,
					courseKey, itemId));
		}

		@Override
		public synchronized SchoolSyllabus syllabus(String courseKey, Duration requestTimeout, Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.syllabus(open(requestTimeout, remaining), this.referer,
					courseKey));
		}

		@Override
		public synchronized List<SchoolAttendanceWeek> attendance(String courseKey, Duration requestTimeout,
				Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.attendance(open(requestTimeout, remaining), this.referer,
					courseKey));
		}

		@Override
		public synchronized List<SchoolGradeItem> grades(String courseKey, Duration requestTimeout,
				Duration remaining) {
			return content(() -> EclassPasswordVerifier.this.grades(open(requestTimeout, remaining), this.referer,
					courseKey));
		}

		private Session open(Duration requestTimeout, Duration remaining) {
			if (this.session == null) {
				throw expired("closed");
			}
			if (requestTimeout.isNegative() || requestTimeout.isZero() || remaining.isNegative() || remaining.isZero()) {
				throw unavailable("timeout");
			}
			this.session.limit(requestTimeout, remaining);
			return this.session;
		}

		// 학교가 401/403으로 막은 내용은 그 학생에게 열리지 않은 것으로 본다(로그인 확인의 REJECTED와 다르다).
		private <T> T content(Supplier<T> read) {
			try {
				return read.get();
			}
			catch (SchoolLoginException ex) {
				if (ex.kind() == Kind.REJECTED) {
					throw notFound(ex.getMessage());
				}
				throw ex;
			}
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
