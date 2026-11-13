package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachRequest;
import com.stackcorp.astra.files.api.VideoAssets;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영상(온라인강의) 활동 등록, 수정, 삭제(ADR-0016, ADR-0011). 활동, 콘텐츠, 영상 첨부, 변환 예약을 한 transaction으로 저장한다(SC-05-B).
 * 콘텐츠마다 업로드 하나를 첨부 그룹 하나로 확정하고 그 파일의 영상 asset을 만든다. 하나라도 실패하면 전체를 되돌린다.
 * <p>
 * 수정과 삭제는 활동 행을 잠근 뒤 client가 본 version과 비교한다(SC-05). 활동과 콘텐츠는 삭제 표시만 하고 학습 기록은 남긴다. 빠진
 * 콘텐츠와 지운 활동의 첨부 그룹은 놓는다(Attachments.release). 마지막 참조를 잃은 영상 파일은 지워지고, files worker가 그 영상의
 * 변환을 취소하고 재생 경로, HLS, key를 거둔다. 권한은 부르는 쪽이 먼저 판정한다.
 * <p>
 * 수정과 삭제는 활동 행보다 먼저 옮기기 전과 뒤 주차를 배타 잠금하고, 바꾼 뒤 그 주차의 온라인 학습 출석 반영을 다시 판정한다
 * ({@link OnlineAttendance}). 남은 콘텐츠를 이미 모두 충족한 학생이 채워진다. 이미 쓴 출석 기록은 지우지 않는다.
 */
@Service
class LectureService {

	static final String PURPOSE = "lecture_video";

	private final JdbcClient jdbc;

	private final Attachments attachments;

	private final VideoAssets assets;

	private final CourseWeeks weeks;

	private final OnlineAttendance attendance;

	LectureService(JdbcClient jdbc, Attachments attachments, VideoAssets assets, CourseWeeks weeks,
			OnlineAttendance attendance) {
		this.jdbc = jdbc;
		this.attachments = attachments;
		this.assets = assets;
		this.weeks = weeks;
		this.attendance = attendance;
	}

