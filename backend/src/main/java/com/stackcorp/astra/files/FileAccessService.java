package com.stackcorp.astra.files;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;

@Service
class FileAccessService implements FileAccess {

	private final JdbcClient jdbc;

	private final List<AttachmentParentAccess> parents;

	FileAccessService(JdbcClient jdbc, List<AttachmentParentAccess> parents) {
		this.jdbc = jdbc;
		this.parents = parents;
	}

	@Override
	public boolean allowed(long userId, long courseId, long fileId, FileAction action) {
		List<Long> groups = this.jdbc.sql("""
				SELECT DISTINCT a.attachment_group_id FROM attachments a JOIN files f ON f.file_id = a.file_id
				 WHERE a.file_id = :fileId AND f.state = 'active'
				""").param("fileId", fileId).query(Long.class).list();
		for (long group : groups) {
			for (AttachmentParentAccess parent : this.parents) {
				if (parent.allowed(userId, courseId, group, action)) {
					return true;
				}
			}
		}
		return false;
	}

}
