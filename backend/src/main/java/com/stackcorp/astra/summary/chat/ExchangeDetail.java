package com.stackcorp.astra.summary.chat;

/**
 * @param question 질문 본문. 저장소에서 읽지 못하면 null
 * @param answer 답한 질문에만 있다
 */
public record ExchangeDetail(Exchange exchange, String question, Answer answer) {
}
