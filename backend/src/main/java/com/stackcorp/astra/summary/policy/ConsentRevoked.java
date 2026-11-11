package com.stackcorp.astra.summary.policy;

/**
 * 교수자가 동의를 철회했다. 철회와 같은 transaction에서 동기로 전달한다. 받는 쪽은 그 교수자의 동의에 기대던 대기 작업을 취소한다.
 */
public record ConsentRevoked(long courseId, long instructorUserId, ProcessingScope scope) {
}
