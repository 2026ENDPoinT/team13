package com.stackcorp.astra.home;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.home.AssistantContext.CourseRef;
import com.stackcorp.astra.home.AssistantContext.ItemRef;
import com.stackcorp.astra.home.AssistantContext.Snapshot;

/**
 * 모델 없이 바로 답하는 분명한 명령(ADR-0044). Frontend-final(origin/main e44daf9)이 서버 모드에서 직접 처리하던 명령을 옮겼다:
 * {@code areas/home/command-bar.tsx}의 {@code submitCommand}(강의실 이동, 할 일 목록), {@code shared/assistant/classrooms.ts}
 * ({@code classroomForCommand}, {@code searchClassrooms}), {@code shared/assistant/course-match.ts}({@code abbreviationMatch}),
 * {@code shared/assistant/server-home.ts}({@code serverActionForCommand}, {@code TODO_COMMAND}). 화면과 달리 같은 이름 과목의 구분은
 * {@code (c<courseId>)}로 받고, "오늘 마감"과 "<과목> 다음 할 일"을 더했다. 계획 문장은 {@link AssistantPlan}이 만든다. 자동 실행이나
 * 제출은 하지 않는다. 열 곳과 계획 단계만 돌려준다.
 */
final class AssistantRules {

	static final String TODO_COMMAND = "할일목록보여줘";

	static final int MAX_ACTIONS = 5;

	private static final Pattern ROOM = Pattern
		.compile("^(.+?)강의실(?:(?:로|으로)?(?:이동|가기|열기|가자|열어줘|가줘|이동해줘|들어가줘))?$");

	private static final Pattern KJ = Pattern.compile("\\(c([1-9]\\d{0,17})\\)$");

	private static final Pattern NEXT = Pattern.compile("^(.*?)(?:의|에서)?다음(?:활동|할일)(?:열어줘|보여줘|알려줘)?$");

	private static final String CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";

	private AssistantRules() {
	}

	/**
	 * @param intent course(강의실), list(목록), open(하나 열기), plan(계획) 또는 모델의 의도. 없으면 null
	 * @param plan 계획 단계. 계획이 아니면 null
	 */
	record Answer(String say, String intent, List<AssistantLinks.Action> actions, List<AssistantPlan.Step> plan) {

		Answer(String say, String intent, List<AssistantLinks.Action> actions) {
			this(say, intent, actions, null);
		}

	}

	/** @return 규칙에 맞지 않으면 null(모델에 묻는다) */
	static Answer answer(Snapshot snapshot, String text, Long contextCourseId) {
		String query = AssistantContext.compact(text).replaceAll("[.!?。~]+$", "");
		Answer room = room(snapshot, query);
		if (room != null) {
			return room;
		}
		Answer plan = AssistantPlan.rule(snapshot, text, contextCourseId);
		if (plan != null) {
			return plan;
		}
		if (query.equals(TODO_COMMAND) || query.equals("할일목록") || query.equals("할일보여줘")) {
			return list(snapshot.todo, snapshot.todoTotal == 0 ? "남은 할 일이 없어요."
					: "남은 할 일 " + snapshot.todoTotal + "개 중 마감이 가까운 것부터 보여 드릴게요.");
		}
		if (query.contains("오늘마감")) {
			List<ItemRef> today = snapshot.todo.stream()
				.filter((i) -> i.closeAt() != null && i.closeAt().toLocalDate().equals(snapshot.today()))
				.toList();
			return list(today, today.isEmpty() ? "오늘 마감인 할 일이 없어요." : "오늘 마감인 할 일 " + today.size() + "개예요.");
		}
		if (query.equals("마감임박한과제열어줘")) {
			return snapshot.todo.stream()
				.filter((i) -> i.kind().equals("report") && i.closeAt() != null && !i.closeAt().isBefore(snapshot.now)
						&& Duration.between(snapshot.now, i.closeAt()).compareTo(Duration.ofHours(24)) <= 0)
				.min(Comparator.comparing(ItemRef::closeAt))
				.map((i) -> one(i, "마감이 가까운 과제를 열게요."))
				.orElse(new Answer("24시간 안에 마감인 과제가 없어요.", null, List.of()));
		}
		Matcher next = NEXT.matcher(query);
		if (next.matches()) {
			return next(snapshot, next.group(1), contextCourseId);
		}
		return open(snapshot, query);
	}

	// classrooms.ts: "<과목> 강의실로 이동". 이름·코드에 포함되면 먼저, 줄임말(course-match.ts)이면 그다음이다.
	private static Answer room(Snapshot snapshot, String query) {
		Matcher m = ROOM.matcher(query);
		if (!m.matches()) {
			return null;
		}
		List<CourseRef> matches = courses(snapshot, m.group(1));
		if (matches.isEmpty()) {
			return new Answer("그 이름의 강의실을 찾지 못했어요. 과목 이름을 다시 확인해 주세요.", null, List.of());
		}
		if (matches.size() == 1) {
			return new Answer(AssistantLinks.name(matches.getFirst().course()) + " 강의실로 이동할게요.", "course",
					List.of(matches.getFirst().room()));
		}
		return new Answer("어느 강의실로 갈까요?", "course",
				matches.stream().limit(MAX_ACTIONS).map(CourseRef::room).toList());
	}

