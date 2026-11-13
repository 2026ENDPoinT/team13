package com.stackcorp.astra.integration.eclass;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceSession;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusInstructor;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideoContent;
import com.stackcorp.astra.identity.api.SchoolLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginException.Kind;

/**
 * 학교 e-Class 강의실의 영상 상세, 강의계획서, 출석, 성적 화면을 읽는다. 2026-11 기준 구조. 같은 과목도 영어로 그려지므로 칸 이름이
 * 아니라 class, id, 자리로 읽는다. 알아보지 못한 행은 버린다.
 *
 * <pre>
 * 영상 online_view_form.acl
 *   form[name=learningForm]  (학습창으로 가는 form. 보내지 않는다)
 *   .online_info_left .online_info .online_info_text        출석부 반영 차시 "3 주 1차시"
 *                                  .online_info_text.date span × 2   출석인정기간 시작 ~, 끝
 *   .online_contents_list
 *     .attend_progress_bar[style=width: p%]  .attend_progress_bar.ex[style=left: p%; width: q%]
 *     button.online_contents_wrap[onclick=learningGo('1')]  .percent "100%"  .video_title
 *       .online_contents_badge × 2 (span 이름, span 시간)   첫째 학습시간, 둘째 출석인정
 * 강의계획서 regular_view_plan.acl
 *   .info_box .reader_profile p × 2   강사(이름, 이메일)
 *   table.bbsview × n   첫째 과목(4칸 행 3개), 다음 4칸 표는 교수 정보, 2칸 표는 서술 항목 6행, 주 번호 행이 있는 표는 주별 계획
 * 출석 attendance_list.acl
 *   .attendance_list#week_list_wrap_N  ul.attendance_week_time_list
 *     li (.attendance_date_box .date, .lect_time "14:00 ~ 17:00")  .attend_menu(.ready|.cancel|.supply)
 *       .attend_dv  .week_time "Session 1"  .attend_stats .attend_dv_icon.(attend|tardy|absence|ex_absence)
 *     li.attendance_make_up_box (보강)
 * 성적 eval3_result_view_detail_list.acl
 *   a.eval_list[href=…activity_form.acl?m=report&amp;n=…]  .list_icon.(report1|test1)  .submit_check  .status:not(.hide) 오프라인
 *     .eval_view_contents_item .font_caption2  마감 일시 | "&lt;span&gt;점수|미공개|-&lt;/span&gt; / 3점"
 * </pre>
 */
final class SchoolScreenPages {

	static final int MAX_ROWS = 500;

	static final int MAX_TEXT = 4000;

	private static final Pattern WIDTH = Pattern.compile("(?:^|;)\\s*width\\s*:\\s*([0-9]{1,4}(?:\\.[0-9]{1,3})?)\\s*%");

	private static final Pattern PERCENT = Pattern.compile("([0-9]{1,4})\\s*%");

	private static final Pattern LEARNING_GO = Pattern.compile("learningGo\\(\\s*'([0-9]{1,6})'");

	private static final Pattern WEEK_ID = Pattern.compile("week_list_wrap_([0-9]{1,3})");

	private static final Pattern NUMBER = Pattern.compile("([0-9]{1,3})");

	private static final Pattern TIMES = Pattern.compile("([0-9]{1,2}):([0-9]{2})\\s*~\\s*([0-9]{1,2}):([0-9]{2})");

	private static final Pattern SCORE = Pattern.compile("-?[0-9]{1,6}(?:\\.[0-9]{1,3})?");

	private static final Pattern QUERY = Pattern.compile("[?&](m|n)=([^&#]*)");

