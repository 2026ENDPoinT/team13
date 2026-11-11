package com.stackcorp.astra.assessment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.SurveyRecords.Answer;
import com.stackcorp.astra.assessment.SurveyRecords.Option;
import com.stackcorp.astra.assessment.SurveyRecords.Question;
import com.stackcorp.astra.shared.ApiException;

/**
 * 설문 답 전체의 검사(EP-0099, ASSESS-09). 화면의 hidden 값(qtn_mandatory, ans_min, ans_max)이 아니라 서버의 문항 정의로
 * 검사한다. 하나라도 틀리면 아무것도 저장하지 않도록 저장 전에 모두 검사한다. 오류 문장에는 입력값을 넣지 않는다(SC-04).
 */
final class SurveyAnswers {

	static final int MAX_TEXT = 2_000;

	private static final int MAX_ANSWERS = SurveyController.MAX_QUESTIONS;

	private static final int MAX_OPTIONS = SurveyController.MAX_OPTIONS;

	private SurveyAnswers() {
	}

	/**
	 * 문항 정의 없이 할 수 있는 검사와 정규화. 문항 ID 순서로, 보기는 오름차순으로 바꾼다. 멱등 지문은 이 값으로 만든다.
	 */
	static List<Answer> normalized(List<Submitted> answers) {
		if (answers == null || answers.size() > MAX_ANSWERS) {
			throw ApiException.invalid("answers는 문항 수 이하의 목록이어야 합니다.");
		}
		Set<Long> seen = new HashSet<>();
		List<Answer> result = new ArrayList<>();
		for (Submitted answer : answers) {
			if (answer == null || answer.questionId() == null || answer.questionId() < 1 || !seen.add(answer.questionId())) {
				throw ApiException.invalid("answers의 questionId가 올바르지 않습니다.");
			}
			List<Long> options = answer.optionIds() == null ? List.of() : answer.optionIds();
			if (options.size() > MAX_OPTIONS || options.stream().anyMatch((id) -> id == null || id < 1)
					|| new HashSet<>(options).size() != options.size()) {
				throw ApiException.invalid("answers의 optionIds가 올바르지 않습니다.");
			}
			String text = PlainText.block(answer.text(), MAX_TEXT, "text");
			result.add(new Answer(answer.questionId(), options.stream().sorted().toList(), text));
		}
		result.sort(Comparator.comparingLong(Answer::questionId));
		return List.copyOf(result);
	}

	/**
	 * 문항 정의로 하는 검사. 모든 답의 문항이 이 설문의 문항이고, 보기가 그 문항의 보기이며, 필수 문항에 모두 답했을 때만 통과한다.
	 * <ul>
	 * <li>single, scale: 보기 하나. 글은 받지 않는다.</li>
	 * <li>multiple: 문항의 최소~최대 개수만큼의 보기. 글은 받지 않는다.</li>
	 * <li>essay: 빈칸이 아닌 글. 보기는 받지 않는다.</li>
	 * </ul>
	 */
	static List<Answer> validated(List<Question> questions, List<Answer> answers) {
		Map<Long, Question> byId = questions.stream().collect(Collectors.toMap(Question::questionId, Function.identity()));
		for (Answer answer : answers) {
			Question question = byId.get(answer.questionId());
			if (question == null) {
				throw ApiException.invalid("이 설문에 없는 문항의 답이 있습니다.");
			}
			check(question, answer);
		}
		Set<Long> answered = answers.stream().map(Answer::questionId).collect(Collectors.toSet());
		if (questions.stream().anyMatch((q) -> q.required() && !answered.contains(q.questionId()))) {
			throw ApiException.invalid("필수 문항에 모두 답해야 합니다.");
		}
		return answers;
	}

	private static void check(Question question, Answer answer) {
		int chosen = answer.optionIds().size();
		if ("essay".equals(question.type())) {
			if (chosen > 0 || answer.text() == null) {
				throw ApiException.invalid("서술형 문항은 보기 없이 글로 답합니다.");
			}
			return;
		}
		if (answer.text() != null) {
			throw ApiException.invalid("보기 문항에는 글을 함께 보내지 않습니다.");
		}
		Set<Long> valid = question.options().stream().map(Option::optionId).collect(Collectors.toSet());
		if (!valid.containsAll(answer.optionIds())) {
			throw ApiException.invalid("문항에 없는 보기를 골랐습니다.");
		}
		int min = "multiple".equals(question.type()) ? question.minSelect() : 1;
		int max = "multiple".equals(question.type()) ? question.maxSelect() : 1;
		if (chosen < min || chosen > max) {
			throw ApiException.invalid("고른 보기 수가 문항의 선택 범위를 벗어났습니다.");
		}
	}

	/**
	 * 요청의 답 하나.
	 *
	 * @param optionIds 고른 보기. 서술형이면 비우거나 보내지 않는다
	 * @param text 서술형 답변(일반 텍스트, 2000자 이하). 보기 문항이면 보내지 않는다
	 */
	record Submitted(Long questionId, List<Long> optionIds, String text) {
	}

}
