package com.stackcorp.astra.home;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.stackcorp.astra.home.AssistantContext.CourseRef;
import com.stackcorp.astra.home.AssistantContext.ItemRef;
import com.stackcorp.astra.home.AssistantContext.Snapshot;

/**
 * 도우미의 계획(ADR-0044 18~20번). 화면이 차례로 열고 끝나면 다음으로 넘어가는 단계 목록이다. Frontend-final(origin/main 69524cf)의 표본
 * 모드에서 옮겼다.
 * <ul>
 * <li>계획 문장: {@code shared/assistant/aliases.ts}의 {@code isTodayAllRequest}(20-24행), {@code intentKeywords.plan}과
 * {@code allowsPlan}의 명시 판정(9-10, 32-39행). 확인 답("응")으로 앞 계획 요청을 잇는 판정은 앞 차례의 결과 종류가 요청에 없어
 * 옮기지 않았다.</li>
 * <li>범위: {@code shared/assistant/plan.ts}의 {@code todayDeadlinePlan}·{@code buildPlan}(오늘부터 사흘 안 마감, 시험 제외, 끝낸 것 제외),
 * {@code shared/assistant/resolve.ts} 39-47행(같은 문장의 과목이나 유형으로 좁힌다). 서버 모드에 더한 범위는 "오늘 마감"(오늘만)과
 * "이번 주"(일요일에 시작하는 이번 주 토요일까지, 캘린더와 같은 주 경계)다.</li>
 * <li>순서: {@code plan.ts}의 {@code orderPlan}(31-40행). 영상, 과제, 그 밖의 순서이고 같은 무리 안에서는 마감이 이른 순, 마감이 없으면
 * 뒤다. 같으면 처음 순서를 지킨다.</li>
 * <li>단계: {@code createRun}(98-110행)처럼 영상은 {@code auto}, 나머지는 {@code confirm}이다. 다만 서버 모드에서 STack이 재생하는
 * 영상은 서비스 영상뿐이므로 가져온 학교 영상(재생 없는 상세)과 학교 주소로 가는 단계는 모두 {@code confirm}이다.</li>
 * </ul>
 */
final class AssistantPlan {

	static final int MAX_STEPS = 12;

	/** 화면이 지금 보는 활동을 정하는 값({@code App.tsx}의 {@code observeScreen(params.get("n"))}). */
	private static final Pattern N_PARAM = Pattern.compile("[?&]n=([^&]+)");

	private static final Pattern TODAY_ALL = Pattern.compile(
			"(?:오늘)?(?:할일|남은일|남은것|남은거|해야할일)(?:을|를|은|는)?(?:전부|전체|모두|다|싹다)(?:해줘|해주세요|해|하자|해라|처리해줘|처리해|끝내줘|끝내자|진행해줘|진행해)?");

	private static final Pattern START = Pattern.compile("(?:오늘)?시작(?:해|하자)?");

	private static final List<String> PLAN_WORDS = List.of("시작해", "시작하자", "시작", "오늘계획", "계획", "전부", "다해", "모두",
			"남은거다", "남은것다", "할일다", "다듣", "다보", "다처리", "다끝내", "순서대로", "차례로", "한꺼번에", "한번에", "할일끝내");

	/** aliases.ts intentKeywords에서 계획을 뺀 유형 낱말. 앞의 유형이 먼저다. */
	private static final Map<String, List<String>> TYPE_WORDS = typeWords();

	private static final Pattern NOISE = Pattern.compile("(?U)[\\s.,!?。~]+");

	private AssistantPlan() {
	}

