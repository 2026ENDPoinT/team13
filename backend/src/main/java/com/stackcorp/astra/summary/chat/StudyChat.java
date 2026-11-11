package com.stackcorp.astra.summary.chat;

import java.time.Instant;

/**
 * 학습 대화 하나. 검색 범위는 fileId 하나이고 대화를 연 사람만 읽고 지운다.
 *
 * @param deleted 소유자가 지웠거나 보존기한이 지나 정리를 기다리는지. 지운 대화는 없는 것으로 다룬다
 */
public record StudyChat(long id, long ownerUserId, long courseId, long fileId, int exchangeCount, Instant createdAt,
		Instant lastActivityAt, boolean deleted) {
}
