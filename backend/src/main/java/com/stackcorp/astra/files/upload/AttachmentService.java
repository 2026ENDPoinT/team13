package com.stackcorp.astra.files.upload;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.files.FileReferences;
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

	private final FileReferences references;

	AttachmentService(JdbcClient jdbc, UploadRecords uploads, FileReferences references) {
		this.jdbc = jdbc;
		this.uploads = uploads;
		this.references = references;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public long attach(AttachRequest request) {
		Policy policy = policy(purpose(request.purpose(), request.courseId()));
		List<Long> uploadIds = new ArrayList<>(new LinkedHashSet<>(request.uploadIds()));
		if (uploadIds.isEmpty() || (policy.maxFileCount() != null && uploadIds.size() > policy.maxFileCount())) {
			throw new AttachmentException("첨부 개수가 올바르지 않습니다.");
		}
		long group = createGroup(request.title(), request.ownerUserId());
		addUploads(group, policy, request.ownerUserId(), request.courseId(), uploadIds, 1);
		return group;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<Long> change(ChangeRequest request) {
		Policy policy = policy(purpose(request.purpose(), request.courseId()));
		List<Long> keep = new ArrayList<>(new LinkedHashSet<>(request.keepFileIds()));
		List<Long> add = new ArrayList<>(new LinkedHashSet<>(request.addUploadIds()));
		if (policy.maxFileCount() != null && keep.size() + add.size() > policy.maxFileCount()) {
			throw new AttachmentException("첨부 개수가 올바르지 않습니다.");
		}
		Long group = request.attachmentGroupId();
		List<Attached> current = group == null ? List.of() : attached(group);
		if (!current.stream().filter(Attached::active).map(Attached::fileId).toList().containsAll(keep)) {
			throw new AttachmentException("남길 수 없는 첨부가 있습니다.");
		}
		if (group == null) {
			if (add.isEmpty()) {
				return Optional.empty();
			}
			group = createGroup(request.title(), request.ownerUserId());
		}
		List<Attached> kept = new ArrayList<>();
		for (Attached a : current) {
			if (keep.contains(a.fileId())) {
				kept.add(a);
			}
			else {
				detach(group, a);
			}
		}
		renumber(group, kept);
		addUploads(group, policy, request.ownerUserId(), request.courseId(), add, kept.size() + 1);
		return Optional.of(group);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void release(long attachmentGroupId) {
		for (Attached a : attached(attachmentGroupId)) {
			detach(attachmentGroupId, a);
		}
	}

	// 과목 용도는 과목이 있어야 하고 과목 밖 용도는 과목이 없어야 한다. 과목 파일이 과목 밖 부모에 붙거나 그 반대가 되지 않는다.
	private static UploadPurpose purpose(String code, Long courseId) {
		return UploadPurpose.of(code)
			.filter((p) -> p.courseScoped() == (courseId != null))
			.orElseThrow(() -> new AttachmentException("첨부할 수 없는 용도입니다."));
	}

	private Policy policy(UploadPurpose purpose) {
		return this.uploads.policy(purpose.code())
			.orElseThrow(() -> new AttachmentException("첨부할 수 없는 용도입니다."));
	}

	private long createGroup(String title, long ownerUserId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("INSERT INTO attachment_groups (group_title, created_by_user_id) VALUES (:title, :user)")
			.param("title", title)
			.param("user", ownerUserId)
			.update(key, "attachment_group_id");
		return key.getKey().longValue();
	}

	private void addUploads(long group, Policy policy, long ownerUserId, Long courseId, List<Long> uploadIds,
			int firstOrder) {
		int order = firstOrder;
		for (long uploadId : uploadIds) {
			long fileId = this.uploads.claim(uploadId, ownerUserId, courseId, policy.id())
				.orElseThrow(() -> new AttachmentException("쓸 수 없는 업로드가 있습니다."));
			this.jdbc.sql("""
					INSERT INTO attachments (attachment_group_id, file_id, upload_id, sort_order)
					VALUES (:group, :file, :upload, :order)
					""").param("group", group).param("file", fileId).param("upload", uploadId).param("order", order++).update();
		}
	}

	/**
	 * 그룹의 첨부를 순서대로 잠가 읽는다. 같은 부모를 동시에 바꾸는 요청은 여기서 차례를 기다린다. 파일 행은 여기서 잠그지 않는다.
	 * 첨부를 끊을 때 FileReferences가 file_blobs → files 순서로 잠근다.
	 */
	private List<Attached> attached(long group) {
		return this.jdbc.sql("""
				SELECT a.attachment_id, a.file_id, a.sort_order, f.state = 'active' AS active
				  FROM attachments a JOIN files f ON f.file_id = a.file_id
				 WHERE a.attachment_group_id = :group
				 ORDER BY a.sort_order, a.attachment_id
				   FOR UPDATE OF a
				""")
			.param("group", group)
			.query((rs, n) -> new Attached(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBoolean(4)))
			.list();
	}

	// 첨부를 끊고, 마지막 참조였으면 파일을 지운다. 그 파일을 만든 업로드도 지운 상태로 둔다.
	private void detach(long group, Attached attached) {
		this.jdbc.sql("DELETE FROM attachments WHERE attachment_id = :id AND attachment_group_id = :group")
			.param("id", attached.attachmentId())
			.param("group", group)
			.update();
		if (this.references.releaseIfUnattached(attached.fileId())) {
			this.jdbc.sql("UPDATE upload_sessions SET upload_state = 'deleted' WHERE file_id = :file AND upload_state = 'attached'")
				.param("file", attached.fileId())
				.update();
		}
	}

	// 남은 첨부를 1부터 다시 매긴다. (그룹, 순서)가 유일하므로 먼저 겹치지 않는 범위로 옮긴다.
	private void renumber(long group, List<Attached> kept) {
		boolean consecutive = true;
		for (int i = 0; i < kept.size(); i++) {
			consecutive &= kept.get(i).sortOrder() == i + 1;
		}
		if (consecutive) {
			return;
		}
		this.jdbc.sql("UPDATE attachments SET sort_order = sort_order + 30000 WHERE attachment_group_id = :group")
			.param("group", group)
			.update();
		for (int i = 0; i < kept.size(); i++) {
			this.jdbc.sql("UPDATE attachments SET sort_order = :order WHERE attachment_id = :id")
				.param("order", i + 1)
				.param("id", kept.get(i).attachmentId())
				.update();
		}
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

	private record Attached(long attachmentId, long fileId, int sortOrder, boolean active) {
	}

}
