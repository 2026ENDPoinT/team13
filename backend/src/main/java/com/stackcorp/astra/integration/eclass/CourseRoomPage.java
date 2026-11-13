package com.stackcorp.astra.integration.eclass;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginException.Kind;

/**
 * 학교 e-Class 강의실 첫 화면({@code /ilos/cls/st/submain/submain_form.acl})의 주차와 학습활동 목록을 읽는다. 정규과목과 비정규과목이
 * 같은 화면이다(비정규는 "N 회차"이고 기간이 없다). 2026-11 기준 구조:
 *
 * <pre>
 * #week_list_wrap_3.week_list_container
 *   .week_title_wrap[data-week=3]  .week_period  "9월 15일 ~ 9월 21일"
 *   a.week_activity.week_activity_lecture_weeks[href="/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&amp;n=8936822"]
 *     .activity_title  제목
 *     .activity_info × N  (.activity_info_title "출석"|"학습시간"|"시작"|"마감", .activity_info_text 값)
 *     .submit_check_outer  (.activity_status "오프라인", .submit_check)   완료한 활동에만
 * </pre>
 *
 * 알아보지 못한 행(링크·제목이 없거나 이상한 것)은 버리고 나머지를 읽는다. 알아보지 못한 정보 칸은 null로 둔다. 화면 자체를 알아보지
 * 못하거나(강의실 표시가 없음) 다른 과목이 열렸으면 예외다.
 */
final class CourseRoomPage {

	static final int MAX_WEEKS = 60;

	static final int MAX_ACTIVITIES = 500;

	static final int MAX_TITLE = 300;

	private static final String CONNECT = "/ilos/mp/todo_list_connect.acl";

	private static final Pattern KEY_YEAR = Pattern.compile("^(?:[A-Z]|비)([0-9]{4})");

	private static final Pattern WEEK_ID = Pattern.compile("week_list_wrap_([0-9]{1,3})");

