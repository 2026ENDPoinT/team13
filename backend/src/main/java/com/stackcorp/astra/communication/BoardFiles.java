package com.stackcorp.astra.communication;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachRequest;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 게시글 첨부. 업로드는 {@code POST /api/uploads}에 용도 {@value #PURPOSE}로 올리고, 글을 저장하는 transaction 안에서 첨부로 확정한다
 * (SC-05-B, ADR-0010). 확정에 실패하면 글도 저장하지 않는다. 첨부의 읽기 권한은 {@link PostAttachmentAccess}가 글의 권한으로 판정한다.
 */
@Component
class BoardFiles {

	static final String PURPOSE = "post_attachment";

	/** upload_policies의 post_attachment 최대 개수와 같다. */
	static final int MAX_UPLOADS = 5;

	private final Attachments attachments;

	BoardFiles(Attachments attachments) {
		this.attachments = attachments;
	}

	/** 요청의 업로드 ID 목록. 없으면 빈 목록. */
	static List<Long> validated(List<Long> uploadIds) {
		if (uploadIds == null) {
			return List.of();
		}
		if (uploadIds.size() > MAX_UPLOADS || uploadIds.stream().anyMatch((id) -> id == null || id < 1)) {
			throw ApiException.invalid("uploadIds는 " + MAX_UPLOADS + "개 이하의 업로드 ID여야 합니다.");
		}
		return List.copyOf(uploadIds);
	}

	/**
	 * 부르는 쪽의 transaction 안에서 부른다.
	 * @return 첨부 그룹 ID. 업로드가 없으면 null
	 */
	Long attach(long userId, long courseId, List<Long> uploadIds, String title) {
		if (uploadIds.isEmpty()) {
			return null;
		}
		try {
			return this.attachments.attach(new AttachRequest(userId, courseId, PURPOSE, uploadIds, title));
		}
		catch (AttachmentException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "첨부할 수 없는 업로드가 있습니다.");
		}
	}

	Map<Long, List<AttachedFile>> list(Collection<Long> attachmentGroupIds) {
		return attachmentGroupIds.isEmpty() ? Map.of() : this.attachments.list(attachmentGroupIds);
	}

	List<AttachedFile> of(Long attachmentGroupId) {
		return attachmentGroupId == null ? List.of()
				: this.attachments.list(List.of(attachmentGroupId)).getOrDefault(attachmentGroupId, List.of());
	}

}
