package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.List;

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
 * 영상(온라인강의) 활동 등록(ADR-0016). 활동, 콘텐츠, 영상 첨부 확정, 변환 예약을 한 transaction으로 저장한다(SC-05-B).
 * 콘텐츠마다 업로드 하나를 첨부 그룹 하나로 확정하고 그 파일의 영상 asset을 만든다. 하나라도 실패하면 전체를 되돌린다.
 * 권한은 부르는 쪽이 먼저 판정한다.
 */
@Service
class LectureService {

	static final String PURPOSE = "lecture_video";

	private final JdbcClient jdbc;

	private final Attachments attachments;

	private final VideoAssets assets;

	private final MaterialService materials;

	LectureService(JdbcClient jdbc, Attachments attachments, VideoAssets assets, MaterialService materials) {
		this.jdbc = jdbc;
		this.attachments = attachments;
		this.assets = assets;
		this.materials = materials;
	}

	/** @return 만든 활동 ID */
	@Transactional
	long create(long userId, long courseId, NewLecture lecture) {
		long week = this.materials.week(courseId, lecture.weekNo());
		int order = this.jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM learning_activities WHERE course_week_id = :week")
			.param("week", week)
			.query(Integer.class)
			.single();
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
			.param("order", order)
			.param("user", userId)
			.update(key, "activity_id");
		long activityId = key.getKey().longValue();
		int seq = 1;
		for (NewItem item : lecture.items()) {
			try {
				addItem(userId, courseId, activityId, seq++, item);
			}
			catch (AttachmentException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "첨부할 수 없는 업로드가 있습니다.");
			}
		}
		return activityId;
	}

	private void addItem(long userId, long courseId, long activityId, int seq, NewItem item) {
		long group = this.attachments
			.attach(new AttachRequest(userId, courseId, PURPOSE, List.of(item.uploadId()), item.title()));
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

}