	private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,29}");

	private static final Pattern ITEM = Pattern.compile("[0-9A-Za-z_-]{1,40}");

	private static final Pattern QUERY = Pattern.compile("[?&](m|n)=([^&#]*)");

	private static final Pattern MONTH_DAY = Pattern.compile(
			"([0-9]{1,2})\\s*월\\s*([0-9]{1,2})\\s*일(?:\\s*\\(\\s*([일월화수목금토])\\s*\\))?(?:\\s*(오전|오후)?\\s*([0-9]{1,2}):([0-9]{2}))?");

	private static final Pattern DOTTED = Pattern.compile(
			"([0-9]{4})[.-]\\s*([0-9]{1,2})[.-]\\s*([0-9]{1,2})\\.?(?:\\s*\\(\\s*[일월화수목금토]\\s*\\))?(?:\\s*(오전|오후)?\\s*([0-9]{1,2}):([0-9]{2}))?");

	private static final Pattern CLOCK = Pattern.compile("(?:([0-9]{1,3}):)?([0-9]{1,2}):([0-9]{2})");

	private static final String DAYS = "월화수목금토일";

	/**
	 * 정보 칸 이름 → 뜻. 학교 화면은 같은 session에서도 과목에 따라 영어로 그려진다(2026-11 관찰: Attendance, Studying Time,
	 * Deadline, Expired).
	 */
	private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("출석", "attendance"),
			Map.entry("attendance", "attendance"), Map.entry("학습시간", "time"), Map.entry("studying time", "time"),
			Map.entry("learning time", "time"), Map.entry("study time", "time"), Map.entry("시작", "open"),
			Map.entry("start", "open"), Map.entry("마감", "close"), Map.entry("deadline", "close"),
			Map.entry("due", "close"), Map.entry("due date", "close"));

	private static final String MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec";

	private static final String WEEKDAYS = "sun|mon|tue|wed|thu|fri|sat";

	/**
	 * 영어 화면의 날짜. "Wed, November 18 11:59 PM", "September 9 ~ September 15", "Sep 18 (Fri) 11:59 PM",
	 * "September 18, 2026 23:59".
	 */
	private static final Pattern ENGLISH = Pattern.compile("(?:\\b(?<lead>" + WEEKDAYS + ")[a-z]*\\.?,?\\s*)?\\b(?<month>"
			+ MONTHS + ")[a-z]*\\.?\\s*(?<day>[0-9]{1,2})(?:st|nd|rd|th)?(?:,?\\s*(?<year>[0-9]{4}))?(?:\\s*\\(\\s*(?<paren>"
			+ WEEKDAYS + ")[a-z]*\\.?\\s*\\))?(?:\\s*(?<hour>[0-9]{1,2}):(?<minute>[0-9]{2})\\s*(?<half>am|pm)?)?",
			Pattern.CASE_INSENSITIVE);

	private CourseRoomPage() {
	}

	/** 과목 키의 학년도. 화면 날짜에 연도가 없어 이 해를 기준으로 정한다. 키가 이상하면 null. */
	static Integer yearOf(String courseKey) {
		Matcher match = KEY_YEAR.matcher(courseKey == null ? "" : courseKey);
		return match.find() ? Integer.valueOf(match.group(1)) : null;
	}

	/**
	 * @param base 학교 e-Class origin. 활동을 여는 주소를 만든다
	 * @throws SchoolLoginException 강의실 화면이 아니거나 다른 과목의 화면일 때
	 */
	static SchoolCourseRoom read(Document doc, String courseKey, int year, URI base) {
		Element key = doc.selectFirst("input#KJ_KEY");
		if (key != null && !key.val().isBlank() && !courseKey.equals(key.val().strip())) {
			throw new SchoolLoginException(Kind.UNAVAILABLE, "room_mismatch");
		}
		List<Element> containers = doc.select(".week_list_container");
		if (key == null && containers.isEmpty() && doc.selectFirst(".activity_list_container") == null) {
			throw new SchoolLoginException(Kind.UNAVAILABLE, "room_unrecognized");
		}
		Map<Integer, SchoolWeek> weeks = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		int activities = 0;
		LocalDate previous = null;
		int rollover = 0;
		for (Element container : containers) {
			Integer weekNo = weekNo(container);
			if (weekNo == null || weeks.containsKey(weekNo) || weeks.size() == MAX_WEEKS) {
				continue;
			}
			LocalDate[] period = period(text(container.selectFirst(".week_period")), year + rollover);
			if (period != null && previous != null && period[0].isBefore(previous.minusDays(60))) {
				// 겨울학기처럼 해를 넘긴 주차. 화면 기간에 연도가 없어 앞 주차보다 크게 앞서면 다음 해로 본다.
				rollover++;
				period = period(text(container.selectFirst(".week_period")), year + rollover);
			}
			if (period != null) {
				previous = period[0];
			}
			List<SchoolActivity> rows = new ArrayList<>();
			for (Element row : container.select("a.week_activity")) {
				if (activities == MAX_ACTIVITIES) {
					break;
				}
				SchoolActivity activity;
				try {
					activity = activity(row, courseKey, year + rollover, base);
				}
				catch (RuntimeException ex) {
					// 예상하지 못한 행 하나 때문에 과목 전체를 버리지 않는다.
					activity = null;
				}
				if (activity != null && seen.add(activity.type() + "/" + activity.itemId())) {
					rows.add(activity);
					activities++;
				}
			}
			weeks.put(weekNo, new SchoolWeek(weekNo, period == null ? null : period[0],
					period == null ? null : period[1], rows));
		}
		return new SchoolCourseRoom(List.copyOf(weeks.values()));
	}

	private static Integer weekNo(Element container) {
		Matcher id = WEEK_ID.matcher(container.id());
		String value = id.matches() ? id.group(1) : null;
		if (value == null) {
			Element title = container.selectFirst(".week_title_wrap[data-week]");
			value = title == null ? null : title.attr("data-week").strip();
		}
		if (value == null || !value.matches("[0-9]{1,3}")) {
			return null;
		}
		int number = Integer.parseInt(value);
		return number >= 1 && number <= MAX_WEEKS ? number : null;
	}

	private static SchoolActivity activity(Element row, String courseKey, int year, URI base) {
		String type = null;
		String item = null;
		Matcher query = QUERY.matcher(row.attr("href").replace("&amp;", "&"));
		while (query.find()) {
			String value = decode(query.group(2));
			if ("m".equals(query.group(1))) {
				type = value;
			}
			else {
				item = value;
			}
		}
		String title = text(row.selectFirst(".activity_title"));
		if (type == null || item == null || title == null || !TYPE.matcher(type).matches()
				|| !ITEM.matcher(item).matches()) {
			return null;
		}
		String kind = null;
		for (String name : row.classNames()) {
			if (name.startsWith("week_activity_")) {
				String suffix = name.substring("week_activity_".length());
				if (!suffix.equals(type) && TYPE.matcher(suffix).matches()) {
					kind = suffix;
				}
			}
		}
		LocalDateTime openAt = null;
		LocalDateTime closeAt = null;
		boolean expired = false;
		String attendance = null;
		Integer learningTime = null;
		for (Element info : row.select(".activity_info")) {
			String label = text(info.selectFirst(".activity_info_title"));
			String value = text(info.selectFirst(".activity_info_text"));
			if (label == null || value == null) {
				continue;
			}
			switch (LABELS.getOrDefault(label.toLowerCase(Locale.ROOT), "")) {
				case "attendance" -> attendance = value.length() <= 40 ? value : null;
				case "time" -> learningTime = seconds(value);
				case "open" -> openAt = dateTime(value, year, LocalTime.MIDNIGHT);
				case "close" -> {
					expired = value.contains("만료") || value.toLowerCase(Locale.ROOT).contains("expired");
					closeAt = expired ? null : dateTime(value, year, LocalTime.of(23, 59));
				}
				default -> {
				}
			}
		}
		Element status = row.selectFirst(".activity_status");
		boolean offline = status != null
				&& (status.text().contains("오프라인") || status.text().toLowerCase(Locale.ROOT).contains("offline"));
		boolean done = row.selectFirst(".submit_check, .activity_complete") != null;
		return new SchoolActivity(type, kind, item, limit(title), openAt, closeAt, expired, attendance, learningTime,
				offline, done, connect(base, courseKey, type, item));
	}

	/** 학교 홈의 할 일 항목이 쓰는 연결 주소. 과목을 session에 묶고 그 활동으로 넘긴다. */
	private static String connect(URI base, String courseKey, String type, String item) {
		return base.resolve(CONNECT) + "?SEQ=" + encode(item) + "&gubun=" + encode(type) + "&KJKEY="
				+ encode(courseKey);
	}

	/** "9월 3일 ~ 9월 9일". 끝 달이 시작 달보다 앞이면 다음 해다. 없거나 알아보지 못하면 null. */
	static LocalDate[] period(String value, int year) {
		if (value == null) {
			return null;
		}
		Matcher match = MONTH_DAY.matcher(value);
		LocalDate start = match.find() ? date(year, match.group(1), match.group(2)) : null;
		LocalDate end = start != null && match.find() ? date(year, match.group(1), match.group(2)) : null;
		if (start == null) {
			Matcher english = ENGLISH.matcher(value);
			start = english.find() ? englishDate(english, year) : null;
			end = start != null && english.find() ? englishDate(english, year) : null;
		}
		if (start == null || end == null) {
			return null;
		}
		if (end.isBefore(start)) {
			end = end.plusYears(1);
		}
		return new LocalDate[] { start, end };
	}

	/**
	 * "12월 11일 (금) 오후 11:59", "D-2 9월 22일 (화) 오후 11:59", "2026.09.22 23:59". 요일이 있으면 학년도 앞뒤 한 해 가운데 요일이
	 * 맞는 해를 고른다. 시각이 없으면 {@code missingTime}이다. "시작됨"처럼 날짜가 없으면 null.
	 */
	static LocalDateTime dateTime(String value, int year, LocalTime missingTime) {
		Matcher dotted = DOTTED.matcher(value);
		if (dotted.find()) {
			LocalDate date = date(Integer.parseInt(dotted.group(1)), dotted.group(2), dotted.group(3));
			return date == null ? null : date.atTime(time(dotted.group(4), dotted.group(5), dotted.group(6), missingTime));
		}
		Matcher match = MONTH_DAY.matcher(value);
		if (!match.find()) {
			return english(value, year, missingTime);
		}
		LocalDate date = null;
		if (match.group(3) != null) {
			DayOfWeek day = DayOfWeek.of(DAYS.indexOf(match.group(3)) + 1);
			for (int candidate : new int[] { year, year + 1, year - 1 }) {
				LocalDate guess = date(candidate, match.group(1), match.group(2));
				if (guess != null && guess.getDayOfWeek() == day) {
					date = guess;
					break;
				}
			}
		}
		if (date == null) {
			date = date(year, match.group(1), match.group(2));
		}
		return date == null ? null : date.atTime(time(match.group(4), match.group(5), match.group(6), missingTime));
	}

	private static LocalDateTime english(String value, int year, LocalTime missingTime) {
		Matcher match = ENGLISH.matcher(value);
		if (!match.find()) {
			return null;
		}
		String month = month(match);
		LocalDate date = null;
		String weekday = match.group("paren") != null ? match.group("paren") : match.group("lead");
		if (match.group("year") != null) {
			date = date(Integer.parseInt(match.group("year")), month, match.group("day"));
		}
		else if (weekday != null) {
			DayOfWeek day = DayOfWeek.of((WEEKDAYS.indexOf(weekday.toLowerCase(Locale.ROOT)) / 4 + 6) % 7 + 1);
			for (int candidate : new int[] { year, year + 1, year - 1 }) {
				LocalDate guess = date(candidate, month, match.group("day"));
				if (guess != null && guess.getDayOfWeek() == day) {
					date = guess;
					break;
				}
			}
		}
		if (date == null) {
			date = date(year, month, match.group("day"));
		}
		String half = match.group("half") == null ? null : match.group("half").equalsIgnoreCase("pm") ? "오후" : "오전";
		return date == null ? null
				: date.atTime(time(half, match.group("hour"), match.group("minute"), missingTime));
	}

	private static String month(Matcher match) {
		return String.valueOf(MONTHS.indexOf(match.group("month").toLowerCase(Locale.ROOT)) / 4 + 1);
	}

	private static LocalTime time(String half, String hour, String minute, LocalTime missing) {
		if (hour == null) {
			return missing;
		}
		int h = Integer.parseInt(hour);
		int m = Integer.parseInt(minute);
		if ("오후".equals(half) && h < 12) {
			h += 12;
		}
		else if ("오전".equals(half) && h == 12) {
			h = 0;
		}
		return h < 24 && m < 60 ? LocalTime.of(h, m) : missing;
	}

	private static LocalDate englishDate(Matcher match, int year) {
		return date(match.group("year") == null ? year : Integer.parseInt(match.group("year")), month(match),
				match.group("day"));
	}

	/** "25:05", "0:00", "1:38:27". */
	static Integer seconds(String value) {
		Matcher match = CLOCK.matcher(value.strip());
		if (!match.matches()) {
			return null;
		}
		int hours = match.group(1) == null ? 0 : Integer.parseInt(match.group(1));
		int minutes = Integer.parseInt(match.group(2));
		int seconds = Integer.parseInt(match.group(3));
		if (seconds >= 60 || (match.group(1) != null && minutes >= 60)) {
			return null;
		}
		return hours * 3600 + minutes * 60 + seconds;
	}

	private static LocalDate date(int year, String month, String day) {
		try {
			return LocalDate.of(year, Integer.parseInt(month), Integer.parseInt(day));
		}
		catch (DateTimeException | NumberFormatException ex) {
			return null;
		}
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8).strip();
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String limit(String value) {
		return value.codePointCount(0, value.length()) <= MAX_TITLE ? value
				: value.substring(0, value.offsetByCodePoints(0, MAX_TITLE));
	}

	private static String text(Element element) {
		if (element == null) {
			return null;
		}
		String value = element.text().replaceAll("\\s+", " ").strip();
		return value.isEmpty() ? null : value;
	}

}