	// 다음 할 일. 과목을 말하지 않았으면 지금 과목, 그것도 없으면 모든 과목의 첫 할 일이다(server-home.ts "다음 할 일 열어줘").
	private static Answer next(Snapshot snapshot, String courseQuery, Long contextCourseId) {
		CourseRef course = null;
		if (!courseQuery.isEmpty()) {
			List<CourseRef> matches = courses(snapshot, courseQuery);
			if (matches.size() != 1) {
				return matches.isEmpty() ? null
						: new Answer("어느 과목의 다음 할 일을 볼까요?", "course",
								matches.stream().limit(MAX_ACTIONS).map(CourseRef::room).toList());
			}
			course = matches.getFirst();
		}
		else if (contextCourseId != null) {
			course = snapshot.byId.get(contextCourseId);
		}
		long courseId = course == null ? -1 : course.course().courseId();
		String name = course == null ? null : AssistantLinks.name(course.course());
		return snapshot.todo.stream()
			.filter((i) -> courseId < 0 || i.courseId() == courseId)
			.findFirst()
			.map((i) -> one(i, "다음 할 일을 열게요."))
			.orElse(new Answer(name == null ? "남은 할 일이 없어요." : name + "에 남은 할 일이 없어요.", null, List.of()));
	}

	// server-home.ts serverActionForCommand: "<과목> <제목> 열어줘" 또는 "<제목> 열어줘"와 정확히 같은 할 일 하나.
	private static Answer open(Snapshot snapshot, String query) {
		if (!query.endsWith("열어줘")) {
			return null;
		}
		List<ItemRef> exact = snapshot.todo.stream().filter((i) -> {
			String name = AssistantContext.compact(AssistantLinks.name(snapshot.byId.get(i.courseId()).course()));
			String title = AssistantContext.compact(i.title());
			return query.equals(name + title + "열어줘") || query.equals(title + "열어줘");
		}).toList();
		return exact.size() == 1 ? one(exact.getFirst(), "열게요.") : null;
	}

	private static List<CourseRef> courses(Snapshot snapshot, String raw) {
		Matcher kj = KJ.matcher(raw);
		if (kj.find()) {
			CourseRef course = snapshot.byId.get(Long.parseLong(kj.group(1)));
			return course == null ? List.of() : List.of(course);
		}
		String query = raw;
		if (query.isEmpty()) {
			return List.of();
		}
		// 이름이나 코드가 같은 과목이 먼저다. 같은 이름 과목이 여럿이면 모두 후보다.
		List<CourseRef> exact = new ArrayList<>();
		List<CourseRef> contains = new ArrayList<>();
		List<CourseRef> abbreviated = new ArrayList<>();
		for (CourseRef c : snapshot.courses) {
			String name = AssistantContext.compact(c.course().name());
			String code = AssistantContext.compact(c.course().code());
			if (name.equals(query) || code.equals(query)) {
				exact.add(c);
			}
			else if (name.contains(query) || code.contains(query)) {
				contains.add(c);
			}
			else if (abbreviation(query, name)) {
				abbreviated.add(c);
			}
		}
		return !exact.isEmpty() ? exact : !contains.isEmpty() ? contains : abbreviated;
	}

	// course-match.ts abbreviationMatch의 한글 부분: 초성만("ㄱㅇㅍ")이거나, 한글 두 글자 이상이 이름 안에 차례로 있다("고웹프").
	static boolean abbreviation(String query, String name) {
		if (query.codePointCount(0, query.length()) < 2) {
			return false;
		}
		if (query.chars().allMatch((ch) -> ch >= 'ㄱ' && ch <= 'ㅎ')) {
			return subsequence(query, choseong(name));
		}
		StringBuilder hangul = new StringBuilder();
		query.chars().filter((ch) -> ch >= '가' && ch <= '힣').forEach((ch) -> hangul.append((char) ch));
		return hangul.length() >= 2 && subsequence(hangul.toString(), name);
	}

	private static boolean subsequence(String text, String name) {
		int index = 0;
		for (int i = 0; i < name.length() && index < text.length(); i++) {
			if (name.charAt(i) == text.charAt(index)) {
				index++;
			}
		}
		return index == text.length();
	}

	private static String choseong(String text) {
		StringBuilder out = new StringBuilder();
		for (char ch : text.toCharArray()) {
			int code = ch - 0xAC00;
			out.append(code >= 0 && code < 11172 ? CHOSEONG.charAt(code / 588) : ch);
		}
		return out.toString();
	}

	private static Answer list(List<ItemRef> items, String say) {
		return new Answer(say, "list", items.stream().limit(MAX_ACTIONS).map(ItemRef::action).toList());
	}

	private static Answer one(ItemRef item, String say) {
		return new Answer(say, "open", List.of(item.action()));
	}

}