	/**
	 * 계획의 단계 하나.
	 * @param id 화면이 그 활동 화면에서 쓰는 값(주소의 {@code n}). 서비스 영상·자료는 {@code srv-<활동 ID>}, 서비스 과제·시험·설문은
	 * {@code srv-<과제·퀴즈·설문 ID>}(없으면 {@code srv-<활동 ID>}), 가져온 학교 활동은 {@code school-<n>}
	 * @param kind video, report, test, survey, material, other
	 * @param course 과목 이름
	 * @param due 마감 일시(기관 시간대의 벽시계, offset 없음). 없으면 null
	 * @param mode auto(STack이 재생하는 서비스 영상, 끝나면 넘어간다) 또는 confirm
	 */
	record Step(String id, String kind, String title, String course, LocalDateTime due, String href, boolean external,
			String mode) {

		Map<String, Object> body() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("id", this.id);
			out.put("kind", this.kind);
			out.put("title", this.title);
			out.put("course", this.course);
			out.put("due", this.due == null ? null : this.due.withNano(0).toString());
			out.put("href", this.href);
			out.put("external", this.external);
			out.put("mode", this.mode);
			return out;
		}

	}

	/**
	 * 규칙으로 만드는 계획. 계획 문장이 아니면 null이다.
	 * @return 계획 답. 넣을 일이 없으면 plan이 null인 답
	 */
	static AssistantRules.Answer rule(Snapshot snapshot, String text, Long contextCourseId) {
		String query = NOISE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
		boolean todayAll = TODAY_ALL.matcher(query).matches();
		if (!todayAll && !explicit(query)) {
			return null;
		}
		LocalDateTime now = snapshot.now;
		LocalDateTime endOfToday = snapshot.today().plusDays(1).atStartOfDay();
		Predicate<ItemRef> due;
		String scope;
		if (query.contains("오늘마감")) {
			due = (i) -> i.closeAt() != null && i.closeAt().isBefore(endOfToday);
			scope = "오늘 마감";
		}
		else if (query.contains("이번주")) {
			LocalDateTime endOfWeek = snapshot.today()
				.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
				.plusDays(1)
				.atStartOfDay();
			due = (i) -> i.closeAt() != null && i.closeAt().isBefore(endOfWeek);
			scope = "이번 주";
		}
		else {
			// todayDeadlinePlan·buildPlan: 오늘부터 사흘 뒤 날짜까지(D-0~D-3) 마감.
			LocalDateTime until = snapshot.today().plusDays(4).atStartOfDay();
			due = (i) -> i.closeAt() != null && i.closeAt().isBefore(until);
			scope = "오늘";
		}
		Set<Long> courses = todayAll ? Set.of() : courses(snapshot, query, contextCourseId);
		String type = todayAll ? null : type(query);
		List<ItemRef> picked = snapshot.todo.stream()
			.filter(ItemRef::activity)
			.filter((i) -> !i.kind().equals("test"))
			.filter((i) -> i.closeAt() == null || i.closeAt().isAfter(now))
			.filter(courses.isEmpty() || !scope.equals("오늘") ? due : (i) -> true)
			.filter((i) -> courses.isEmpty() || courses.contains(i.courseId()))
			.filter((i) -> type == null || i.kind().equals(type))
			.toList();
		List<Step> steps = steps(snapshot, picked);
		if (steps.isEmpty()) {
			return new AssistantRules.Answer(scope + " 계획에 넣을 일이 없어요.", "plan", List.of(), null);
		}
		String label = courses.isEmpty() || !scope.equals("오늘") ? scope + " 계획" : "계획";
		return answer(label + " " + steps.size() + "단계예요. 이 순서로 진행할까요?", steps);
	}

	/** 모델이 고른 항목으로 만드는 계획. 활동이 아닌 이름(과목, 공지)과 모르는 이름은 버린다. */
	static List<Step> steps(Snapshot snapshot, List<ItemRef> items) {
		List<ItemRef> unique = new ArrayList<>(new LinkedHashSet<>(items));
		unique.removeIf((i) -> !i.activity());
		return order(unique).stream().limit(MAX_STEPS).map((i) -> step(snapshot, i)).toList();
	}

	static AssistantRules.Answer answer(String say, List<Step> steps) {
		List<AssistantLinks.Action> actions = steps.stream()
			.limit(AssistantRules.MAX_ACTIONS)
			.map((s) -> new AssistantLinks.Action(s.course() + " · " + s.title(), s.href(), s.external()))
			.toList();
		return new AssistantRules.Answer(say, "plan", actions, steps);
	}

	// plan.ts orderPlan: 영상(0) → 과제(1) → 그 밖(2), 같은 무리는 마감이 이른 순, 마감 없음은 뒤. List.sort는 안정 정렬이다.
	static List<ItemRef> order(List<ItemRef> items) {
		List<ItemRef> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparingInt(AssistantPlan::group)
			.thenComparing(ItemRef::closeAt, Comparator.nullsLast(Comparator.naturalOrder())));
		return sorted;
	}

	private static int group(ItemRef item) {
		return switch (item.kind()) {
			case "video" -> 0;
			case "report" -> 1;
			default -> 2;
		};
	}

	private static Step step(Snapshot snapshot, ItemRef item) {
		AssistantLinks.Action action = item.action();
		String kind = switch (item.kind()) {
			case "video", "report", "test", "survey", "material" -> item.kind();
			default -> "other";
		};
		boolean auto = item.nativeActivity() && item.type().equals("lecture_weeks") && !action.external();
		String course = AssistantLinks.name(snapshot.byId.get(item.courseId()).course());
		return new Step(id(item), kind, item.title(), course, item.closeAt(), action.href(), action.external(),
				auto ? "auto" : "confirm");
	}

	// 화면 주소의 n 값. 서비스 활동의 연결 화면(act=)이나 강의실로 가는 단계는 srv-<활동 ID>, 학교 활동은 school-<n>이다.
	private static String id(ItemRef item) {
		if (!item.nativeActivity()) {
			return item.key();
		}
		var n = N_PARAM.matcher(item.action().href());
		return !item.action().external() && n.find() ? n.group(1) : "srv-" + item.key().substring(1);
	}

	private static boolean explicit(String query) {
		return START.matcher(query).matches()
				|| PLAN_WORDS.stream().anyMatch((word) -> !word.startsWith("시작") && query.contains(word));
	}

	// resolve.ts: 문장에 이름·코드가 나온 과목, 줄임말 과목. "이 과목"은 지금 과목이다.
	private static Set<Long> courses(Snapshot snapshot, String query, Long contextCourseId) {
		Set<Long> ids = new LinkedHashSet<>();
		if (query.contains("이과목") && contextCourseId != null && snapshot.byId.containsKey(contextCourseId)) {
			ids.add(contextCourseId);
			return ids;
		}
		for (CourseRef c : snapshot.courses) {
			String name = AssistantContext.compact(c.course().name());
			String code = AssistantContext.compact(c.course().code());
			if ((name.length() >= 2 && query.contains(name)) || (code.length() >= 2 && query.contains(code))) {
				ids.add(c.course().courseId());
			}
		}
		if (!ids.isEmpty()) {
			return ids;
		}
		String subject = subject(query);
		if (subject.length() >= 2) {
			for (CourseRef c : snapshot.courses) {
				if (AssistantRules.abbreviation(subject, AssistantContext.compact(c.course().name()))) {
					ids.add(c.course().courseId());
				}
			}
		}
		return ids;
	}

	// resolve.ts의 subject: 명령 꼬리, 계획·유형 낱말, 오늘·조사를 지운 나머지.
	private static String subject(String query) {
		String subject = query.replaceAll("(듣게해줘|틀어줘|보여줘|해줘|듣게|듣자|가자|해주세요|주세요|알려줘|찾아줘|이어듣기|재생해줘|끝낼래|할래|하자)", "");
		for (String word : PLAN_WORDS) {
			subject = subject.replace(word, "");
		}
		for (List<String> words : TYPE_WORDS.values()) {
			for (String word : words) {
				subject = subject.replace(word, "");
			}
		}
		return subject.replaceAll("오늘|이번주|마감|할일|남은거|남은것|들을|듣기|보기", "").replaceAll("(?:을|를|은|는|으로|로|에|의|다)+$", "");
	}

	private static String type(String query) {
		for (Map.Entry<String, List<String>> entry : TYPE_WORDS.entrySet()) {
			if (entry.getValue().stream().anyMatch(query::contains)) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static Map<String, List<String>> typeWords() {
		Map<String, List<String>> words = new LinkedHashMap<>();
		words.put("report", List.of("과제", "제출", "리포트"));
		words.put("test", List.of("시험", "퀴즈", "고사"));
		words.put("survey", List.of("설문"));
		words.put("material", List.of("자료", "pdf"));
		words.put("zoom", List.of("줌", "화상", "멘토링"));
		words.put("video", List.of("강의", "영상", "수업", "듣", "틀어", "재생", "이어"));
		return words;
	}

}
