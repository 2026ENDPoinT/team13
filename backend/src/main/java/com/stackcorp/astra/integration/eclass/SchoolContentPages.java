package com.stackcorp.astra.integration.eclass;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolAssignment;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginException.Kind;

/**
 * 학교 e-Class 강의실 안 화면 조각을 읽는다. 2026-11 기준 구조(같은 과목도 영어로 그려질 수 있어 글자가 아니라 class와 링크로 읽는다):
 *
 * <pre>
 * 파일모음 files_list.acl
 *   .files_list
 *     a.file_target_artl[href="…activity_form.acl?m=lecture_material&amp;n=8746631"]
 *     ul.attach_list li a.file_down[href="/ilos/co/efile_download.acl?FILE_SEQ=…&amp;CONTENT_SEQ=…&amp;ky=…&amp;ud=…"] 이름
 *                       span.file_size "3.2MB"
 * 첨부 목록 efile_list.acl   ul.attach_list li (위와 같다)
 * 자료 상세 lecture_material_view_form.acl, 공지 상세 notice_view_pop.acl
 *   .view_title span  제목
 *   table.bbsview tr th|td  공개일·게시일, 작성자, (공지 만료일), 조회수(td.count)
 *   .editor_content  본문
 *   script  CONTENT_SEQ : "AGUM7AXY7C3KS"  (첨부 목록 요청에 쓰는 키)
 * 공지 목록 notice_list.acl
 *   script  $(".board_count_wrap &gt; .total_num").text('6')   (목록으로 알아보는 표시. 빈 목록에도 있다)
 *   button.board_list_wrap[data-num=8957473](.expired)
 *     .is_read(.unread)  .board_title  .board_text  .board_list_info .board_list_text(조회수)  .reg_info "11월 4일 (수) 오후 3:24"
 * 과제 상세 report_view_form.acl?RT_SEQ=
 *   .view_title span  제목
 *   table.bbsview &gt; tbody &gt; tr  날짜로 읽히는 첫 칸이 공개일, 둘째 칸이 마감일. .editor_content  설명
 *   script  CONTENT_SEQ : "…"   (과제 첨부 목록 키, 첫 efile_list)
 *   script  content_seq = "…";  (학생 본인 제출 파일 목록 키, 둘째 efile_list. 제출 전에는 빈 값)
 *   #submit_form .submit_info_box .txt(제출 상태 표시) .date(제출 일시)   #submit_form .editor_content(글로 낸 내용)
 * </pre>
 */
final class SchoolContentPages {

	static final int MAX_FILES = 1000;

	static final int MAX_NAME = 300;

	static final int MAX_BODY = 100_000;

	private static final Pattern QUERY = Pattern.compile("[?&]([A-Za-z_]+)=([^&#]*)");

	private static final Pattern KEY = Pattern.compile("[0-9A-Za-z]{1,40}");

