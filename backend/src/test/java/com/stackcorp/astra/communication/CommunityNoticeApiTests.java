package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 알림마당 공지. 알림마당 운영자만 쓰고, 읽는 사람은 기관 역할에 맞는 대상의 게시된 공지만 본다. 고정 공지가 먼저 오고, 읽지 않은 고정 공지는
 * 메인 화면의 진입 공지가 된다. 전역 게시판은 모든 테스트가 함께 쓰므로 고유한 제목 머리말로 검색한다.
 */
class CommunityNoticeApiTests extends BoardTestSupport {

	private static final String NOTICES = "/api/community/notices";

	String tag;

	long op;

	ApiClient operator;

	ApiClient staffClient;

	@BeforeEach
	void operators() {
		this.tag = "n" + UUID.randomUUID().toString().substring(0, 8);
		this.op = this.data.user("STAFF");
		this.jdbc.sql("INSERT INTO community_operators (user_id, grant_reference) VALUES (:u, 'test')")
			.param("u", this.op)
			.update();
		this.operator = new ApiClient(this.mvc, this.data.session(this.op));
		this.staffClient = new ApiClient(this.mvc, this.data.session(this.data.user("STAFF")));
	}

	@Test
	void operatorsPostNoticesThatFollowTheAudienceAndThePublishTime() throws Exception {
		String create = notice("고정", "common", true, null, null);
		for (ApiClient client : List.of(this.student, this.professor, this.staffClient)) {
			client.postJson(NOTICES, create).andExpect(status().isNotFound());
		}
		this.anonymous.postJson(NOTICES, create).andExpect(status().isUnauthorized());
		withoutCsrf(MockMvcRequestBuilders.post(NOTICES), this.op).andExpect(status().isForbidden());
		this.operator.postJson(NOTICES, notice("x", "all", false, null, null)).andExpect(status().isBadRequest());
		this.student.uploadOutsideCourse("community_notice", "a.txt", bytes("x")).andExpect(status().isNotFound());

		long pinned = created(postWithKey(this.operator, NOTICES, create, "notice-" + this.tag)
			.andExpect(jsonPath("$.pinned").value(true))
			.andExpect(jsonPath("$.published").value(true))
			.andExpect(jsonPath("$.canManage").value(true)), "noticeId");
		postWithKey(this.operator, NOTICES, create, "notice-" + this.tag).andExpect(jsonPath("$.noticeId").value(pinned));
		postWithKey(this.operator, NOTICES, notice("다른", "common", false, null, null), "notice-" + this.tag)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		long upload = ApiClient.json(this.operator.uploadOutsideCourse("community_notice", "guide.pdf.txt", bytes("안내"))
			.andExpect(status().isCreated())).get("uploadId").asLong();
		JsonNode forStudents = ApiClient.json(this.operator
			.postJson(NOTICES, notice("학생", "student", false, null, "[" + upload + "]"))
			.andExpect(status().isCreated()));
		long studentNotice = forStudents.get("noticeId").asLong();
		long file = forStudents.get("attachments").get(0).get("fileId").asLong();
		long professorNotice = created(this.operator.postJson(NOTICES, notice("교수", "professor", false, null, null)),
				"noticeId");
		String later = this.jdbc.sql("SELECT DATE_FORMAT(CURRENT_TIMESTAMP + INTERVAL 1 DAY, '%Y-%m-%dT%H:%i:%s')")
			.query(String.class)
			.single();
		long scheduled = created(this.operator.postJson(NOTICES, notice("예약", "common", true, later, null))
			.andExpect(jsonPath("$.published").value(false)), "noticeId");
		assertThat(audits("community_notice.create", pinned)).isEqualTo(1);

		// 고정 공지가 먼저다. 학생에게는 교수 대상과 예약 공지가 없다.
		get(this.student, NOTICES, "q", this.tag).andExpect(status().isOk())
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].noticeId").value(pinned))
			.andExpect(jsonPath("$.items[0].audience").value("common"))
			.andExpect(jsonPath("$.items[1].noticeId").value(studentNotice))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.counts.total").value(2))
			.andExpect(jsonPath("$.counts.professor").value(0));
		get(this.student, NOTICES, "q", this.tag, "audience", "student").andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].noticeId").value(studentNotice))
			.andExpect(jsonPath("$.counts.total").value(2));
		get(this.student, NOTICES, "q", this.tag, "size", "1", "page", "2")
			.andExpect(jsonPath("$.items[0].noticeId").value(studentNotice));
		get(this.professor, NOTICES, "q", this.tag).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[1].noticeId").value(professorNotice));
		get(this.operator, NOTICES, "q", this.tag).andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.canManage").value(true))
			.andExpect(jsonPath("$.items[0].noticeId").value(scheduled))
			.andExpect(jsonPath("$.items[0].published").value(false));
		get(this.student, NOTICES, "audience", "nobody").andExpect(status().isBadRequest());
		get(this.student, NOTICES, "page", "0").andExpect(status().isBadRequest());
		this.anonymous.get(NOTICES).andExpect(status().isUnauthorized());

		// 상세와 첨부는 그 대상만 본다. 조회는 읽음을 남기지 않고 읽음은 따로 보낸다.
		this.student.get(NOTICES + "/" + studentNotice).andExpect(status().isOk())
			.andExpect(jsonPath("$.authorName").value("테스트 STAFF"))
			.andExpect(jsonPath("$.read").value(false))
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.attachments[0].fileId").value(file))
			// 어느 계정이 운영자인지는 운영자끼리만 안다. 다른 사람에게는 이름만 준다.
			.andExpect(jsonPath("$.authorId").value(nullValue()))
			.andExpect(jsonPath("$.authorHasPhoto").value(false));
		this.data.photo(this.op);
		this.operator.get(NOTICES + "/" + studentNotice)
			.andExpect(jsonPath("$.authorId").value(this.op))
			.andExpect(jsonPath("$.authorHasPhoto").value(true));
		this.student.get(NOTICES + "/" + studentNotice).andExpect(jsonPath("$.authorHasPhoto").value(false));
		this.student.get(CommunityApiTests.content(file)).andExpect(status().isOk());
		this.professor.get(CommunityApiTests.content(file)).andExpect(status().isNotFound());
		this.professor.get(NOTICES + "/" + studentNotice).andExpect(status().isNotFound());
		this.student.get(NOTICES + "/" + scheduled).andExpect(status().isNotFound());
		this.student.post(NOTICES + "/" + scheduled + "/read").andExpect(status().isNotFound());
		this.operator.post(NOTICES + "/" + scheduled + "/read").andExpect(status().isNotFound());
		this.student.post(NOTICES + "/" + pinned + "/read").andExpect(jsonPath("$.noticeId").value(pinned))
			.andExpect(jsonPath("$.postId").doesNotExist())
			.andExpect(jsonPath("$.viewCount").value(1));
		this.student.post(NOTICES + "/" + pinned + "/read").andExpect(jsonPath("$.viewCount").value(1));
		this.student.get(NOTICES + "/" + pinned).andExpect(jsonPath("$.read").value(true));
		withoutCsrf(MockMvcRequestBuilders.post(NOTICES + "/" + pinned + "/read"), this.stu)
			.andExpect(status().isForbidden());

		// 운영자는 고정·대상·게시 일시·첨부를 바꾼다. 읽은 version이 낡았으면 409다.
		long replacement = ApiClient.json(this.operator.uploadOutsideCourse("community_notice", "v2.txt", bytes("새 안내"))
			.andExpect(status().isCreated())).get("uploadId").asLong();
		String edit = "{\"title\":\"" + this.tag + " 학생 수정\",\"audience\":\"common\",\"pinned\":false,"
				+ "\"attachments\":{\"keep\":[],\"add\":[" + replacement + "]},\"version\":1}";
		put(this.student, NOTICES + "/" + studentNotice, edit).andExpect(status().isNotFound());
		long newFile = ApiClient.json(put(this.operator, NOTICES + "/" + studentNotice, edit).andExpect(status().isOk())
			.andExpect(jsonPath("$.audience").value("common"))
			.andExpect(jsonPath("$.version").value(2))).get("attachments").get(0).get("fileId").asLong();
		put(this.operator, NOTICES + "/" + studentNotice, edit).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.professor.get(NOTICES + "/" + studentNotice).andExpect(status().isOk());
		this.professor.get(CommunityApiTests.content(newFile)).andExpect(status().isOk());
		this.student.get(CommunityApiTests.content(file)).andExpect(status().isNotFound());
		assertThat(audits("community_notice.update", studentNotice)).isEqualTo(1);

		this.student.delete(NOTICES + "/" + studentNotice).andExpect(status().isNotFound());
		this.operator.delete(NOTICES + "/" + studentNotice).andExpect(status().isNoContent());
		this.operator.delete(NOTICES + "/" + studentNotice).andExpect(status().isNotFound());
		this.professor.get(NOTICES + "/" + studentNotice).andExpect(status().isNotFound());
		this.professor.get(CommunityApiTests.content(newFile)).andExpect(status().isNotFound());
		assertThat(audits("community_notice.delete", studentNotice)).isEqualTo(1);
		for (long id : List.of(pinned, professorNotice, scheduled)) {
			this.operator.delete(NOTICES + "/" + id).andExpect(status().isNoContent());
		}
	}

	@Test
	void homeShowsSiteNoticesAndTheUnreadPinnedOnesForMe() throws Exception {
		long pinned = created(this.operator.postJson(NOTICES, notice("진입", "common", true, null, null)), "noticeId");
		long forStudents = created(this.operator.postJson(NOTICES, notice("학생", "student", false, null, null)),
				"noticeId");
		this.student.get("/api/me/home").andExpect(status().isOk())
			.andExpect(jsonPath("$.communityNotices[0].noticeId").value(pinned))
			.andExpect(jsonPath("$.communityNotices[0].pinned").value(true))
			.andExpect(jsonPath("$.communityNotices[?(@.noticeId == " + forStudents + ")]").isNotEmpty())
			.andExpect(jsonPath("$.noticesForMe[0].noticeId").value(pinned))
			.andExpect(jsonPath("$.noticesForMe[?(@.noticeId == " + forStudents + ")]").isEmpty());
		this.professor.get("/api/me/home")
			.andExpect(jsonPath("$.communityNotices[?(@.noticeId == " + forStudents + ")]").isEmpty())
			.andExpect(jsonPath("$.noticesForMe[0].noticeId").value(pinned));
		// 읽으면 진입 공지에서 빠지고 최근 공지에는 읽음으로 남는다.
		this.student.post(NOTICES + "/" + pinned + "/read").andExpect(status().isOk());
		this.student.get("/api/me/home")
			.andExpect(jsonPath("$.noticesForMe[?(@.noticeId == " + pinned + ")]").isEmpty())
			.andExpect(jsonPath("$.communityNotices[0].read").value(true));
		this.operator.delete(NOTICES + "/" + pinned).andExpect(status().isNoContent());
		this.operator.delete(NOTICES + "/" + forStudents).andExpect(status().isNoContent());
		this.professor.get("/api/me/home")
			.andExpect(jsonPath("$.noticesForMe[?(@.noticeId == " + pinned + ")]").isEmpty());
	}

	@Test
	void noticesAnnounceToTheirAudienceAndWithdrawWhenDeleted() throws Exception {
		long forStudents = created(this.operator.postJson(NOTICES, notice("학생 알림", "student", false, null, null)),
				"noticeId");
		long forStaff = created(this.operator.postJson(NOTICES, notice("교수 알림", "professor", false, null, null)),
				"noticeId");
		long forAll = created(this.operator.postJson(NOTICES, notice("전체 알림", "common", false, null, null)), "noticeId");

		// 공지를 쓴 transaction이 알림 사건을 남긴다. 받는 사람은 공지 대상이 가리키는 사람이다.
		assertThat(announcedTo(forStudents)).isEqualTo("student");
		assertThat(announcedTo(forStaff)).isEqualTo("staff");
		assertThat(announcedTo(forAll)).isEqualTo("all");

		// 대상을 바꾸면 알림 대상도 따라가고, 지우면 알림과 예약을 거둔다.
		put(this.operator, NOTICES + "/" + forStudents,
				"{\"title\":\"" + this.tag + " 학생 알림\",\"audience\":\"common\",\"pinned\":false,\"version\":1}")
			.andExpect(status().isOk());
		assertThat(announcedTo(forStudents)).isEqualTo("all");
		for (long id : List.of(forStudents, forStaff, forAll)) {
			this.operator.delete(NOTICES + "/" + id).andExpect(status().isNoContent());
			assertThat(announcedTo(id)).isNull();
		}
	}

	@Test
	void aScheduledNoticeEditedWithoutPublishAtIsPublishedNowAndAnnounced() throws Exception {
		String later = this.jdbc.sql("SELECT DATE_FORMAT(CURRENT_TIMESTAMP + INTERVAL 1 DAY, '%Y-%m-%dT%H:%i:%s')")
			.query(String.class)
			.single();
		long scheduled = created(this.operator.postJson(NOTICES, notice("예약", "common", false, later, null))
			.andExpect(jsonPath("$.published").value(false)), "noticeId");
		assertThat(announcedLater(scheduled)).isTrue();

		// 만들 때처럼 publishAt을 비우면(생략 또는 null) 지금 게시하고 알림 예약도 지금으로 당긴다.
		put(this.operator, NOTICES + "/" + scheduled, "{\"title\":\"" + this.tag
				+ " 예약\",\"audience\":\"common\",\"pinned\":false,\"publishAt\":null,\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.published").value(true));
		assertThat(announcedLater(scheduled)).isFalse();
		this.student.get(NOTICES + "/" + scheduled).andExpect(status().isOk());
		this.operator.delete(NOTICES + "/" + scheduled).andExpect(status().isNoContent());
	}

	private boolean announcedLater(long noticeId) {
		return this.jdbc.sql("""
				SELECT visible_at > CURRENT_TIMESTAMP(3) FROM notification_events
				 WHERE notification_type = 'SITE_NOTICE' AND target_ref_id = :id
				""").param("id", noticeId).query(Boolean.class).single();
	}

	private String announcedTo(long noticeId) {
		return this.jdbc.sql("""
				SELECT site_audience FROM notification_events
				 WHERE notification_type = 'SITE_NOTICE' AND target_ref_id = :id
				""").param("id", noticeId).query(String.class).optional().orElse(null);
	}

	private String notice(String title, String audience, boolean pinned, String publishAt, String uploadIds) {
		return "{\"title\":\"" + this.tag + " " + title + "\",\"body\":\"본문\",\"audience\":\"" + audience + "\",\"pinned\":"
				+ pinned + (publishAt == null ? "" : ",\"publishAt\":\"" + publishAt + "\"")
				+ (uploadIds == null ? "" : ",\"uploadIds\":" + uploadIds) + "}";
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

}