	/** @return 만든 활동 ID */
	@Transactional
	long create(long userId, long courseId, NewLecture lecture) {
		long week = this.weeks.weekId(courseId, lecture.weekNo());
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, description, open_at,
				                                 close_at, published_yn, sort_order, created_by)
				VALUES (:course, :week, 'lecture_weeks', :title, :description, :openAt, :closeAt, :published, :order, :user)
				""")
			.param("course", courseId)
			.param("week", week)
			.param("title", lecture.title())
			.param("description", lecture.description())
			.param("openAt", lecture.openAt())
			.param("closeAt", lecture.closeAt())
			.param("published", lecture.published())
			.param("order", nextOrder(week))
			.param("user", userId)
			.update(key, "activity_id");
		long activityId = key.getKey().longValue();
		addItems(userId, courseId, activityId, 1, lecture.items());
		return activityId;
	}

	/**
	 * 영상 활동을 고친다. 콘텐츠는 최종 집합이다. keep은 지금 이 활동의 콘텐츠만, add는 {@link #create}와 같은 조건의 업로드만
	 * 받는다. 하나라도 쓸 수 없으면 아무것도 바꾸지 않는다.
	 *
	 * @param expectedVersion client가 마지막으로 본 version(If-Match)
	 */
	@Transactional
	void update(long userId, long courseId, long activityId, int expectedVersion, LectureChange change) {
		long from = weekOf(courseId, activityId);
		long week = this.weeks.weekId(courseId, change.weekNo());
		this.attendance.lockWeeks(true, List.of(from, week));
		Locked current = lock(courseId, activityId, expectedVersion, from);
		Map<Long, Long> live = liveItems(activityId);
		Set<Long> kept = new HashSet<>();
		for (KeptItem item : change.keep()) {
			if (!live.containsKey(item.itemId())) {
				throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "이 활동의 콘텐츠가 아닌 항목이 있습니다.");
			}
			kept.add(item.itemId());
			this.jdbc.sql("""
					UPDATE online_lecture_items SET title = :title, required_sec = :required
					 WHERE online_lecture_item_id = :item AND deleted_at IS NULL
					""").param("title", item.title()).param("required", item.requiredSec()).param("item", item.itemId()).update();
		}
		for (Map.Entry<Long, Long> item : live.entrySet()) {
			if (!kept.contains(item.getKey())) {
				removeItem(item.getKey(), item.getValue());
			}
		}
		int nextSeq = this.jdbc.sql("SELECT COALESCE(MAX(link_seq), 0) + 1 FROM online_lecture_items WHERE activity_id = :a")
			.param("a", activityId)
			.query(Integer.class)
			.single();
		addItems(userId, courseId, activityId, nextSeq, change.add());
		this.jdbc.sql("""
				UPDATE learning_activities
				   SET course_week_id = :week, sort_order = :order, title = :title, description = :description,
				       open_at = :openAt, close_at = :closeAt, published_yn = :published, row_version = row_version + 1
				 WHERE activity_id = :activity AND row_version = :version AND deleted_at IS NULL
				""")
			.param("week", week)
			.param("order", week == current.weekId() ? current.sortOrder() : nextOrder(week))
			.param("title", change.title())
			.param("description", change.description())
			.param("openAt", change.openAt())
			.param("closeAt", change.closeAt())
			.param("published", change.published())
			.param("activity", activityId)
			.param("version", expectedVersion)
			.update();
		this.attendance.reconcile(courseId, List.of(from, week), current.sessions());
	}

	/**
	 * 영상 활동을 지운다. 활동에는 삭제 표시만 남기고, 콘텐츠의 첨부 그룹을 모두 놓는다. 그 순간부터 상세, 재생 허가, key, 학습 session이
	 * 모두 404이고, 변환 취소와 HLS 회수는 files worker가 한다.
	 *
	 * @param expectedVersion client가 마지막으로 본 version(If-Match)
	 */
	@Transactional
	void delete(long userId, long courseId, long activityId, int expectedVersion) {
		long from = weekOf(courseId, activityId);
		this.attendance.lockWeeks(true, List.of(from));
		Locked current = lock(courseId, activityId, expectedVersion, from);
		this.jdbc.sql("""
				UPDATE learning_activities
				   SET deleted_at = CURRENT_TIMESTAMP(3), deleted_by = :user, row_version = row_version + 1
				 WHERE activity_id = :activity AND deleted_at IS NULL
				""").param("user", userId).param("activity", activityId).update();
		for (Map.Entry<Long, Long> item : liveItems(activityId).entrySet()) {
			removeItem(item.getKey(), item.getValue());
		}
		this.attendance.reconcile(courseId, List.of(from), current.sessions());
	}

	private void addItems(long userId, long courseId, long activityId, int firstSeq, List<NewItem> items) {
		int seq = firstSeq;
		for (NewItem item : items) {
			try {
				addItem(userId, courseId, activityId, seq++, item);
			}
			catch (AttachmentException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "첨부할 수 없는 업로드가 있습니다.");
			}
		}
	}

	private void addItem(long userId, long courseId, long activityId, int seq, NewItem item) {
		long group = this.attachments
			.attach(new AttachRequest(userId, courseId, PURPOSE, List.of(item.uploadId())));
		long fileId = this.attachments.list(List.of(group)).get(group).getFirst().fileId();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, required_sec,
				                                  attachment_group_id, video_state)
				VALUES (:activity, :seq, :title, 'video', :required, :group, 'processing')
				""")
			.param("activity", activityId)
			.param("seq", seq)
			.param("title", item.title())
			.param("required", item.requiredSec())
			.param("group", group)
			.update(key, "online_lecture_item_id");
		long itemId = key.getKey().longValue();
		// player는 재생 목록의 key URI로 key를 받는다. 이 API가 요청마다 콘텐츠 권한을 판정한다(ADR-0007 8번).
		long assetId = this.assets.register(fileId,
				"/api/courses/" + courseId + "/lecture-items/" + itemId + "/hls-key");
		this.jdbc.sql("UPDATE online_lecture_items SET video_asset_id = :asset WHERE online_lecture_item_id = :item")
			.param("asset", assetId)
			.param("item", itemId)
			.update();
	}

	// 콘텐츠에 삭제 표시를 하고 재생 경로를 바로 닫는다. 첨부 그룹을 놓으면 영상 파일이 지워진다.
	private void removeItem(long itemId, Long attachmentGroupId) {
		this.jdbc.sql("""
				UPDATE online_lecture_items SET deleted_at = CURRENT_TIMESTAMP(3), content_path = NULL
				 WHERE online_lecture_item_id = :item AND deleted_at IS NULL
				""").param("item", itemId).update();
		if (attachmentGroupId != null) {
			this.attachments.release(attachmentGroupId);
		}
	}

	/** @return 지우지 않은 콘텐츠 ID와 그 첨부 그룹 ID(없으면 null), link_seq 순서 */
	private Map<Long, Long> liveItems(long activityId) {
		Map<Long, Long> items = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT online_lecture_item_id, attachment_group_id FROM online_lecture_items
				 WHERE activity_id = :activity AND deleted_at IS NULL ORDER BY link_seq
				""").param("activity", activityId).query((rs) -> {
			items.put(rs.getLong(1), rs.getObject(2, Long.class));
		});
		return items;
	}

	// 과목의 지우지 않은 영상 활동을 잠근다. 같은 활동을 동시에 고치는 요청은 여기서 차례를 기다리고, 늦은 쪽은 version이 달라 거부된다.
	// 주차 잠금 전에 읽은 주차가 그대로인지도 본다. 그 사이 다른 요청이 옮겼으면 version도 바뀌었으므로 같은 409다.
	private Locked lock(long courseId, long activityId, int expectedVersion, long weekId) {
		Locked current = this.jdbc.sql("""
				SELECT course_week_id, sort_order, row_version, attendance_session_id FROM learning_activities
				 WHERE activity_id = :activity AND course_id = :course AND activity_type = 'lecture_weeks'
				   AND deleted_at IS NULL
				   FOR UPDATE
				""")
			.param("activity", activityId)
			.param("course", courseId)
			.query((rs, n) -> new Locked(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getObject(4, Long.class)))
			.optional()
			.orElseThrow(ApiException::notFound);
		if (current.version() != expectedVersion || current.weekId() != weekId) {
			throw new ApiException(HttpStatus.CONFLICT, "version_conflict", "그 사이 활동이 바뀌었습니다. 다시 불러온 뒤 고쳐 주세요.");
		}
		return current;
	}

	private long weekOf(long courseId, long activityId) {
		return this.jdbc.sql("""
				SELECT course_week_id FROM learning_activities
				 WHERE activity_id = :activity AND course_id = :course AND activity_type = 'lecture_weeks'
				   AND deleted_at IS NULL
				""")
			.param("activity", activityId)
			.param("course", courseId)
			.query(Long.class)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private int nextOrder(long week) {
		return this.jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM learning_activities WHERE course_week_id = :week")
			.param("week", week)
			.query(Integer.class)
			.single();
	}

	/**
	 * @param openAt 학습인정기간 시작. 학생은 이 시각부터 활동을 본다
	 * @param closeAt 학습인정기간 끝
	 */
	record NewLecture(int weekNo, String title, String description, LocalDateTime openAt, LocalDateTime closeAt,
			boolean published, List<NewItem> items) {
	}

	/**
	 * @param requiredSec 출석인정 필요 시간(초). 비우면 출석 규칙이 정한다
	 */
	record NewItem(String title, long uploadId, Integer requiredSec) {
	}

	/**
	 * @param keep 남길 콘텐츠와 그 제목, 출석인정 필요 시간. 이 활동의 콘텐츠만 된다
	 * @param add 새로 더할 콘텐츠
	 */
	record LectureChange(int weekNo, String title, String description, LocalDateTime openAt, LocalDateTime closeAt,
			boolean published, List<KeptItem> keep, List<NewItem> add) {
	}

	record KeptItem(long itemId, String title, Integer requiredSec) {
	}

	/** @param sessionId 활동이 직접 가리키는 출석 차시. 없으면 null */
	private record Locked(long weekId, int sortOrder, int version, Long sessionId) {

		List<Long> sessions() {
			return this.sessionId == null ? List.of() : List.of(this.sessionId);
		}

	}

}
