package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 알림마당 자료실과 1:1 문의. 자료는 기관 역할에 맞는 대상만, 문의는 쓴 사람과 알림마당 운영자만 본다. 자료 쓰기와 문의 답변은 운영자
 * 행(community_operators)이 있는 사람만 한다. 첨부는 과목 없이 올리고 과목 없는 경로로만 내려받는다. 전역 게시판은 모든 테스트가 함께
 * 쓰므로 테스트마다 고유한 제목 머리말로 검색해 섞이지 않게 한다.
 */
class CommunityApiTests extends BoardTestSupport {

	String tag;

	long staff;

	long op;

	ApiClient staffClient;

	ApiClient operator;

	@BeforeEach
	void boards() {
		this.tag = "t" + UUID.randomUUID().toString().substring(0, 8);
		this.staff = this.data.user("STAFF");
		this.staffClient = new ApiClient(this.mvc, this.data.session(this.staff));
		this.op = this.data.user("STAFF");
		this.jdbc.sql("INSERT INTO community_operators (user_id, grant_reference) VALUES (:u, 'test')")
			.param("u", this.op)
			.update();
		this.operator = new ApiClient(this.mvc, this.data.session(this.op));
	}

	@Test
	void materialsFollowTheAudienceOfTheInstitutionRole() throws Exception {
		long board = board("MATERIAL");
		long common = material(board, "ALL", "INTERVAL 1 DAY", false);
		long forStudents = material(board, "STUDENT", "INTERVAL 2 DAY", false);
		long forProfessors = material(board, "PROFESSOR", "INTERVAL 3 DAY", false);
		long scheduled = material(board, "ALL", "INTERVAL -1 DAY", false);
		long deleted = material(board, "ALL", "INTERVAL 1 DAY", true);

		get(this.student, materials(), "q", this.tag).andExpect(status().isOk())
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].postId").value(forStudents))
			.andExpect(jsonPath("$.items[0].audience").value("student"))
			.andExpect(jsonPath("$.items[1].postId").value(common))
			.andExpect(jsonPath("$.items[1].audience").value("common"))
			.andExpect(jsonPath("$.counts.total").value(2))
			.andExpect(jsonPath("$.counts.professor").value(0));
		get(this.student, materials(), "q", this.tag, "audience", "professor").andExpect(jsonPath("$.items.length()").value(0));
		get(this.professor, materials(), "q", this.tag).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].postId").value(forProfessors))
			.andExpect(jsonPath("$.counts.student").value(0));
		get(this.staffClient, materials(), "q", this.tag).andExpect(jsonPath("$.counts.total").value(3))
			.andExpect(jsonPath("$.canManage").value(false));
		get(this.operator, materials(), "q", this.tag).andExpect(jsonPath("$.canManage").value(true));
		get(this.student, materials(), "q", this.tag, "size", "1")
			.andExpect(jsonPath("$.items[0].postId").value(forStudents))
			.andExpect(jsonPath("$.nextCursor").value(forStudents));
		get(this.student, materials(), "audience", "nobody").andExpect(status().isBadRequest());

		this.student.get(materials() + "/" + common).andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("본문 " + this.tag))
			.andExpect(jsonPath("$.read").value(false))
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.attachments.length()").value(0));
		for (long hidden : new long[] { forProfessors, scheduled, deleted }) {
			this.student.get(materials() + "/" + hidden).andExpect(status().isNotFound());
			this.student.post(materials() + "/" + hidden + "/read").andExpect(status().isNotFound());
		}
		this.student.post(materials() + "/" + common + "/read").andExpect(jsonPath("$.viewCount").value(1));
		this.student.post(materials() + "/" + common + "/read").andExpect(jsonPath("$.viewCount").value(1));
		this.student.get(materials() + "/" + common).andExpect(jsonPath("$.read").value(true));
		this.anonymous.get(materials()).andExpect(status().isUnauthorized());
	}

	@Test
	void inquiriesAreVisibleOnlyToTheirAuthor() throws Exception {
		long board = board("H002");
		long mine = inquiry(board, this.stu, true);
		long open = inquiry(board, this.stu, false);
		long theirs = inquiry(board, this.mate, false);
		this.data.insert("""
				INSERT INTO posts (board_id, parent_post_id, author_id, post_kind, title, body_text)
				VALUES (:board, :parent, :staff, 'ANSWER', 're : 문의', '답변드립니다')
				""", Map.of("board", board, "parent", mine, "staff", this.staff));

		get(this.student, inquiries(), "q", this.tag).andExpect(status().isOk())
			.andExpect(jsonPath("$.canManage").value(false))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].postId").value(open))
			.andExpect(jsonPath("$.items[0].authorName").doesNotExist())
			.andExpect(jsonPath("$.items[1].postId").value(mine))
			.andExpect(jsonPath("$.counts.total").value(2))
			.andExpect(jsonPath("$.counts.answered").value(1))
			.andExpect(jsonPath("$.counts.unanswered").value(1));
		get(this.student, inquiries(), "q", this.tag, "status", "answered").andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(mine));
		get(this.student, inquiries(), "status", "mine").andExpect(status().isBadRequest());
		this.student.get(inquiries() + "/" + mine).andExpect(status().isOk())
			.andExpect(jsonPath("$.answered").value(true))
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.canAnswer").value(false))
			.andExpect(jsonPath("$.replies[0].body").value("답변드립니다"))
			.andExpect(jsonPath("$.replies[0].authorName").value("테스트 STAFF"));
		this.student.get(inquiries() + "/" + theirs).andExpect(status().isNotFound());
		this.classmate.get(inquiries() + "/" + mine).andExpect(status().isNotFound());
		// 운영자 행이 없는 직원도 남의 문의를 보지 못한다.
		this.staffClient.get(inquiries() + "/" + mine).andExpect(status().isNotFound());
		get(this.staffClient, inquiries(), "all", "true").andExpect(status().isNotFound());
		get(this.classmate, inquiries(), "q", this.tag).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(theirs));
		// 운영자는 모든 문의와 작성자 이름을 본다.
		get(this.operator, inquiries(), "q", this.tag, "all", "true").andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.canManage").value(true))
			.andExpect(jsonPath("$.counts.total").value(3))
			.andExpect(jsonPath("$.items[0].authorName").value("테스트 STUDENT"));
		// 운영자 판정은 all=true 없이도 목록에 나온다. 화면은 이 값으로 관리 메뉴를 연다.
		get(this.operator, inquiries(), "q", this.tag).andExpect(jsonPath("$.canManage").value(true))
			.andExpect(jsonPath("$.items.length()").value(0));
		this.operator.get(inquiries() + "/" + theirs).andExpect(status().isOk())
			.andExpect(jsonPath("$.mine").value(false))
			.andExpect(jsonPath("$.canAnswer").value(true));
		this.anonymous.get(inquiries()).andExpect(status().isUnauthorized());
	}

	@Test
	void operatorsPublishMaterialsWhoseFilesOnlyTheAudienceDownloads() throws Exception {
		// 과목 밖 용도는 courseId 없이, 과목 용도는 courseId와 함께만 올린다. 자료실 첨부는 운영자만 올린다.
		this.student.uploadOutsideCourse("post_attachment", "a.txt", bytes("x")).andExpect(status().isBadRequest());
		this.student.upload(this.course, "inquiry_attachment", "a.txt", bytes("x"), null).andExpect(status().isBadRequest());
		this.student.uploadOutsideCourse("community_material", "a.txt", bytes("x")).andExpect(status().isNotFound());
		this.staffClient.uploadOutsideCourse("community_material", "a.txt", bytes("x")).andExpect(status().isNotFound());
		long first = outsideUpload(this.operator, "community_material", "guide.txt", "이용 안내");

		String create = "{\"title\":\"" + this.tag + " 학생 자료\",\"body\":\"안내\",\"audience\":\"student\",\"uploadIds\":[" + first
				+ "]}";
		for (ApiClient client : List.of(this.student, this.staffClient, this.professor)) {
			client.postJson(materials(), create).andExpect(status().isNotFound());
		}
		this.anonymous.postJson(materials(), create).andExpect(status().isUnauthorized());
		withoutCsrf(MockMvcRequestBuilders.post(materials()), this.op).andExpect(status().isForbidden());
		this.operator.postJson(materials(), "{\"title\":\"x\",\"audience\":\"all\"}").andExpect(status().isBadRequest());

		JsonNode created = ApiClient.json(postWithKey(this.operator, materials(), create, "material-" + this.tag)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.canManage").value(true))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.audience").value("student")));
		long id = created.get("postId").asLong();
		long file = created.get("attachments").get(0).get("fileId").asLong();
		postWithKey(this.operator, materials(), create, "material-" + this.tag).andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(id));
		postWithKey(this.operator, materials(), "{\"title\":\"다른 자료\",\"audience\":\"common\"}", "material-" + this.tag)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(audits("community_material.create", id)).isEqualTo(1);

		// 자료를 볼 수 있는 사람만 파일을 받는다. 과목 경로로는 열리지 않는다.
		this.student.get(materials() + "/" + id).andExpect(jsonPath("$.attachments[0].fileId").value(file))
			.andExpect(jsonPath("$.canManage").value(false));
		this.student.get(content(file)).andExpect(status().isOk());
		this.operator.get(content(file)).andExpect(status().isOk());
		this.professor.get(content(file)).andExpect(status().isNotFound());
		this.student.get(QnaApiTests.file(this.course, file)).andExpect(status().isNotFound());
		this.anonymous.get(content(file)).andExpect(status().isUnauthorized());
		// 과목 첨부는 과목 없는 경로로 열리지 않는다.
		long coursePost = ApiClient.json(this.student.postJson(OpenBoardApiTests.posts(this.course),
				"{\"title\":\"글\",\"uploadIds\":[" + upload(this.student, "c.txt", "과목 파일") + "]}")
			.andExpect(status().isCreated())).get("attachments").get(0).get("fileId").asLong();
		this.student.get(content(coursePost)).andExpect(status().isNotFound());

		// 운영자는 첨부를 바꾸며 고친다. 빠진 파일은 바로 읽히지 않는다.
		long second = outsideUpload(this.operator, "community_material", "guide2.txt", "새 안내");
		String edit = "{\"title\":\"" + this.tag + " 학생 자료\",\"audience\":\"student\",\"attachments\":{\"keep\":[],\"add\":["
				+ second + "]},\"version\":1}";
		put(this.student, materials() + "/" + id, edit).andExpect(status().isNotFound());
		JsonNode edited = ApiClient.json(put(this.operator, materials() + "/" + id, edit).andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2)));
		long secondFile = edited.get("attachments").get(0).get("fileId").asLong();
		put(this.operator, materials() + "/" + id, edit).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.get(content(file)).andExpect(status().isNotFound());
		this.student.get(content(secondFile)).andExpect(status().isOk());
		assertThat(audits("community_material.update", id)).isEqualTo(1);

		// 지우면 자료와 파일 모두 누구에게도 없다.
		this.student.delete(materials() + "/" + id).andExpect(status().isNotFound());
		this.operator.delete(materials() + "/" + id).andExpect(status().isNoContent());
		this.operator.delete(materials() + "/" + id).andExpect(status().isNotFound());
		this.student.get(materials() + "/" + id).andExpect(status().isNotFound());
		this.operator.get(content(secondFile)).andExpect(status().isNotFound());
		assertThat(audits("community_material.delete", id)).isEqualTo(1);

		// 운영자 행을 해제하면 다음 요청부터 권한이 없다.
		this.jdbc.sql("UPDATE community_operators SET revoked_at = CURRENT_TIMESTAMP(3) WHERE user_id = :u")
			.param("u", this.op)
			.update();
		this.operator.postJson(materials(), "{\"title\":\"해제 뒤\",\"audience\":\"common\"}").andExpect(status().isNotFound());
		this.operator.uploadOutsideCourse("community_material", "a.txt", bytes("x")).andExpect(status().isNotFound());
	}

	@Test
	void anyUserAsksAnInquiryThatOnlyTheAskerAndOperatorsSee() throws Exception {
		long attached = outsideUpload(this.student, "inquiry_attachment", "error.txt", "오류 화면");
		String ask = "{\"title\":\"" + this.tag + " 로그인 문의\",\"body\":\"로그인이 안 됩니다\",\"uploadIds\":[" + attached + "]}";
		JsonNode created = ApiClient.json(postWithKey(this.student, inquiries(), ask, "inquiry-" + this.tag)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.answered").value(false))
			.andExpect(jsonPath("$.canAnswer").value(false)));
		long id = created.get("postId").asLong();
		long file = created.get("attachments").get(0).get("fileId").asLong();
		postWithKey(this.student, inquiries(), ask, "inquiry-" + this.tag).andExpect(jsonPath("$.postId").value(id));
		postWithKey(this.student, inquiries(), "{\"title\":\"다른 문의\"}", "inquiry-" + this.tag)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(count("SELECT COUNT(*) FROM posts WHERE author_id = :id AND post_kind = 'QUESTION'", this.stu))
			.isEqualTo(1);
		assertThat(audits("community_inquiry.create", id)).isEqualTo(1);

		// 제목은 필수이고 일반 텍스트다. 본문은 비어도 된다. 작성자는 받지 않는다.
		this.student.postJson(inquiries(), "{\"title\":\" \"}").andExpect(status().isBadRequest());
		this.student.postJson(inquiries(), "{\"title\":\"a\\u0007b\"}").andExpect(status().isBadRequest());
		this.student.postJson(inquiries(), "{\"title\":\"t\",\"authorId\":" + this.mate + "}").andExpect(status().isBadRequest());
		// 남의 업로드는 붙이지 못하고, 실패하면 문의도 남지 않는다.
		long others = outsideUpload(this.classmate, "inquiry_attachment", "o.txt", "남의 파일");
		this.student.postJson(inquiries(), "{\"title\":\"" + this.tag + " 남의 파일\",\"uploadIds\":[" + others + "]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		this.anonymous.postJson(inquiries(), ask).andExpect(status().isUnauthorized());
		withoutCsrf(MockMvcRequestBuilders.post(inquiries()), this.stu).andExpect(status().isForbidden());

		// 쓴 사람과 운영자만 문의와 파일을 본다.
		this.classmate.get(inquiries() + "/" + id).andExpect(status().isNotFound());
		this.staffClient.get(inquiries() + "/" + id).andExpect(status().isNotFound());
		this.operator.get(inquiries() + "/" + id).andExpect(status().isOk())
			.andExpect(jsonPath("$.authorName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.canAnswer").value(true));
		this.student.get(content(file)).andExpect(status().isOk());
		this.operator.get(content(file)).andExpect(status().isOk());
		this.classmate.get(content(file)).andExpect(status().isNotFound());
		this.staffClient.get(content(file)).andExpect(status().isNotFound());
		this.student.get(QnaApiTests.file(this.course, file)).andExpect(status().isNotFound());

		// 운영자만 답한다. 답하면 문의가 답변완료가 되고 쓴 사람에게 알림이 간다.
		String replies = inquiries() + "/" + id + "/replies";
		for (ApiClient client : List.of(this.student, this.classmate, this.staffClient)) {
			client.postJson(replies, "{\"body\":\"답\"}").andExpect(status().isNotFound());
		}
		this.operator.postJson(replies, "{\"body\":\" \"}").andExpect(status().isBadRequest());
		this.operator.postJson(inquiries() + "/9223372036854775807/replies", "{\"body\":\"답\"}")
			.andExpect(status().isNotFound());
		withoutCsrf(MockMvcRequestBuilders.post(replies), this.op).andExpect(status().isForbidden());
		long replyUpload = outsideUpload(this.operator, "inquiry_attachment", "fix.txt", "해결 방법");
		JsonNode reply = ApiClient.json(postWithKey(this.operator, replies,
				"{\"body\":\"비밀번호를 재설정했습니다\",\"uploadIds\":[" + replyUpload + "]}", "reply-" + this.tag)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.authorName").value("테스트 STAFF")));
		long replyFile = reply.get("attachments").get(0).get("fileId").asLong();
		postWithKey(this.operator, replies, "{\"body\":\"비밀번호를 재설정했습니다\",\"uploadIds\":[" + replyUpload + "]}",
				"reply-" + this.tag)
			.andExpect(jsonPath("$.replyId").value(reply.get("replyId").asLong()));
		this.student.get(inquiries() + "/" + id).andExpect(jsonPath("$.answered").value(true))
			.andExpect(jsonPath("$.replies.length()").value(1))
			.andExpect(jsonPath("$.replies[0].attachments[0].fileId").value(replyFile));
		get(this.student, inquiries(), "q", this.tag, "status", "answered").andExpect(jsonPath("$.items[0].postId").value(id))
			.andExpect(jsonPath("$.items[0].answerCount").value(1));
		this.student.get(content(replyFile)).andExpect(status().isOk());
		this.classmate.get(content(replyFile)).andExpect(status().isNotFound());
		assertThat(count("""
				SELECT COUNT(*) FROM notifications
				 WHERE notification_type = 'QUESTION_ANSWERED' AND target_ref_id = :id AND course_id IS NULL
				""", id)).isEqualTo(1);
		assertThat(audits("community_inquiry.answer", reply.get("replyId").asLong())).isEqualTo(1);
	}

	@Test
	void inquiryPeopleCarryTheirIdOnlyWhereTheAudienceAllowsIt() throws Exception {
		this.data.photo(this.stu);
		this.data.photo(this.op);
		long id = created(postWithKey(this.student, inquiries(), "{\"title\":\"" + this.tag + " 사진\"}",
				"photo-" + this.tag), "postId");
		created(postWithKey(this.operator, inquiries() + "/" + id + "/replies", "{\"body\":\"답\"}",
				"photo-reply-" + this.tag), "replyId");
		// 문의한 사람은 자기 ID와 사진을 본다. 답한 운영자는 이름만 보인다(알림마당 공지와 같은 규칙).
		this.student.get(inquiries() + "/" + id)
			.andExpect(jsonPath("$.authorId").value(this.stu))
			.andExpect(jsonPath("$.authorHasPhoto").value(true))
			.andExpect(jsonPath("$.replies[0].authorName").value("테스트 STAFF"))
			.andExpect(jsonPath("$.replies[0].authorId").value(nullValue()))
			.andExpect(jsonPath("$.replies[0].authorHasPhoto").value(false));
		get(this.student, inquiries(), "q", this.tag)
			.andExpect(jsonPath("$.items[0].authorId").doesNotExist())
			.andExpect(jsonPath("$.items[0].authorHasPhoto").value(false));
		// 운영자는 두 사람의 ID를 본다. 사진은 아바타 경로처럼 과목을 함께할 때만 true다.
		this.operator.get(inquiries() + "/" + id)
			.andExpect(jsonPath("$.authorId").value(this.stu))
			.andExpect(jsonPath("$.authorHasPhoto").value(false))
			.andExpect(jsonPath("$.replies[0].authorId").value(this.op))
			.andExpect(jsonPath("$.replies[0].authorHasPhoto").value(true));
		get(this.operator, inquiries(), "q", this.tag, "all", "true")
			.andExpect(jsonPath("$.items[0].authorId").value(this.stu))
			.andExpect(jsonPath("$.items[0].authorHasPhoto").value(false));
		this.data.enroll(this.course, this.op);
		this.operator.get(inquiries() + "/" + id).andExpect(jsonPath("$.authorHasPhoto").value(true));
	}

	@Test
	void concurrentAnswersAreAllCounted() throws Exception {
		long id = created(this.student.postJson(inquiries(), "{\"title\":\"" + this.tag + " 동시 답변\"}"), "postId");
		List<Callable<ResultActions>> requests = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			String body = "{\"body\":\"답 " + i + "\"}";
			requests.add(() -> this.operator.postJson(inquiries() + "/" + id + "/replies", body));
		}
		assertThat(concurrently(requests)).containsOnly(201);
		assertThat(count("SELECT answer_count FROM posts WHERE post_id = :id", id)).isEqualTo(3);
		assertThat(count("SELECT COUNT(*) FROM posts WHERE parent_post_id = :id", id)).isEqualTo(3);
	}

	/** 알림마당 전역 게시판. 유형마다 하나라 이미 있으면 그 게시판이다. */
	private long board(String type) {
		this.jdbc.sql("INSERT IGNORE INTO boards (board_type, board_name) VALUES (:type, :type)").param("type", type).update();
		return this.jdbc.sql("SELECT board_id FROM boards WHERE community_board_key = :type")
			.param("type", type)
			.query(Long.class)
			.single();
	}

	/** @param age 게시 일시를 지금보다 얼마나 앞으로 둘지(SQL INTERVAL). 음수면 아직 게시 전 */
	private long material(long board, String audience, String age, boolean deleted) {
		return this.data.insert("""
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, audience, published_at, deleted_at)
				VALUES (:board, :staff, 'MATERIAL', :title, :body, :audience, CURRENT_TIMESTAMP(3) - %s,
				        CASE WHEN :deleted THEN CURRENT_TIMESTAMP(3) END)
				""".formatted(age), Map.of("board", board, "staff", this.staff, "title", this.tag + " " + audience, "body",
				"본문 " + this.tag, "audience", audience, "deleted", deleted));
	}

	private long inquiry(long board, long author, boolean answered) {
		return this.data.insert("""
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, is_answered, answer_count)
				VALUES (:board, :author, 'QUESTION', :title, '문의 본문', :answered, :count)
				""", Map.of("board", board, "author", author, "title", this.tag + " 문의", "answered", answered, "count",
				answered ? 1 : 0));
	}

	private static long outsideUpload(ApiClient client, String purpose, String name, String text) throws Exception {
		return ApiClient.json(client.uploadOutsideCourse(purpose, name, bytes(text)).andExpect(status().isCreated()))
			.get("uploadId")
			.asLong();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	static String content(long fileId) {
		return "/api/files/" + fileId + "/content";
	}

	static String materials() {
		return "/api/community/materials";
	}

	static String inquiries() {
		return "/api/community/qna";
	}

}
