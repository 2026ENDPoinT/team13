package com.stackcorp.astra.files.api;

import com.stackcorp.astra.identity.api.Actor;

/**
 * 과목이 없는 업로드 용도(알림마당 자료, 1:1 문의, 쪽지 첨부)의 올리기 권한. 그 용도의 부모를 가진 모듈이 구현한다. 과목 용도는 files가
 * 과목 역할로 판정하므로 여기에 오지 않는다. 자기가 모르는 용도면 false를 돌려준다. 어느 구현도 허락하지 않으면 거부다.
 */
public interface UploadPermission {

	/**
	 * @param purpose upload_policies.policy_code
	 */
	boolean allows(Actor actor, String purpose);

}
