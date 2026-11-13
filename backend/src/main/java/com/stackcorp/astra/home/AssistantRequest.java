package com.stackcorp.astra.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면이 보내는 도우미 요청(ADR-0044). 사용자가 쓴 문장과 지금 보고 있는 곳만 받는다. 과목, 할 일, 마감은 서버가 로그인한 사용자의
 * 자료로 정하고 화면이 보낸 값은 믿지 않는다. 정해 둔 필드 밖의 필드, 틀린 형식, 길이 초과는 모두 거절한다.
 *
 * @param courseId 지금 보고 있는 과목. 내 과목이 아니면 쓰지 않는다. 없으면 null
 * @param activityId 지금 보고 있는 활동의 화면 주소 값({@code srv-<n>}, {@code school-<n>}, 숫자). 모델에 주는 힌트일 뿐이다. 없으면 null
 * @param history 같은 대화의 앞 차례(오래된 것부터)
 */
record AssistantRequest(String text, Long courseId, String activityId, List<Turn> history) {

	static final int MAX_TEXT = 500;

	static final int MAX_SAY = 300;

	static final int MAX_ACTIVITY_ID = 64;

	static final int MAX_HISTORY = 4;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	record Turn(String text, String say) {
	}

	/** @return 형식에 맞지 않으면 null */
	static AssistantRequest parse(byte[] body) {
		JsonNode root;
		try {
			root = JSON.readTree(body);
		}
		catch (JacksonException ex) {
			return null;
		}
		if (root == null || !root.isObject() || !onlyFields(root, Set.of("text", "context"))) {
			return null;
		}
		String text = text(root.get("text"), MAX_TEXT);
		if (text == null || text.isBlank()) {
			return null;
		}
		JsonNode context = root.get("context");
		if (context == null || context.isNull()) {
			return new AssistantRequest(text, null, null, List.of());
		}
		if (!context.isObject() || !onlyFields(context, Set.of("courseId", "activityId", "history"))) {
			return null;
		}
		Long courseId = null;
		JsonNode course = context.get("courseId");
		if (course != null && !course.isNull()) {
			if (!course.isIntegralNumber() || !course.canConvertToLong() || course.longValue() < 1) {
				return null;
			}
			courseId = course.longValue();
		}
		String activityId = null;
		JsonNode activity = context.get("activityId");
		if (activity != null && !activity.isNull()) {
			activityId = text(activity, MAX_ACTIVITY_ID);
			if (activityId == null) {
				return null;
			}
		}
		List<Turn> history = new ArrayList<>();
		JsonNode turns = context.get("history");
		if (turns != null && !turns.isNull()) {
			if (!turns.isArray() || turns.size() > MAX_HISTORY) {
				return null;
			}
			for (JsonNode turn : turns) {
				if (!turn.isObject() || !onlyFields(turn, Set.of("text", "say"))) {
					return null;
				}
				String turnText = text(turn.get("text"), MAX_TEXT);
				String say = text(turn.get("say"), MAX_SAY);
				if (turnText == null || say == null) {
					return null;
				}
				history.add(new Turn(turnText, say));
			}
		}
		return new AssistantRequest(text, courseId, activityId, List.copyOf(history));
	}

	private static boolean onlyFields(JsonNode node, Set<String> allowed) {
		for (String name : node.propertyNames()) {
			if (!allowed.contains(name)) {
				return false;
			}
		}
		return true;
	}

	private static String text(JsonNode node, int max) {
		if (node == null || !node.isString()) {
			return null;
		}
		String value = node.stringValue();
		return value.codePointCount(0, value.length()) <= max ? value : null;
	}

}
