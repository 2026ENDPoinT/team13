package com.stackcorp.astra.files.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 첨부 확정과 조회(SC-05-B). 부모 객체를 가진 모듈이 부모 저장과 같은 transaction 안에서 {@link #attach}를 부른다.
 * 다른 모듈은 files 테이블을 직접 바꾸지 않는다. 권한 판정은 부르는 쪽이 한다.
 */
public interface Attachments {

	/**
	 * 업로드를 첨부로 확정하고 첨부 그룹을 만든다. 업로드는 요청한 사용자가 같은 과목·같은 용도로 올렸고 검사를 통과한 것만 받는다.
	 * 한 업로드는 한 번만 첨부된다.
	 *
	 * @return 첨부 그룹 ID
	 * @throws AttachmentException 쓸 수 없는 업로드가 하나라도 있을 때. 부모 transaction을 되돌려야 한다
	 */
	long attach(AttachRequest request);

	/** 첨부 그룹별 파일. 삭제된 파일은 빼고 첨부 순서대로 돌려준다. */
	Map<Long, List<AttachedFile>> list(Collection<Long> attachmentGroupIds);

	/**
	 * @param purpose 업로드 용도. upload_policies.policy_code
	 */
	record AttachRequest(long ownerUserId, long courseId, String purpose, List<Long> uploadIds, String title) {
		public AttachRequest {
			uploadIds = List.copyOf(uploadIds);
		}
	}

	record AttachedFile(long fileId, String name, long sizeBytes, String extension, boolean pdf) {
	}

}