	private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,29}");

	private static final Pattern CONTENT_SEQ = Pattern.compile("CONTENT_SEQ\\s*:\\s*\"([0-9A-Za-z]{1,40})\"");

	private static final Pattern SUBMITTED_CONTENT_SEQ = Pattern
		.compile("content_seq\\s*=\\s*\"([0-9A-Za-z]{1,40})\"");

	private static final Pattern SIZE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB)", Pattern.CASE_INSENSITIVE);

	private static final Pattern NOTICE_TOTAL = Pattern.compile("\\.total_num\"?\\)?\\s*\\.text\\(\\s*'([0-9]{1,6})'");

	private static final Pattern DIGITS = Pattern.compile("[0-9]{1,9}");

	private static final Map<String, Boolean> AUTHOR_LABELS = Map.of("작성자", true, "writer", true, "author", true,
			"등록자", true);

	static final int MAX_NOTICES = 500;

	private SchoolContentPages() {
	}

	/**
	 * 파일모음의 파일. 화면 순서이고 같은 파일은 한 번만 둔다. 한 주차 카드({@code .files_list}) 안에 활동 머리
	 * ({@code .file_list_top})와 그 활동의 파일 목록({@code ul.attach_list})이 활동마다 번갈아 온다.
	 */
	static List<SchoolFile> files(Document doc) {
		Map<String, SchoolFile> files = new LinkedHashMap<>();
		for (Element block : doc.select(".files_list")) {
			String type = null;
			String item = null;
			for (Element part : block.select(".file_list_top, ul.attach_list")) {
				if (part.hasClass("file_list_top")) {
					Map<String, String> target = query(part.selectFirst("a.file_target_artl"));
					type = target.get("m") != null && TYPE.matcher(target.get("m")).matches() ? target.get("m") : null;
					item = key(target.get("n"));
					continue;
				}
				for (SchoolFile file : list(part, type, item)) {
					if (files.size() >= MAX_FILES) {
						break;
					}
					files.putIfAbsent(file.fileSeq(), file);
				}
			}
		}
		return List.copyOf(files.values());
	}

	/** 첨부 목록 조각(또는 그것을 담은 요소)의 파일. 화면 순서. */
	static List<SchoolFile> attachments(Element root, String activityType, String activityItemId) {
		List<SchoolFile> files = new ArrayList<>();
		for (Element list : root.select("ul.attach_list")) {
			files.addAll(list(list, activityType, activityItemId));
		}
		return files;
	}

	private static List<SchoolFile> list(Element attachList, String activityType, String activityItemId) {
		List<SchoolFile> files = new ArrayList<>();
		for (Element row : attachList.select("> li")) {
			Element link = row.selectFirst("a.file_down[href*=efile_download]");
			if (link == null) {
				link = row.selectFirst("a[href*=efile_download]");
			}
			if (link == null) {
				continue;
			}
			Map<String, String> query = query(link);
			String fileSeq = key(query.get("FILE_SEQ"));
			String contentSeq = key(query.get("CONTENT_SEQ"));
			String name = clean(link.text());
			if (fileSeq == null || contentSeq == null || name == null) {
				continue;
			}
			files.add(new SchoolFile(fileSeq, contentSeq, limit(name), bytes(text(row.selectFirst(".file_size"))),
					activityType, activityItemId));
		}
		return files;
	}

	/** 상세 조각의 첨부 목록 키. 첨부 영역이 없으면 null. */
	static String contentSeq(String html) {
		Matcher match = CONTENT_SEQ.matcher(html);
		return match.find() ? match.group(1) : null;
	}

	/** 과제 상세의 학생 본인 제출 파일 목록 키(스크립트 변수 {@code content_seq}). 제출하지 않았으면 null. */
	static String submittedContentSeq(String html) {
		Matcher match = SUBMITTED_CONTENT_SEQ.matcher(html);
		String found = null;
		while (match.find()) {
			found = match.group(1);
		}
		return found;
	}

	/**
	 * 과제 상세와 학생 본인의 제출 정보. 표의 칸 이름은 한국어나 영어로 그려지므로 이름으로 정하지 않는다: 날짜로 읽히는 첫 칸이 공개일,
	 * 둘째 칸이 마감일이다. 제출 여부는 제출 정보 상자({@code .submit_info_box})의 일시로 정하고 상태 표시 글자는 보여 주기만 한다.
	 * @param year 과목 학년도. 연도 없는 날짜를 정한다
	 * @throws SchoolLoginException {@code NOT_FOUND}: 과제 상세 화면이 아니다
	 */
	static SchoolAssignment assignment(Document doc, List<SchoolFile> files, List<SchoolFile> submittedFiles,
			int year) {
		String title = text(doc.selectFirst(".view_title span, .view_title"));
		Element table = doc.selectFirst("table.bbsview");
		if (title == null || table == null) {
			throw new SchoolLoginException(Kind.NOT_FOUND, "report_unrecognized");
		}
		List<LocalDateTime> dates = new ArrayList<>();
		for (Element row : table.select("> tbody > tr, > tr")) {
			Element th = row.selectFirst("> th");
			Element td = row.selectFirst("> td");
			String value = td == null ? null : clean(td.ownText());
			if (th == null || value == null) {
				continue;
			}
			LocalDateTime date = CourseRoomPage.dateTime(value, year, LocalTime.MIDNIGHT);
			if (date != null) {
				dates.add(date);
			}
		}
		Element submitForm = doc.selectFirst("#submit_form");
		Element info = submitForm == null ? null : submitForm.selectFirst(".submit_info_box");
		String when = info == null ? null : text(info.selectFirst(".date"));
		LocalDateTime submittedAt = when == null ? null : CourseRoomPage.dateTime(when, year, LocalTime.MIDNIGHT);
		String label = info == null ? null : text(info.selectFirst(".txt"));
		return new SchoolAssignment(limit(title), dates.isEmpty() ? null : dates.get(0),
				dates.size() < 2 ? null : dates.get(1), body(table.selectFirst(".editor_content")), files,
				submittedAt == null || label == null ? null : limit(label), submittedAt,
				submittedAt == null || submitForm == null ? null : body(submitForm.selectFirst(".editor_content")),
				submittedAt == null ? List.of() : submittedFiles);
	}

	/**
	 * 자료·공지 상세. 표의 칸 이름은 한국어나 영어로 그려지므로 이름으로 정하지 않는다: 날짜로 읽히는 첫 칸이 공개일·게시일이고,
	 * 작성자는 이름이 작성자(Writer)인 칸, 없으면 날짜도 조회수({@code td.count})도 아닌 첫 칸이다.
	 * @param year 과목 학년도. 연도 없는 날짜를 정한다
	 * @throws SchoolLoginException {@code NOT_FOUND}: 상세 화면이 아니다(지워졌거나 열 수 없는 글)
	 */
	static SchoolPost post(Document doc, List<SchoolFile> files, int year) {
		String title = text(doc.selectFirst(".view_title span, .view_title"));
		if (title == null || doc.selectFirst("table.bbsview") == null) {
			throw new SchoolLoginException(Kind.NOT_FOUND, "post_unrecognized");
		}
		LocalDateTime postedAt = null;
		String author = null;
		String firstOther = null;
		for (Element row : doc.select("table.bbsview tr")) {
			Element th = row.selectFirst("th");
			Element td = row.selectFirst("td");
			if (th == null || td == null || td.hasClass("count")) {
				continue;
			}
			String label = clean(th.text());
			String value = clean(td.text());
			if (value == null) {
				continue;
			}
			if (label != null && AUTHOR_LABELS.containsKey(label.toLowerCase(Locale.ROOT))) {
				author = limit(value);
				continue;
			}
			LocalDateTime date = CourseRoomPage.dateTime(value, year, LocalTime.MIDNIGHT);
			if (date != null) {
				postedAt = postedAt == null ? date : postedAt;
			}
			else if (firstOther == null) {
				firstOther = limit(value);
			}
		}
		return new SchoolPost(limit(title), postedAt, author != null ? author : firstOther,
				body(doc.selectFirst(".editor_content")), files);
	}

	/**
	 * 공지 목록. 화면 순서이고 같은 공지는 한 번만 둔다. 알아보지 못한 행(번호나 제목이 없는 것)은 버린다.
	 * @param year 과목 학년도. 게시일에 연도가 없다
	 * @param url 공지 번호로 학교에서 여는 주소를 만든다
	 * @throws SchoolLoginException {@code UNAVAILABLE}: 공지 목록 조각이 아니다
	 */
	static List<SchoolNotice> notices(Document doc, String html, int year, Function<String, String> url) {
		List<Element> rows = doc.select(".board_list_wrap[data-num]");
		if (rows.isEmpty() && !NOTICE_TOTAL.matcher(html).find() && doc.selectFirst(".file_paging") == null) {
			throw new SchoolLoginException(Kind.UNAVAILABLE, "notice_list_unrecognized");
		}
		Map<String, SchoolNotice> notices = new LinkedHashMap<>();
		for (Element row : rows) {
			String id = row.attr("data-num");
			String title = text(row.selectFirst(".board_title"));
			if (!DIGITS.matcher(id).matches() || title == null || notices.size() >= MAX_NOTICES) {
				continue;
			}
			Integer views = null;
			for (Element info : row.select(".board_list_info")) {
				String count = text(info.selectFirst(".board_list_text"));
				if (info.selectFirst(".comment") == null && count != null && DIGITS.matcher(count).matches()) {
					views = Integer.valueOf(count);
					break;
				}
			}
			String posted = text(row.selectFirst(".reg_info"));
			Element read = row.selectFirst(".is_read");
			String excerpt = text(row.selectFirst(".board_text"));
			notices.putIfAbsent(id, new SchoolNotice(id, limit(title), excerpt == null ? null : limit(excerpt),
					posted == null ? null : CourseRoomPage.dateTime(posted, year, LocalTime.MIDNIGHT), views,
					read == null || !read.hasClass("unread"), row.hasClass("expired"), url.apply(id)));
		}
		return List.copyOf(notices.values());
	}

	/** 공지 목록 조각이 알리는 전체 공지 수. 없으면 null. */
	static Integer noticeTotal(String html) {
		Matcher match = NOTICE_TOTAL.matcher(html);
		return match.find() ? Integer.valueOf(match.group(1)) : null;
	}

	/** 에디터 본문을 글자로 바꾼다. 문단·줄바꿈·목록은 줄바꿈이 되고 빈 줄은 하나로 줄인다. 없으면 null. */
	static String body(Element editor) {
		if (editor == null) {
			return null;
		}
		StringBuilder out = new StringBuilder();
		NodeTraversor.traverse(new NodeVisitor() {

			@Override
			public void head(Node node, int depth) {
				if (node instanceof TextNode text) {
					out.append(text.text().replace('\u00a0', ' '));
				}
				else if (node instanceof Element element) {
					if ("br".equals(element.normalName())) {
						out.append('\n');
					}
					else if (element.isBlock()) {
						lineEnd(out);
					}
				}
			}

			@Override
			public void tail(Node node, int depth) {
				if (node instanceof Element element && element.isBlock()) {
					lineEnd(out);
				}
			}

		}, editor);
		String text = out.toString()
			.replaceAll("[ \\t]+", " ")
			.replaceAll(" *\\n *", "\n")
			.replaceAll("\\n{3,}", "\n\n")
			.strip();
		if (text.isEmpty()) {
			return null;
		}
		return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text;
	}

	// 블록 경계는 줄바꿈 하나다. 빈 줄은 본문의 줄바꿈(br)으로만 생긴다.
	private static void lineEnd(StringBuilder out) {
		if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
			out.append('\n');
		}
	}

	/** "3.2MB", "405.8KB", "27 B" → 바이트 근사값. 알아보지 못하면 null. */
	static Long bytes(String label) {
		if (label == null) {
			return null;
		}
		Matcher match = SIZE.matcher(label);
		if (!match.find()) {
			return null;
		}
		long unit = switch (match.group(2).toUpperCase(Locale.ROOT)) {
			case "KB" -> 1024L;
			case "MB" -> 1024L * 1024;
			case "GB" -> 1024L * 1024 * 1024;
			default -> 1L;
		};
		return new BigDecimal(match.group(1)).multiply(BigDecimal.valueOf(unit)).longValue();
	}

	private static Map<String, String> query(Element link) {
		Map<String, String> values = new LinkedHashMap<>();
		if (link == null) {
			return values;
		}
		Matcher match = QUERY.matcher(link.attr("href").replace("&amp;", "&"));
		while (match.find()) {
			values.putIfAbsent(match.group(1), URLDecoder.decode(match.group(2), StandardCharsets.UTF_8));
		}
		return values;
	}

	private static String key(String value) {
		return value != null && KEY.matcher(value).matches() ? value : null;
	}

	private static String text(Element element) {
		return element == null ? null : clean(element.text());
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
	}

	private static String limit(String value) {
		return value.length() > MAX_NAME ? value.substring(0, MAX_NAME) : value;
	}

}
