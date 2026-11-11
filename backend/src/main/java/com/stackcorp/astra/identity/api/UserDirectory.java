package com.stackcorp.astra.identity.api;

import java.util.Collection;
import java.util.Map;

/**
 * 다른 모듈이 화면에 사용자를 표시할 때 쓰는 이름과 학사 식별자. 호출하는 쪽이 먼저 그 사용자를 볼 권한을 판정한다.
 */
public interface UserDirectory {

	/** @return 있는 사용자만 담은 ID별 표시 정보 */
	Map<Long, UserCard> find(Collection<Long> userIds);

	/**
	 * @param externalRef 학사 식별자(학번·교번)
	 */
	record UserCard(long userId, String name, String externalRef) {
	}

}
