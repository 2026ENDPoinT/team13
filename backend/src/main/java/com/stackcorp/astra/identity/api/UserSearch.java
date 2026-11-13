package com.stackcorp.astra.identity.api;

import java.util.Collection;
import java.util.List;

/**
 * 부르는 쪽이 정한 사용자 범위 안에서 이름으로 찾는다. 범위 밖의 사용자는 찾지 않으므로 사용자 표 전체를 훑는 데 쓸 수 없다.
 * 권한은 판정하지 않는다. 부르는 쪽이 볼 수 있는 사람만 넘긴다. 활동 중(ACTIVE)인 계정만 돌려준다.
 */
public interface UserSearch {

	/**
	 * @param userIds 찾을 범위. 비어 있으면 결과도 비어 있다
	 * @param nameQuery 이름에 들어 있는 글자. null이면 범위 전체
	 * @return 이름, ID 순서의 한 쪽과 조건에 맞는 전체 수
	 */
	Matches search(Collection<Long> userIds, String nameQuery, int offset, int limit);

	record Match(long userId, String name) {
	}

	record Matches(List<Match> items, long total) {
	}

}
