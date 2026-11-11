package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachRequest;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강의 자료 등록. 활동, 자료 상세, 첨부 확정을 한 transaction으로 저장한다(SC-05-B). 권한은 부르는 쪽이 먼저 판정한다.
 */
@Service
class MaterialService {

	static final String PURPOSE = "lecture_material";

	private final JdbcClient jdbc;

	private final Attachments attachments;

	MaterialService(JdbcClient jdbc, Attachments attachments) {
		this.jdbc = jdbc;
		this.attachments = attachments;
	}

	/** @return 만든 활동 ID */
	@Transactional
	long create(long userId, long courseId, NewMaterial material) {
		long week = week(courseId, material.weekNo());
		Long group = null;
		if (!material.uploadIds().isEmpty()) {
			try {
				group = this.attachments
					.attach(new AttachRequest(userId, courseId, PURPOSE, material.uploadIds(), material.title()));
			}
			catch (AttachmentException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "첨부할 수 없는 업로드가 있습니다.");
			}
		}
		int order = this.jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM learning_activities WHERE course_week_id = :week")
			.param("week", week)
			.query(Integer.class)
			.single();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, description, open_at,
				                                 published_yn, attachment_group_id, sort_order, created_by)
				VALUES (:course, :week, 'lecture_material', :title, :description, :openAt, :published, :group, :order, :user)
				""")
			.param("course", courseId)
			.param("week", week)
			.param("title", material.title())
			.param("description", material.description())
			.param("openAt", material.openAt())
			.param("published", material.published())
			.param("group", group)
			.param("order", order)
			.param("user", userId)
			.update(key, "activity_id");
		long activityId = key.getKey().longValue();
		this.jdbc.sql("""
				INSERT INTO learning_material_details (activity_id, published_at)
				VALUES (:activity, CASE WHEN :published THEN COALESCE(:openAt, CURRENT_TIMESTAMP) END)
				""").param("activity", activityId).param("published", material.published()).param("openAt", material.openAt()).update();
		return activityId;
	}

	private long week(long courseId, int weekNo) {
		Optional<Long> existing = findWeek(courseId, weekNo, "");
		if (existing.isPresent()) {
			return existing.get();
		}
		try {
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			this.jdbc.sql("INSERT INTO course_weeks (course_id, week_no) VALUES (:course, :no)")
				.param("course", courseId)
				.param("no", weekNo)
				.update(key, "course_week_id");
			return key.getKey().longValue();
		}
		catch (DuplicateKeyException ex) {
			// 다른 transaction이 방금 만든 행은 snapshot 밖이라 잠금 읽기로 찾는다.
			return findWeek(courseId, weekNo, " FOR SHARE").orElseThrow(() -> ex);
		}
	}

	private Optional<Long> findWeek(long courseId, int weekNo, String lock) {
		return this.jdbc.sql("SELECT course_week_id FROM course_weeks WHERE course_id = :course AND week_no = :no" + lock)
			.param("course", courseId)
			.param("no", weekNo)
			.query(Long.class)
			.optional();
	}

	record NewMaterial(int weekNo, String title, String description, LocalDateTime openAt, boolean published,
			List<Long> uploadIds) {
	}

}
