package com.stackcorp.astra.files.api;

import java.util.Collection;
import java.util.Set;

/**
 * 아바타 사진을 볼 수 있는지(ADR-0038). 이름 옆에 아바타를 그리는 목록과 상세가 사진이 없는 사람의
 * {@code GET /api/users/{userId}/avatar}를 부르지 않도록 쓴다. 화면의 공개 범위는 부르는 쪽이 정하고, 사용자 ID를 내보내는 사람에게만
 * 묻는다.
 */
public interface AvatarPhotos {

	/**
	 * {@code viewerId}가 {@code GET /api/users/{userId}/avatar}로 사진을 받을 사람. 지금 사진이 있고(처리를 마친 현재 사진), 본인이거나
	 * 폐강하지 않은 과목을 함께하는 사람이다. 사용자 수와 관계없이 쿼리 두 번 이하이고, 빈 목록이면 쿼리가 없다.
	 */
	Set<Long> visibleTo(long viewerId, Collection<Long> userIds);

}
