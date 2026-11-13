package com.stackcorp.astra.summary.chat;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.FileDerivatives;

import tools.jackson.databind.json.JsonMapper;

/**
 * 학습 대화 본문의 저장 위치. 근거 파일의 파생물이라 content bucket에 있고, 파일을 지우면 GC가 함께 지운다(ADR-0007, ADR-0032).
 * <ul>
 * <li>studychat/{chatId}/{exchangeId}/question.json 질문</li>
 * <li>studychat/{chatId}/{exchangeId}/answer.json 검사를 통과한 답과 인용</li>
 * </ul>
 * 대화를 지우면 studychat/{chatId}/ 아래를 모두 지운다.
 */
@Component
class StudyChatStorage {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final FileDerivatives derivatives;

	StudyChatStorage(FileDerivatives derivatives) {
		this.derivatives = derivatives;
	}

	void putQuestion(long fileId, long chatId, long exchangeId, String question) {
		this.derivatives.put(fileId, exchange(chatId, exchangeId) + "question.json",
				JSON.writeValueAsBytes(Map.of("question", question)), "application/json");
	}

	Optional<String> question(long fileId, long chatId, long exchangeId) {
		return this.derivatives.get(fileId, exchange(chatId, exchangeId) + "question.json")
			.map((bytes) -> JSON.readTree(bytes).path("question").asString(null));
	}

	void putAnswer(long fileId, long chatId, long exchangeId, Answer answer) {
		this.derivatives.put(fileId, exchange(chatId, exchangeId) + "answer.json", JSON.writeValueAsBytes(answer),
				"application/json");
	}

	Optional<Answer> answer(long fileId, long chatId, long exchangeId) {
		return this.derivatives.get(fileId, exchange(chatId, exchangeId) + "answer.json")
			.map((bytes) -> JSON.readValue(bytes, Answer.class));
	}

	void deleteAnswer(long fileId, long chatId, long exchangeId) {
		this.derivatives.delete(fileId, exchange(chatId, exchangeId) + "answer.json");
	}

	void deleteChat(long fileId, long chatId) {
		this.derivatives.deletePrefix(fileId, "studychat/" + chatId + "/");
	}

	private static String exchange(long chatId, long exchangeId) {
		return "studychat/" + chatId + "/" + exchangeId + "/";
	}

}
