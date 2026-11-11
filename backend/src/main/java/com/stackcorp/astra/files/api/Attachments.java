package com.stackcorp.astra.files.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
	 * 첨부 그룹을 최종 집합으로 바꾼다(SC-05-B). 부모 저장과 같은 transaction 안에서 부른다. keep은 지금 이 그룹에 첨부된 파일만,
	 * add는 {@link #attach}와 같은 조건의 업로드만 받는다. 집합에서 빠진 파일은 첨부를 끊고, 마지막 참조였으면 파일을 지운다.
	 * 지운 파일은 바로 어느 경로로도 읽히지 않는다.
	 *
	 * @return 첨부 그룹 ID. 그룹이 없었고 최종 집합이 비어 있으면 비어 있는 값
	 * @throws AttachmentException keep이나 add에 쓸 수 없는 값이 하나라도 있을 때. 부모 transaction을 되돌려야 한다
	 */
	Optional<Long> change(ChangeRequest request);

	/**
	 * 원본 그룹을 바꾸지 않고 새 첨부 그룹을 만든다. keep과 add는 {@link #change}와 같다. keep은 원본 그룹에 지금 첨부된 파일만 받고,
	 * 이어받은 파일은 두 그룹이 함께 참조한다. 판마다 첨부 집합을 따로 남기는 부모(과제 제출 버전)가 부모 저장과 같은 transaction
	 * 안에서 부른다.
	 *
	 * @return 새 첨부 그룹 ID. keep과 add가 모두 비어 있으면 비어 있는 값
	 * @throws AttachmentException keep이나 add에 쓸 수 없는 값이 하나라도 있을 때. 부모 transaction을 되돌려야 한다
	 */
	Optional<Long> derive(ChangeRequest request);

	/**
	 * 부모를 지울 때 그룹의 첨부를 모두 끊는다. 마지막 참조였던 파일은 지운다. 부모 저장과 같은 transaction 안에서 부른다.
	 */
	void release(long attachmentGroupId);

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

	/**
	 * @param attachmentGroupId 부모가 지금 가진 첨부 그룹. 없으면 null
	 * @param keepFileIds 남길 파일. 지금 그룹에 첨부된 파일 ID
	 * @param addUploadIds 새로 첨부할 업로드 ID
	 */
	record ChangeRequest(long ownerUserId, long courseId, String purpose, Long attachmentGroupId,
			List<Long> keepFileIds, List<Long> addUploadIds, String title) {
		public ChangeRequest {
			keepFileIds = List.copyOf(keepFileIds);
			addUploadIds = List.copyOf(addUploadIds);
		}
	}

}
