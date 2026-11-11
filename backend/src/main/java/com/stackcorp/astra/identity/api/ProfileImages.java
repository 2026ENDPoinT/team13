package com.stackcorp.astra.identity.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 아바타 사진의 상태(profile_images, user_profiles, ADR-0038). 행과 상태 전이는 identity가 갖고, 사진 바이트의 저장·검사·재인코딩·
 * 전달은 files가 한다. files는 이 interface로만 상태를 바꾼다. 대상 사용자는 언제나 부르는 쪽이 session에서 정한 값이다.
 * <p>
 * 잠금 순서는 user_profiles → profile_images다. 색상 저장(EP-0271)과 초기화(EP-0278)도 같은 순서로 대기·현재 사진을 버린다.
 */
public interface ProfileImages {

	/**
	 * 새 사진을 처리 대기(TEMP)로 둔다. 같은 사용자의 앞선 대기·실패 사진은 버린다(DELETED). 프로필 행이 없으면 색상형으로 만든다.
	 * 프로필 version을 올린다.
	 * @param storageKey 재인코딩한 사진을 둘 key. 아직 객체가 없다
	 * @param sourceKey 올린 원본을 둔 key
	 * @return 사진 ID
	 */
	long stage(long userId, String storageKey, String sourceKey);

	/** 처리할 대기 사진 하나를 lease로 잡는다. worker가 둘이어도 같은 사진을 함께 잡지 않는다(SKIP LOCKED). */
	Optional<Pending> claim(String owner, Duration lease);

	/**
	 * 재인코딩을 마친 사진을 현재 사진으로 한다. 앞선 현재 사진은 버린다. 같은 transaction에서 프로필을 이미지형으로 바꾸고 version을
	 * 올린다.
	 * @return lease를 잃었거나 그 사이 버려졌으면 false. 부르는 쪽은 만든 객체를 지운다
	 */
	boolean activate(long imageId, String owner, String sha256, long sizeBytes, int width, int height);

	/** 다시 처리할 수 있게 대기로 돌린다. {@code delay}가 지나야 다시 잡힌다. @return lease를 잃었으면 false */
	boolean retryLater(long imageId, String owner, String errorCode, Duration delay);

	/** @param errorCode not_image|too_large|rejected|unavailable|failed. @return lease를 잃었으면 false */
	boolean fail(long imageId, String owner, String errorCode);

	/**
	 * lease가 끝난 처리 중 사진을 대기로 돌린다. 시도 수가 {@code maxAttempts}에 닿았으면 unavailable로 실패시킨다.
	 * @return 돌리거나 닫은 행 수
	 */
	int recoverExpired(int maxAttempts);

	/** 사용자의 현재 사진. 이미지형 프로필일 때만 있다. */
	Optional<Current> current(long userId);

	/** 지울 객체가 남은 사진. 버렸거나 실패한 사진의 두 객체와, 현재 사진의 남은 원본이다. */
	List<Discarded> discarded(int limit);

	/** {@link #discarded}의 객체를 지웠다. */
	void purged(long imageId);

	/**
	 * @param attempt 이번이 몇 번째 시도인지. 1부터 센다
	 */
	record Pending(long imageId, long userId, String storageKey, String sourceKey, int attempt) {
	}

	/** @param sha256 재인코딩한 사진의 SHA-256(hex). ETag로 쓴다 */
	record Current(long imageId, String storageKey, String sha256) {
	}

	/** @param keys 지울 객체 key. 없는 객체가 섞여 있어도 된다 */
	record Discarded(long imageId, List<String> keys) {
	}

}
