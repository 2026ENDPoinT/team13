package com.stackcorp.astra.communication;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 알림마당 자료실과 1:1 문의 조회. 자료는 기관 역할에 맞는 대상만, 문의는 본인 것만 보인다. 전역 게시판은 모든 테스트가 함께 쓰므로 테스트마다
 * 고유한 제목 머리말로 검색해 섞이지 않게 한다.
 */
class CommunityApiTests extends BoardTestSupport {

	String tag;

	long staff;

	@BeforeEach
	void boards() {
		this.tag = "t" + UUID.randomUUID().toString().substring(0, 8);
		this.staff = this.data.user("STAFF");
	}

	@Test
	void materialsFollowTheAudienceOfTheInstitutionRole() throws Exception {
		long board = this.data.insert("INSERT INTO boards (board_type, board_name) VALUES ('MATERIAL', '자료실')", Map.of());
		long common = material(board, "ALL", "INTERVAL 1 DAY", false);
		long forStudents = material(board, "STUDENT", "INTERVAL 2 DAY", false);
		long forProfessors = material(board, "PROFESSOR", "INTERVAL 3 DAY", false);
		long scheduled = material(board, "ALL", "INTERVAL -1 DAY", false);
		long deleted = material(board, "ALL", "INTERVAL 1 DAY", true);

		get(this.student, materials(), "q", this.tag).andExpect(status().isOk())
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
		get(new ApiClient(this.mvc, this.data.session(this.staff)), materials(), "q", this.tag)
			.andExpect(jsonPath("$.counts.total").value(3));
		get(this.student, materials(), "q", this.tag, "size", "1")
			.andExpect(jsonPath("$.items[0].postId").value(forStudents))
			.andExpect(jsonPath("$.nextCursor").value(forStudents));
		get(this.student, materials(), "audience", "nobody").andExpect(status().isBadRequest());

		this.student.get(materials() + "/" + common).andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("본문 " + this.tag))
			.andExpect(jsonPath("$.read").value(false));
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
		long board = this.data.insert("INSERT INTO boards (board_type, board_name) VALUES ('H002', '1:1 문의')", Map.of());
		long mine = inquiry(board, this.stu, true);
		long open = inquiry(board, this.stu, false);
		long theirs = inquiry(board, this.mate, false);
		this.data.insert("""
				INSERT INTO posts (board_id, parent_post_id, author_id, post_kind, title, body_text)
				VALUES (:board, :parent, :staff, 'ANSWER', 're : 문의', '답변드립니다')
				""", Map.of("board", board, "parent", mine, "staff", this.staff));

		get(this.student, inquiries(), "q", this.tag).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].postId").value(open))
			.andExpect(jsonPath("$.items[1].postId").value(mine))
			.andExpect(jsonPath("$.counts.total").value(2))
			.andExpect(jsonPath("$.counts.answered").value(1))
			.andExpect(jsonPath("$.counts.unanswered").value(1));
		get(this.student, inquiries(), "q", this.tag, "status", "answered").andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(mine));
		get(this.student, inquiries(), "status", "mine").andExpect(status().isBadRequest());
		this.student.get(inquiries() + "/" + mine).andExpect(status().isOk())
			.andExpect(jsonPath("$.answered").value(true))
			.andExpect(jsonPath("$.replies[0].body").value("답변드립니다"))
			.andExpect(jsonPath("$.replies[0].authorName").value("테스트 STAFF"));
		this.student.get(inquiries() + "/" + theirs).andExpect(status().isNotFound());
		this.classmate.get(inquiries() + "/" + mine).andExpect(status().isNotFound());
		get(this.classmate, inquiries(), "q", this.tag).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(theirs));
		this.anonymous.get(inquiries()).andExpect(status().isUnauthorized());
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

	static String materials() {
		return "/api/community/materials";
	}

	static String inquiries() {
		return "/api/community/qna";
	}

}
