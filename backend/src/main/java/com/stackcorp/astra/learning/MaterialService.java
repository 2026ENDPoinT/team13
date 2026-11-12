package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;
import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachRequest;
import com.stackcorp.astra.files.api.Attachments.ChangeRequest;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강의 자료 등록, 수정, 삭제. 활동, 자료 상세, 첨부를 한 transaction으로 저장한다(SC-05-B). 권한은 부르는 쪽이 먼저 판정한다.
 * 수정과 삭제는 활동 행을 잠근 뒤 client가 본 version과 비교한다(SC-05). 삭제는 삭제 표시만 남긴다(ADR-0011).
 * 수정과 삭제는 같은 transaction에 감사 기록({@code material.update}, {@code material.delete})을 남긴다(SC-09).
 */
@Service
class MaterialService {

	static final String PURPOSE = "lecture_material";

	private final JdbcClient jdbc;

	private final Attachments attachments;

	private final Notifications notifications;

	private final AuditLog audit;

	MaterialService(JdbcClient jdbc, Attachments attachments, Notifications notifications, AuditLog audit) {
		this.jdbc = jdbc;
		this.attachments = attachments;
		this.notifications = notifications;
		this.audit = audit;
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
		int order = nextOrder(week);
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
		// 공개한 자료만 알린다. 시작 일시가 미래면 그 시각에 그때의 수강생에게 간다(ADR-0027).
		if (material.published()) {
			this.notifications.publish(new CourseNotification(NotificationKind.LECTURE_MATERIAL, courseId,
					CourseRole.STUDENT, activityId, material.title(), material.openAt()));
		}
		return activityId;
	}

	/**
	 * 강의 자료를 고친다. 첨부 중 하나라도 쓸 수 없으면 활동도 첨부도 바꾸지 않는다.
	 *
	 * @param expectedVersion client가 마지막으로 본 version(If-Match)
	 */
	@Transactional
	void update(Actor actor, long courseId, long activityId, int expectedVersion, MaterialChange change) {
		long userId = actor.userId();
		Locked current = lock(courseId, activityId, expectedVersion);
		long week = week(courseId, change.weekNo());
		Optional<Long> group;
		try {
			group = this.attachments.change(new ChangeRequest(userId, courseId, PURPOSE, current.attachmentGroupId(),
					change.keepFileIds(), change.addUploadIds(), change.title()));
		}
		catch (AttachmentException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "첨부할 수 없는 파일이나 업로드가 있습니다.");
		}
		this.jdbc.sql("""
				UPDATE learning_activities
				   SET course_week_id = :week, sort_order = :order, title = :title, description = :description,
				       open_at = :openAt, published_yn = :published, attachment_group_id = :group,
				       row_version = row_version + 1
				 WHERE activity_id = :activity AND row_version = :version AND deleted_at IS NULL
				""")
			.param("week", week)
			.param("order", week == current.weekId() ? current.sortOrder() : nextOrder(week))
			.param("title", change.title())
			.param("description", change.description())
			.param("openAt", change.openAt())
			.param("published", change.published())
			.param("group", group.orElse(null))
			.param("activity", activityId)
			.param("version", expectedVersion)
			.update();
		this.jdbc.sql("""
				UPDATE learning_material_details
				   SET published_at = CASE WHEN :published THEN COALESCE(:openAt, published_at, CURRENT_TIMESTAMP) END
				 WHERE activity_id = :activity
				""").param("published", change.published()).param("openAt", change.openAt()).param("activity", activityId).update();
		// 알림을 자료의 지금 상태에 맞춘다. 비공개가 되면 보낸 알림과 예약을 거둔다(ADR-0027).
		if (change.published()) {
			this.notifications.publish(new CourseNotification(NotificationKind.LECTURE_MATERIAL, courseId,
					CourseRole.STUDENT, activityId, change.title(), change.openAt()));
		}
		else {
			this.notifications.withdraw(NotificationKind.LECTURE_MATERIAL, activityId);
		}
		this.audit.success(userId, actor.sessionId(), "material.update", "learning_activity", activityId, courseId);
	}

	/**
	 * 강의 자료를 지운다. 활동에는 삭제 표시만 남겨 진도, 열람 이력 같은 학습 기록을 보존한다(SC-07). 첨부는 끊고 파일은 지운다.
	 *
	 * @param expectedVersion client가 마지막으로 본 version(If-Match)
	 */
	@Transactional
	void delete(Actor actor, long courseId, long activityId, int expectedVersion) {
		long userId = actor.userId();
		Locked current = lock(courseId, activityId, expectedVersion);
		this.jdbc.sql("""
				UPDATE learning_activities
				   SET deleted_at = CURRENT_TIMESTAMP(3), deleted_by = :user, row_version = row_version + 1
				 WHERE activity_id = :activity AND deleted_at IS NULL
				""").param("user", userId).param("activity", activityId).update();
		if (current.attachmentGroupId() != null) {
			this.attachments.release(current.attachmentGroupId());
		}
		this.notifications.withdraw(NotificationKind.LECTURE_MATERIAL, activityId);
		this.audit.success(userId, actor.sessionId(), "material.delete", "learning_activity", activityId, courseId);
	}

	// 과목의 지우지 않은 강의 자료를 잠근다. 같은 자료를 동시에 고치는 요청은 여기서 차례를 기다리고, 늦은 쪽은 version이 달라 거부된다.
	private Locked lock(long courseId, long activityId, int expectedVersion) {
		Locked current = this.jdbc.sql("""
				SELECT course_week_id, sort_order, attachment_group_id, row_version FROM learning_activities
				 WHERE activity_id = :activity AND course_id = :course AND activity_type = 'lecture_material'
				   AND deleted_at IS NULL
				   FOR UPDATE
				""")
			.param("activity", activityId)
			.param("course", courseId)
			.query((rs, n) -> new Locked(rs.getLong(1), rs.getInt(2), rs.getObject(3, Long.class), rs.getInt(4)))
			.optional()
			.orElseThrow(ApiException::notFound);
		if (current.version() != expectedVersion) {
			throw new ApiException(HttpStatus.CONFLICT, "version_conflict", "그 사이 자료가 바뀌었습니다. 다시 불러온 뒤 고쳐 주세요.");
		}
		return current;
	}

	private int nextOrder(long week) {
		return this.jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM learning_activities WHERE course_week_id = :week")
			.param("week", week)
			.query(Integer.class)
			.single();
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

	/**
	 * @param keepFileIds 남길 파일. 이 자료에 첨부된 파일 ID
	 * @param addUploadIds 새로 첨부할 업로드 ID
	 */
	record MaterialChange(int weekNo, String title, String description, LocalDateTime openAt, boolean published,
			List<Long> keepFileIds, List<Long> addUploadIds) {
	}

	private record Locked(long weekId, int sortOrder, Long attachmentGroupId, int version) {
	}

}