	private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,29}");

	private static final Pattern ITEM = Pattern.compile("[0-9A-Za-z_-]{1,40}");

	private static final Pattern PLAN_FUNCTION = Pattern.compile("function\\s+lecPlanViewPop\\s*\\(");

	/** 강의계획서 요청에 학교 화면이 보내는 값. 강의실 첫 화면의 {@code lecPlanViewPop()} 안 data에 있다. */
	static final List<String> PLAN_KEYS = List.of("KJKEY", "ORG_SECT", "YEAR", "TERM", "LEDG_YEAR", "LEDG_SESSN",
			"LSSN_CD", "SUBJT_NO");

	private static final Pattern PLAN_VALUE = Pattern.compile("[0-9A-Za-z]{0,30}");

	private static final Set<String> ICON_NOISE = Set.of("list_icon", "menu_icon");

	private SchoolScreenPages() {
	}

	/**
	 * 영상 활동 상세.
	 * @param year 과목 학년도. 연도 없는 날짜를 정한다
	 * @throws SchoolLoginException {@code NOT_FOUND}: 영상 상세 화면이 아니다
	 */
	static SchoolVideo video(Document doc, int year) {
		if (doc.selectFirst(".online_contents_list_wrap, .online_contents_container") == null) {
			throw new SchoolLoginException(Kind.NOT_FOUND, "video_unrecognized");
		}
		String attendanceWeek = null;
		LocalDateTime start = null;
		LocalDateTime end = null;
		for (Element info : doc.select(".online_info_left .online_info .online_info_text")) {
			if (info.hasClass("date")) {
				Elements spans = info.select("span");
				if (spans.size() >= 2) {
					start = date(text(spans.get(0)), year);
					end = date(text(spans.get(1)), year);
				}
			}
			else if (attendanceWeek == null) {
				attendanceWeek = limit(text(info));
			}
		}
		List<SchoolVideoContent> contents = new ArrayList<>();
		int order = 0;
		for (Element card : doc.select(".online_contents_list")) {
			order++;
			Element button = card.selectFirst(".online_contents_wrap");
			String title = text(card.selectFirst(".video_title"));
			if (button == null || title == null || contents.size() >= MAX_ROWS) {
				continue;
			}
			Matcher go = LEARNING_GO.matcher(button.attr("onclick"));
			int no = go.find() ? Integer.parseInt(go.group(1)) : order;
			BigDecimal in = null;
			BigDecimal out = null;
			for (Element bar : card.select("> .attend_progress_bar")) {
				Matcher width = WIDTH.matcher(bar.attr("style"));
				BigDecimal value = width.find() ? new BigDecimal(width.group(1)) : null;
				if (bar.hasClass("ex")) {
					out = out == null ? value : out;
				}
				else {
					in = in == null ? value : in;
				}
			}
			Matcher percent = PERCENT.matcher(text(card.selectFirst(".percent")) == null ? ""
					: text(card.selectFirst(".percent")));
			Elements badges = card.select(".online_contents_badge");
			contents.add(new SchoolVideoContent(no, limit(title), percent.find() ? Integer.valueOf(percent.group(1)) : null,
					in, out, badges.size() >= 1 ? clock(badges.get(0)) : null,
					badges.size() >= 2 ? clock(badges.get(1)) : null));
		}
		return new SchoolVideo(attendanceWeek, start, end, contents);
	}

	// 배지의 마지막 span이 시간이다. 앞의 span은 이름(학습시간, Studying Time 등)이다.
	private static Integer clock(Element badge) {
		Elements spans = badge.select("span");
		String value = spans.isEmpty() ? null : text(spans.last());
		return value == null ? null : CourseRoomPage.seconds(value);
	}

	/**
	 * 강의실 첫 화면의 강의계획서 요청 값({@code lecPlanViewPop()} 안 data). 계획서 버튼이 없으면(비정규 과정) null.
	 */
	static Map<String, String> planParameters(String roomHtml) {
		Matcher function = PLAN_FUNCTION.matcher(roomHtml);
		if (!function.find()) {
			return null;
		}
		String block = roomHtml.substring(function.end(), Math.min(roomHtml.length(), function.end() + 3000));
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : PLAN_KEYS) {
			Matcher match = Pattern.compile("\\b" + key + "\\s*:\\s*[\"']([^\"']*)[\"']").matcher(block);
			if (!match.find() || !PLAN_VALUE.matcher(match.group(1)).matches()) {
				return null;
			}
			values.put(key, match.group(1));
		}
		return values;
	}

	/**
	 * 강의계획서. 표는 자리로 읽는다: 첫 4칸 표는 과목, 그다음 4칸 표들은 교수(표마다 한 사람), 행마다 칸이 둘인 표는 서술 항목
	 * (과목개요, 교육목표, 학습성과관련도, 교재, 기자재, 성적평가 순서), 주 번호 칸이 있는 표는 주별 계획이다.
	 * @throws SchoolLoginException {@code NOT_FOUND}: 계획서 표가 없다
	 */
	static SchoolSyllabus syllabus(Document doc) {
		Elements tables = doc.select("table.bbsview");
		if (tables.isEmpty()) {
			throw new SchoolLoginException(Kind.NOT_FOUND, "syllabus_unrecognized");
		}
		List<String> course = null;
		List<SchoolSyllabusInstructor> instructors = new ArrayList<>();
		List<String> narrative = new ArrayList<>();
		List<SchoolSyllabusWeek> weeks = new ArrayList<>();
		for (Element table : tables) {
			List<Element> rows = table.select("tr");
			if (rows.stream().anyMatch(SchoolScreenPages::weekRow)) {
				for (Element row : rows) {
					if (!weekRow(row) || weeks.size() >= 30) {
						continue;
					}
					Elements cells = row.select("> td");
					weeks.add(new SchoolSyllabusWeek(Integer.parseInt(text(row.selectFirst("> th"))),
							longText(cells.get(0)), cells.size() > 1 ? longText(cells.get(1)) : null));
				}
				continue;
			}
			List<String> values = new ArrayList<>();
			boolean pairs = true;
			for (Element row : rows) {
				Elements cells = row.select("> td");
				if (row.select("> th").isEmpty() || cells.isEmpty()) {
					continue;
				}
				pairs &= cells.size() == 1 && row.select("> th").size() == 1;
				for (Element cell : cells) {
					values.add(longText(cell));
				}
			}
			if (values.isEmpty()) {
				continue;
			}
			if (pairs && course != null) {
				narrative.addAll(values);
			}
			else if (course == null) {
				course = values;
			}
			else if (instructors.size() < 10) {
				instructors.add(new SchoolSyllabusInstructor(at(values, 0), at(values, 1), at(values, 2), at(values, 3),
						at(values, 4), at(values, 5), at(values, 6)));
			}
		}
		List<String> c = course == null ? List.of() : course;
		return new SchoolSyllabus(at(c, 0), at(c, 1), at(c, 2), at(c, 3), at(c, 4), at(c, 5), instructors,
				at(narrative, 0), at(narrative, 1), at(narrative, 2), at(narrative, 3), at(narrative, 4),
				at(narrative, 5), weeks);
	}

	private static boolean weekRow(Element row) {
		String th = text(row.selectFirst("> th"));
		return th != null && th.matches("[0-9]{1,2}") && !row.select("> td").isEmpty();
	}

	/**
	 * 학생 본인의 출석. 주차는 카드 id의 번호다. 차시 번호는 차시 이름의 숫자이고 없으면 그 주차 안 순서다.
	 * @param year 과목 학년도
	 */
	static List<SchoolAttendanceWeek> attendance(Document doc, int year) {
		List<SchoolAttendanceWeek> weeks = new ArrayList<>();
		int rows = 0;
		for (Element card : doc.select(".attendance_list[id]")) {
			Matcher id = WEEK_ID.matcher(card.id());
			if (!id.matches()) {
				continue;
			}
			List<SchoolAttendanceSession> sessions = new ArrayList<>();
			LocalDate date = null;
			LocalTime[] times = null;
			// 한 li 안에 날짜 상자와 차시가 화면 순서로 온다. 보강 상자(li.attendance_make_up_box)에는 날짜 상자와 차시 쌍이 여럿이다.
			for (Element item : card.select("ul.attendance_week_time_list > li")) {
				boolean makeupBox = item.hasClass("attendance_make_up_box");
				for (Element part : item.select(".attendance_date_box, .attend_menu:not(.make_up_attend_menu)")) {
					if (part.hasClass("attendance_date_box")) {
						date = day(text(part.selectFirst(".date")), year);
						times = times(text(part.selectFirst(".lect_time")));
						continue;
					}
					if (rows >= MAX_ROWS) {
						break;
					}
					sessions.add(session(part, makeupBox, date, times, sessions.size() + 1));
					rows++;
				}
			}
			weeks.add(new SchoolAttendanceWeek(Integer.parseInt(id.group(1)), sessions));
		}
		return weeks;
	}

	private static SchoolAttendanceSession session(Element menu, boolean makeupBox, LocalDate date, LocalTime[] times,
			int order) {
		String name = text(menu.selectFirst(".week_time"));
		Matcher number = NUMBER.matcher(name == null ? "" : name);
		int sessionNo = number.find() ? Integer.parseInt(number.group(1)) : order;
		Element icon = menu.selectFirst(".attend_stats .attend_dv_icon");
		String status = null;
		if (icon != null && !menu.hasClass("ready")) {
			status = icon.classNames()
				.stream()
				.filter((c) -> !"attend_dv_icon".equals(c) && TYPE.matcher(c).matches())
				.findFirst()
				.orElse(null);
		}
		Element label = menu.selectFirst(".attend_stats .attend_dv_icon_title");
		return new SchoolAttendanceSession(sessionNo, date, times == null ? null : times[0],
				times == null ? null : times[1], limit(text(menu.selectFirst(".attend_dv"))),
				makeupBox || menu.hasClass("supply"), menu.hasClass("cancel"), status,
				status == null ? null : limit(text(label)));
	}

	/**
	 * 학생 본인의 평가 활동. 점수 칸은 "획득 / 배점"이다: 획득이 숫자면 공개된 점수, {@code -}면 공개됐지만 점수가 없고, 그 밖의
	 * 글자(미공개, Private 등)면 공개 전이다.
	 * @param year 과목 학년도
	 */
	static List<SchoolGradeItem> grades(Document doc, int year) {
		List<SchoolGradeItem> items = new ArrayList<>();
		for (Element row : doc.select("a.eval_list[href]")) {
			Map<String, String> query = new LinkedHashMap<>();
			Matcher match = QUERY.matcher(row.attr("href").replace("&amp;", "&"));
			while (match.find()) {
				query.putIfAbsent(match.group(1), match.group(2));
			}
			String type = query.get("m");
			String item = query.get("n");
			Element icon = row.selectFirst(".eval_list_title .list_icon");
			String title = text(icon);
			if (type == null || !TYPE.matcher(type).matches() || item == null || !ITEM.matcher(item).matches()
					|| title == null || items.size() >= MAX_ROWS) {
				continue;
			}
			String kind = icon.classNames()
				.stream()
				.filter((c) -> !ICON_NOISE.contains(c) && !c.startsWith("font_") && TYPE.matcher(c).matches())
				.findFirst()
				.filter((c) -> !c.equals(type))
				.orElse(null);
			LocalDateTime closeAt = null;
			boolean released = false;
			BigDecimal score = null;
			BigDecimal max = null;
			for (Element value : row.select(".eval_view_contents_item .font_caption2")) {
				String all = text(value);
				if (all == null) {
					continue;
				}
				int slash = all.lastIndexOf('/');
				if (slash >= 0) {
					String got = text(value.selectFirst("span"));
					got = got == null ? all.substring(0, slash).strip() : got;
					Matcher maxMatch = SCORE.matcher(all.substring(slash + 1));
					max = maxMatch.find() ? new BigDecimal(maxMatch.group()) : null;
					if (SCORE.matcher(got).matches()) {
						released = true;
						score = new BigDecimal(got);
					}
					else {
						released = "-".equals(got);
					}
				}
				else if (closeAt == null) {
					closeAt = CourseRoomPage.dateTime(all, year, LocalTime.of(23, 59));
				}
			}
			Element status = row.selectFirst(".submit_check_outer .status");
			items.add(new SchoolGradeItem(type, kind, item, limit(title), closeAt,
					row.selectFirst(".submit_check") != null, status != null && !status.hasClass("hide"), released,
					score, max));
		}
		return items;
	}

	private static LocalDateTime date(String value, int year) {
		return value == null ? null : CourseRoomPage.dateTime(value, year, LocalTime.MIDNIGHT);
	}

	private static LocalDate day(String value, int year) {
		LocalDateTime date = date(value, year);
		return date == null ? null : date.toLocalDate();
	}

	private static LocalTime[] times(String value) {
		Matcher match = TIMES.matcher(value == null ? "" : value);
		if (!match.find()) {
			return null;
		}
		int sh = Integer.parseInt(match.group(1));
		int eh = Integer.parseInt(match.group(3));
		int sm = Integer.parseInt(match.group(2));
		int em = Integer.parseInt(match.group(4));
		if (sh > 23 || eh > 23 || sm > 59 || em > 59) {
			return null;
		}
		return new LocalTime[] { LocalTime.of(sh, sm), LocalTime.of(eh, em) };
	}

	private static String at(List<String> values, int index) {
		return index < values.size() ? values.get(index) : null;
	}

	// 칸 안 줄바꿈(br, 문단)은 줄바꿈으로 둔다.
	private static String longText(Element cell) {
		String text = SchoolContentPages.body(cell);
		if (text == null) {
			return null;
		}
		return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
	}

	private static String text(Element element) {
		if (element == null) {
			return null;
		}
		String value = element.text().replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
		return value.isEmpty() ? null : value;
	}

	private static String limit(String value) {
		if (value == null) {
			return null;
		}
		return value.length() > SchoolContentPages.MAX_NAME ? value.substring(0, SchoolContentPages.MAX_NAME) : value;
	}

}
