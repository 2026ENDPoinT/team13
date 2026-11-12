package com.stackcorp.astra.communication;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachRequest;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.files.api.Attachments.ChangeRequest;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 게시글·알림마당 글·쪽지의 첨부. 업로드는 {@code POST /api/uploads}에 용도별로 올리고, 부모를 저장하는 transaction 안에서 첨부로
 * 확정한다(SC-05-B, ADR-0010). 확정에 실패하면 부모도 저장하지 않는다. 수정은 첨부의 최종 집합(keep: 지금 첨부된 파일, add: 내 업로드)을
 * 받는다. 집합에서 빠진 파일은 바로 누구도 읽지 못한다. 읽기 권한은 {@link PostAttachmentAccess}(강의실 게시판),
 * {@link CommunityAttachmentAccess}(알림마당), {@link MessageAttachmentAccess}(쪽지)가 부모의 권한으로 판정한다.
 */
@Component
class BoardFiles {

	/** 강의실 질의응답·열린게시판 글과 답글. 과목 용도다. */
	static final String PURPOSE = "post_attachment";

	/** 알림마당 공지. 과목 밖 용도다(ADR-0035). */
	static final String COMMUNITY_NOTICE = "community_notice";

	/** 알림마당 자료실 자료. 과목 밖 용도다(ADR-0035). */
	static final String COMMUNITY_MATERIAL = "community_material";

	/** 1:1 문의와 그 답변. 과목 밖 용도다. */
	static final String INQUIRY = "inquiry_attachment";

	/** 쪽지. 과목 밖 용도다. */
	static final String MESSAGE = "message_attachment";

	/** upload_policies의 post_attachment, inquiry_attachment, message_attachment 최대 개수와 같다. */
	static final int MAX_UPLOADS = 5;

	/** upload_policies의 community_notice, community_material 최대 개수와 같다. */
	static final int MAX_MATERIAL_UPLOADS = 10;

	private final Attachments attachments;

	BoardFiles(Attachments attachments) {
		this.attachments = attachments;
	}

	/** 요청의 업로드 ID 목록. 없으면 빈 목록. */
	static List<Long> validated(List<Long> uploadIds) {
		return validated(uploadIds, MAX_UPLOADS, "uploadIds");
	}

	static List<Long> validated(List<Long> ids, int max, String field) {
		if (ids == null) {
			return List.of();
		}
		if (ids.size() > max || ids.stream().anyMatch((id) -> id == null || id < 1)
				|| new LinkedHashSet<>(ids).size() != ids.size()) {
			throw ApiException.invalid(field + "는 " + max + "개 이하의 서로 다른 ID여야 합니다.");
		}
		return List.copyOf(ids);
	}

	/**
	 * 수정 요청의 첨부 최종 집합. 없으면(null) 첨부를 바꾸지 않는다.
	 * @return 검사한 집합. 요청에 없으면 null
	 */
	static Change validated(AttachmentSet set, int max) {
		if (set == null) {
			return null;
		}
		List<Long> keep = validated(set.keep(), max, "attachments.keep");
		List<Long> add = validated(set.add(), max, "attachments.add");
		if (keep.size() + add.size() > max) {
			throw ApiException.invalid("첨부는 " + max + "개까지입니다.");
		}
		return new Change(keep, add);
	}

	/**
	 * 강의실 게시글 첨부. 부르는 쪽의 transaction 안에서 부른다.
	 * @return 첨부 그룹 ID. 업로드가 없으면 null
	 */
	Long attach(long userId, long courseId, List<Long> uploadIds, String title) {
		return attach(PURPOSE, userId, courseId, uploadIds, title);
	}

	/**
	 * @param courseId 과목 용도면 그 과목. 과목 밖 용도면 null
	 * @return 첨부 그룹 ID. 업로드가 없으면 null
	 */
	Long attach(String purpose, long userId, Long courseId, List<Long> uploadIds, String title) {
		if (uploadIds.isEmpty()) {
			return null;
		}
		try {
			return this.attachments.attach(new AttachRequest(userId, courseId, purpose, uploadIds, title));
		}
		catch (AttachmentException ex) {
			throw notReady();
		}
	}

	/**
	 * 첨부를 최종 집합으로 바꾼다. 부르는 쪽이 부모 행을 잠근 transaction 안에서 부른다. 하나라도 쓸 수 없으면 409이고 부르는 쪽
	 * transaction이 모두 되돌린다.
	 * @return 바꾼 뒤의 첨부 그룹 ID. 남은 첨부가 없고 그룹도 없었으면 null
	 */
	Long change(String purpose, long userId, Long courseId, Long attachmentGroupId, Change change, String title) {
		try {
			return this.attachments
				.change(new ChangeRequest(userId, courseId, purpose, attachmentGroupId, change.keep(), change.add(), title))
				.orElse(null);
		}
		catch (AttachmentException ex) {
			throw notReady();
		}
	}

	/** 부모를 지울 때 그룹의 첨부를 모두 끊는다. 부모 저장과 같은 transaction에서 부른다. */
	void release(Long attachmentGroupId) {
		if (attachmentGroupId != null) {
			this.attachments.release(attachmentGroupId);
		}
	}

	Map<Long, List<AttachedFile>> list(Collection<Long> attachmentGroupIds) {
		return attachmentGroupIds.isEmpty() ? Map.of() : this.attachments.list(attachmentGroupIds);
	}

	List<AttachedFile> of(Long attachmentGroupId) {
		return attachmentGroupId == null ? List.of()
				: this.attachments.list(List.of(attachmentGroupId)).getOrDefault(attachmentGroupId, List.of());
	}

	private static ApiException notReady() {
		return new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "첨부할 수 없는 파일이나 업로드가 있습니다.");
	}

	/**
	 * 수정 요청의 첨부 최종 집합.
	 * @param keep 남길 파일. 지금 이 글에 첨부된 파일 ID
	 * @param add 새로 첨부할 업로드 ID. 본인이 그 용도로 올린 업로드
	 */
	record AttachmentSet(List<Long> keep, List<Long> add) {
	}

	/** 검사한 최종 집합. 멱등 지문에도 쓴다. */
	record Change(List<Long> keep, List<Long> add) {
	}

}
