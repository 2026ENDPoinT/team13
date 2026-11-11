package com.stackcorp.astra.learning;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.learning.LearningAccess.Activity;

/**
 * 학습활동에 첨부한 파일의 권한을 활동 권한으로 판정한다. 지운 활동의 첨부는 누구에게도 허용하지 않는다(ADR-0011).
 */
@Component
class MaterialAttachmentAccess implements AttachmentParentAccess {

	private final JdbcClient jdbc;

	private final LearningAccess access;

	MaterialAttachmentAccess(JdbcClient jdbc, LearningAccess access) {
		this.jdbc = jdbc;
		this.access = access;
	}

	@Override
	public boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action) {
		List<Activity> activities = this.jdbc.sql("""
				SELECT activity_id, course_id, published_yn, open_at FROM learning_activities
				 WHERE attachment_group_id = :group AND course_id = :course AND deleted_at IS NULL
				""")
			.param("group", attachmentGroupId)
			.param("course", courseId)
			.query((rs, n) -> new Activity(rs.getLong(1), rs.getLong(2), rs.getBoolean(3),
					rs.getObject(4, java.time.LocalDateTime.class)))
			.list();
		return activities.stream()
			.anyMatch((a) -> action == FileAction.MANAGE ? this.access.canManage(userId, a)
					: this.access.canView(userId, a));
	}

}
