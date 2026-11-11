package com.stackcorp.astra.files;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.identity.api.Actor;

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
		for (long group : groups(fileId, false)) {
			for (AttachmentParentAccess parent : this.parents) {
				if (parent.allowed(userId, courseId, group, action)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 과목에 속한 파일(files.course_id가 있음)은 부모와 관계없이 거부한다. 과목 파일은 과목 경로에서 과목 권한으로만 연다(ADR-0035).
	 */
	@Override
	public boolean allowedOutsideCourse(Actor actor, long fileId, FileAction action) {
		for (long group : groups(fileId, true)) {
			for (AttachmentParentAccess parent : this.parents) {
				if (parent.allowedOutsideCourse(actor, group, action)) {
					return true;
				}
			}
		}
		return false;
	}

	private List<Long> groups(long fileId, boolean outsideCourse) {
		return this.jdbc.sql("""
				SELECT DISTINCT a.attachment_group_id FROM attachments a JOIN files f ON f.file_id = a.file_id
				 WHERE a.file_id = :fileId AND f.state = 'active'
				""" + (outsideCourse ? " AND f.course_id IS NULL" : "")).param("fileId", fileId).query(Long.class).list();
	}

}
