package com.stackcorp.astra.files.upload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.files.FileTypes;
import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.upload.UploadRecords.Policy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AttachmentService implements Attachments {

	private final JdbcClient jdbc;

	private final UploadRecords uploads;

	AttachmentService(JdbcClient jdbc, UploadRecords uploads) {
		this.jdbc = jdbc;
		this.uploads = uploads;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public long attach(AttachRequest request) {
		Policy policy = UploadPurpose.of(request.purpose())
			.flatMap((p) -> this.uploads.policy(p.code()))
			.orElseThrow(() -> new AttachmentException("첨부할 수 없는 용도입니다."));
		List<Long> uploadIds = new ArrayList<>(new LinkedHashSet<>(request.uploadIds()));
		if (uploadIds.isEmpty() || (policy.maxFileCount() != null && uploadIds.size() > policy.maxFileCount())) {
			throw new AttachmentException("첨부 개수가 올바르지 않습니다.");
		}
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("INSERT INTO attachment_groups (group_title, created_by_user_id) VALUES (:title, :user)")
			.param("title", request.title())
			.param("user", request.ownerUserId())
			.update(key, "attachment_group_id");
		long group = key.getKey().longValue();
		int order = 1;
		for (long uploadId : uploadIds) {
			long fileId = this.uploads.claim(uploadId, request.ownerUserId(), request.courseId(), policy.id())
				.orElseThrow(() -> new AttachmentException("쓸 수 없는 업로드가 있습니다."));
			this.jdbc.sql("""
					INSERT INTO attachments (attachment_group_id, file_id, upload_id, sort_order)
					VALUES (:group, :file, :upload, :order)
					""").param("group", group).param("file", fileId).param("upload", uploadId).param("order", order++).update();
		}
		return group;
	}

	@Override
	public Map<Long, List<AttachedFile>> list(Collection<Long> attachmentGroupIds) {
		Map<Long, List<AttachedFile>> result = new LinkedHashMap<>();
		if (attachmentGroupIds.isEmpty()) {
			return result;
		}
		this.jdbc.sql("""
				SELECT a.attachment_group_id, f.file_id, COALESCE(a.display_name, f.original_file_name) AS name,
				       f.file_size_bytes, f.file_extension, b.detected_mime_type, f.mime_type
				  FROM attachments a JOIN files f ON f.file_id = a.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE a.attachment_group_id IN (:groups) AND f.state = 'active'
				 ORDER BY a.attachment_group_id, a.sort_order
				""").param("groups", attachmentGroupIds).query((rs) -> {
			String extension = rs.getString("file_extension");
			boolean pdf = FileTypes.pdf(rs.getString("detected_mime_type"), rs.getString("mime_type"), extension);
			result.computeIfAbsent(rs.getLong("attachment_group_id"), (g) -> new ArrayList<>())
				.add(new AttachedFile(rs.getLong("file_id"), rs.getString("name"), rs.getLong("file_size_bytes"),
						extension == null ? null : extension.toLowerCase(java.util.Locale.ROOT), pdf));
		});
		return result;
	}

}
