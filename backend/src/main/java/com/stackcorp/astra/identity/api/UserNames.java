package com.stackcorp.astra.identity.api;

import java.util.Collection;
import java.util.Map;

/**
 * 사용자 표시 이름 조회. 권한 판정은 하지 않는다. 부르는 쪽이 이미 볼 수 있는 객체의 작성자·제출자 이름에만 쓴다.
 */
public interface UserNames {

	/** 없는 사용자는 결과에 넣지 않는다. */
	Map<Long, String> namesOf(Collection<Long> userIds);

}
