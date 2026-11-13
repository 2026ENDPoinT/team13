package com.stackcorp.astra.integration.eclass;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolCourse;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.Slot;

/**
 * 학교 e-Class 메인 화면({@code /ilos/main/main_form.acl})의 수강과목 카드({@code a.sub_open[kj]})를 읽는다. 카드 하나는 이렇게
 * 생겼다(2026-11 기준).
 *
 * <pre>
 * &lt;a class="sub_open" kj="A2026310982911001" kj_year="2026" kj_term="3" ...&gt;
 *   .card_background_cover  style="background-image: url(.../cover/dynamic_13.png?h=150)"
 *   .card_title  Linear Algebra        .card_info  109829-11001        .card_text  손일수
 *   .card_bottom .info_txt  목(1 ~ 3) | 3학점          (비정규: 2026.05.13~2026.12.21 | 이러닝수업)
 * </pre>
 *
 * 읽지 못한 칸은 null로 두고 카드를 버리지 않는다. 키가 이상한 카드만 버린다.
 */
final class MainPageCourses {

	static final int MAX_COURSES = 60;

	private static final Pattern REGULAR_KEY = Pattern.compile("[A-Z][0-9]{4}[0-9A-Z]{1,3}[0-9A-Za-z]{4,20}");

	private static final Pattern NONREGULAR_KEY = Pattern.compile("비[0-9]{4}[A-Z][0-9A-Za-z]{2,20}");

	private static final Pattern TERM_CODE = Pattern.compile("[0-9A-Z]{1,3}");

	private static final Pattern CODE_SECTION = Pattern.compile("([0-9A-Za-z]*)-([0-9A-Za-z]+)");

	private static final Pattern SLOT = Pattern.compile("([일월화수목금토])\\s*\\(\\s*([0-9]{1,2})\\s*(?:~\\s*([0-9]{1,2})\\s*)?\\)");

	private static final Pattern CREDIT = Pattern.compile("([0-9]{1,2}(?:\\.[0-9])?)\\s*학점");

	private static final Pattern PERIOD = Pattern.compile("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}\\s*~\\s*[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}");

	private static final Pattern COVER = Pattern.compile("/cover/((?:dynamic|gradient)_[0-9]{1,3})\\.");

	private static final String DAYS = "일월화수목금토";

	private MainPageCourses() {
	}

	static List<SchoolCourse> read(Document main) {
		Map<String, SchoolCourse> courses = new LinkedHashMap<>();
		for (Element card : main.select("a.sub_open[kj]")) {
			if (courses.size() == MAX_COURSES) {
				break;
			}
			SchoolCourse course = card(card);
			if (course != null) {
				courses.putIfAbsent(course.key(), course);
			}
		}
		return List.copyOf(courses.values());
	}

	private static SchoolCourse card(Element card) {
		String key = card.attr("kj").strip();
		boolean regular = REGULAR_KEY.matcher(key).matches();
		if (!regular && !NONREGULAR_KEY.matcher(key).matches()) {
			return null;
		}
		String name = text(card.selectFirst(".card_title"));
		Integer year = number(card.attr("kj_year"));
		String term = card.attr("kj_term").strip();
		if (name == null || name.length() > 300 || year == null || year < 2000 || year > 2100
				|| !TERM_CODE.matcher(term).matches()) {
			return null;
		}
		String code = null;
		String section = null;
		Matcher codeSection = CODE_SECTION.matcher(String.valueOf(text(card.selectFirst(".card_info"))));
		if (codeSection.matches()) {
			code = codeSection.group(1).isEmpty() ? null : limit(codeSection.group(1), 20);
			section = limit(codeSection.group(2), 10);
		}
		List<Slot> slots = new ArrayList<>();
		BigDecimal credit = null;
		String period = null;
		String lectureType = null;
		for (Element info : card.select(".card_bottom .info_txt")) {
			String value = text(info);
			if (value == null) {
				continue;
			}
			Matcher slot = SLOT.matcher(value);
			boolean found = false;
			while (slot.find()) {
				found = true;
				int start = Integer.parseInt(slot.group(2));
				int end = slot.group(3) == null ? start : Integer.parseInt(slot.group(3));
				if (start <= end && end <= 30) {
					slots.add(new Slot(DAYS.indexOf(slot.group(1)), start, end));
				}
			}
			Matcher creditMatch = CREDIT.matcher(value);
			if (found) {
				continue;
			}
			if (creditMatch.matches()) {
				credit = new BigDecimal(creditMatch.group(1));
			}
			else if (PERIOD.matcher(value).matches()) {
				period = value.replace(" ", "");
			}
			else if (!regular && value.length() <= 20) {
				lectureType = value;
			}
		}
		Matcher cover = COVER.matcher(card.select(".card_background_cover").attr("style"));
		return new SchoolCourse(key, name, regular, year, term, code, section,
				limit(text(card.selectFirst(".card_text")), 200), slots, credit, period, lectureType,
				cover.find() ? cover.group(1) : null);
	}

	private static Integer number(String value) {
		return value.strip().matches("[0-9]{1,4}") ? Integer.valueOf(value.strip()) : null;
	}

	private static String limit(String value, int max) {
		return value == null || value.length() > max ? null : value;
	}

	private static String text(Element element) {
		if (element == null) {
			return null;
		}
		String value = element.text().replaceAll("\\s+", " ").strip();
		return value.isEmpty() ? null : value;
	}

}
