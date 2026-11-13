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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolMaterial;
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
 * 자료 상세 lecture_material_view_form.acl
 *   .view_title span  제목
 *   table.bbsview tr th|td  공개일, 작성자, 조회수
 *   .editor_content  본문
 *   script  CONTENT_SEQ : "AGUM7AXY7C3KS"  (첨부 목록 요청에 쓰는 키)
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

	private static final Pattern SIZE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KB|MB|GB)", Pattern.CASE_INSENSITIVE);

	private static final Map<String, Boolean> AUTHOR_LABELS = Map.of("작성자", true, "writer", true, "author", true,
			"등록자", true);

	private SchoolContentPages() {
	}

	/** 파일모음의 파일. 화면 순서이고 같은 파일은 한 번만 둔다. */
	static List<SchoolFile> files(Document doc) {
		Map<String, SchoolFile> files = new LinkedHashMap<>();
		for (Element block : doc.select(".files_list")) {
			Map<String, String> target = query(block.selectFirst("a.file_target_artl"));
			String type = target.get("m") != null && TYPE.matcher(target.get("m")).matches() ? target.get("m") : null;
			String item = key(target.get("n"));
			for (SchoolFile file : attachments(block, type, item)) {
				if (files.size() >= MAX_FILES) {
					break;
				}
				files.putIfAbsent(file.fileSeq(), file);
			}
		}
		return List.copyOf(files.values());
	}

	/** 첨부 목록 조각(또는 그것을 담은 요소)의 파일. 화면 순서. */
	static List<SchoolFile> attachments(Element root, String activityType, String activityItemId) {
		List<SchoolFile> files = new ArrayList<>();
		for (Element row : root.select("ul.attach_list > li")) {
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

	/** 자료 상세 조각의 첨부 목록 키. 첨부 영역이 없으면 null. */
	static String contentSeq(String html) {
		Matcher match = CONTENT_SEQ.matcher(html);
		return match.find() ? match.group(1) : null;
	}

	/**
	 * @param year 과목 학년도. 연도 없는 날짜를 정한다
	 * @throws SchoolLoginException {@code NOT_FOUND}: 자료 상세 화면이 아니다(지워졌거나 열 수 없는 자료)
	 */
	static SchoolMaterial material(Document doc, List<SchoolFile> files, int year) {
		String title = text(doc.selectFirst(".view_title span, .view_title"));
		if (title == null || doc.selectFirst("table.bbsview") == null) {
			throw new SchoolLoginException(Kind.NOT_FOUND, "material_unrecognized");
		}
		LocalDateTime postedAt = null;
		String author = null;
		for (Element row : doc.select("table.bbsview tr")) {
			Element th = row.selectFirst("th");
			Element td = row.selectFirst("td");
			if (th == null || td == null) {
				continue;
			}
			String label = clean(th.text());
			String value = clean(td.text());
			if (label == null || value == null) {
				continue;
			}
			if (AUTHOR_LABELS.containsKey(label.toLowerCase(Locale.ROOT))) {
				author = limit(value);
			}
			else if (postedAt == null && !td.hasClass("count")) {
				postedAt = CourseRoomPage.dateTime(value, year, LocalTime.MIDNIGHT);
			}
		}
		return new SchoolMaterial(limit(title), postedAt, author, body(doc.selectFirst(".editor_content")), files);
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
