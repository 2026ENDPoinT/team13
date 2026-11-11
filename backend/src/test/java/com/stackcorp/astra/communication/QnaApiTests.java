package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 강의실 질의응답. 권한은 과목 관계와 경로의 과목으로만 정해진다. 비공개 질문은 작성자와 담당자 밖에서는 어디에도 없고, 익명 질문의 작성자
 * 이름은 작성자와 담당자만 본다. 답글이 달린 질문은 작성자가 고치거나 지우지 못한다.
 */
class QnaApiTests extends BoardTestSupport {

	@Test
	void studentAsksInstructorAnswersAndTheAnsweredQuestionIsLocked() throws Exception {
		long id = created(this.student
			.postJson(qna(this.course), "{\"title\":\"  조인 질문 \",\"body\":\"<b>LEFT JOIN</b>\\n차이\",\"anonymous\":true}")
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.title").value("조인 질문"))
			.andExpect(jsonPath("$.authorName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.anonymous").value(true))
			.andExpect(jsonPath("$.secret").value(false))
			.andExpect(jsonPath("$.solved").value(false))
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.canEdit").value(true))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.commentCount").doesNotExist()), "postId");

		// 다른 학생에게는 익명 작성자의 이름도 ID도 없다. 담당자는 이름을 본다.
		String listed = this.classmate.get(qna(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(id))
			.andExpect(jsonPath("$.items[0].anonymous").value(true))
			.andExpect(jsonPath("$.items[0].mine").value(false))
			.andExpect(jsonPath("$.items[0].excerpt").value("<b>LEFT JOIN</b> 차이"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(listed).doesNotContain("테스트", "authorName", "authorId");
		String detail = this.classmate.get(question(this.course, id))
			.andExpect(jsonPath("$.body").value("<b>LEFT JOIN</b>\n차이"))
			.andExpect(jsonPath("$.canEdit").value(false))
			.andExpect(jsonPath("$.canDelete").value(false))
			.andExpect(jsonPath("$.canSetSolved").value(false))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(detail).doesNotContain("테스트", "authorName", "authorId");
		this.professor.get(question(this.course, id))
			.andExpect(jsonPath("$.authorName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.canEdit").value(false))
			.andExpect(jsonPath("$.canDelete").value(true))
			.andExpect(jsonPath("$.canSetFaq").value(true));

		long answer = created(this.professor.postJson(question(this.course, id) + "/answers", "{\"body\":\"ON 조건 차이\"}")
			.andExpect(jsonPath("$.instructor").value(true))
			.andExpect(jsonPath("$.authorName").value("테스트 PROFESSOR")), "answerId");
		this.classmate.get(question(this.course, id) + "/answers")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].answerId").value(answer))
			.andExpect(jsonPath("$.items[0].instructor").value(true))
			.andExpect(jsonPath("$.items[0].body").value("ON 조건 차이"))
			.andExpect(jsonPath("$.items[0].canDelete").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		this.classmate.get(qna(this.course)).andExpect(jsonPath("$.items[0].answerCount").value(1));
		// 질문 작성자만 답변 알림을 받는다. 자기 질문에 단 답글은 알리지 않고, 답글이 늘어도 알림은 하나다.
		long own = created(this.student.postJson(question(this.course, id) + "/answers", "{\"body\":\"추가 질문\"}"), "answerId");
		long second = created(this.classmate.postJson(question(this.course, id) + "/answers", "{\"body\":\"저도 궁금합니다\"}"),
				"answerId");
		assertThat(answerNotifications(id)).isOne();
		this.student.get("/api/me/notifications")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("QUESTION_ANSWERED"))
			.andExpect(jsonPath("$.items[0].targetId").value(id));
		this.classmate.delete(question(this.course, id) + "/answers/" + second).andExpect(status().isNoContent());
		this.student.delete(question(this.course, id) + "/answers/" + own).andExpect(status().isNoContent());
		assertThat(answerNotifications(id)).isOne();

		// 답글이 달린 질문은 작성자가 고치거나 지우지 못한다. 학생은 담당자의 답글을 지우지 못한다.
		put(this.student, question(this.course, id), "{\"title\":\"고친 질문\",\"version\":1}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_answered"));
		this.student.delete(question(this.course, id)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_answered"));
		this.student.delete(question(this.course, id) + "/answers/" + answer).andExpect(status().isNotFound());
		this.student.get(question(this.course, id)).andExpect(jsonPath("$.canEdit").value(false));

		// 해결 상태는 작성자와 담당자가, FAQ는 담당자만 정한다. 목표 상태라 다시 보내도 같다.
		put(this.student, question(this.course, id) + "/status", "{\"solved\":true}").andExpect(status().isOk())
			.andExpect(jsonPath("$.solved").value(true))
			.andExpect(jsonPath("$.faq").value(false));
		put(this.student, question(this.course, id) + "/status", "{\"solved\":true}").andExpect(jsonPath("$.solved").value(true));
		put(this.classmate, question(this.course, id) + "/status", "{\"solved\":false}").andExpect(status().isNotFound());
		put(this.student, question(this.course, id) + "/status", "{\"faq\":true}").andExpect(status().isNotFound());
		put(this.professor, question(this.course, id) + "/status", "{}").andExpect(status().isBadRequest());
		put(this.professor, question(this.course, id) + "/status", "{\"faq\":true}").andExpect(jsonPath("$.faq").value(true))
			.andExpect(jsonPath("$.solved").value(true));
		get(this.classmate, qna(this.course), "status", "faq").andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.counts.total").value(1))
			.andExpect(jsonPath("$.counts.faq").value(1))
			.andExpect(jsonPath("$.counts.solved").value(1))
			.andExpect(jsonPath("$.counts.unsolved").value(0));
		get(this.classmate, qna(this.course), "status", "unsolved").andExpect(jsonPath("$.total").value(0));
		get(this.classmate, qna(this.course), "status", "closed").andExpect(status().isBadRequest());

		// 담당자가 답글을 지우면 질문은 다시 답글 전이라 작성자가 고칠 수 있다. 익명을 풀면 이름이 보인다.
		this.professor.delete(question(this.course, id) + "/answers/" + answer).andExpect(status().isNoContent());
		assertThat(audits("qna_answer.delete", answer)).isEqualTo(1);
		assertThat(answerNotifications(id)).isZero();
		put(this.student, question(this.course, id), "{\"title\":\"고친 질문\",\"version\":1,\"anonymous\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.answerCount").value(0));
		this.classmate.get(question(this.course, id))
			.andExpect(jsonPath("$.title").value("고친 질문"))
			.andExpect(jsonPath("$.authorName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.body").doesNotExist());
		assertThat(audits("qna_question.update", id)).isEqualTo(1);
	}

	@Test
	void privateQuestionIsInvisibleToClassmatesEverywhere() throws Exception {
		long upload = upload(this.student, "note.txt", "비공개 첨부");
		JsonNode asked = ApiClient.json(this.student
			.postJson(qna(this.course), "{\"title\":\"성적 이의 비밀\",\"secret\":true,\"uploadIds\":[" + upload + "]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.secret").value(true))
			.andExpect(jsonPath("$.attachments[0].name").value("note.txt")));
		long id = asked.get("postId").asLong();
		long file = asked.get("attachments").get(0).get("fileId").asLong();
		created(this.classmate.postJson(qna(this.course), "{\"title\":\"공개 질문\"}"), "postId");

		// 같은 과목의 다른 학생: 목록, 건수, 검색, 상세, 답글, 읽음, 추천, 첨부 어디에도 없다.
		this.classmate.get(qna(this.course))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.counts.total").value(1))
			.andExpect(jsonPath("$.counts.unsolved").value(1))
			.andExpect(jsonPath("$.items[0].title").value("공개 질문"));
		get(this.classmate, qna(this.course), "q", "비밀").andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.counts.total").value(0))
			.andExpect(jsonPath("$.items.length()").value(0));
		this.classmate.get(question(this.course, id)).andExpect(status().isNotFound());
		this.classmate.get(question(this.course, id) + "/answers").andExpect(status().isNotFound());
		this.classmate.postJson(question(this.course, id) + "/answers", "{\"body\":\"답\"}").andExpect(status().isNotFound());
		this.classmate.post(question(this.course, id) + "/read").andExpect(status().isNotFound());
		put(this.classmate, question(this.course, id) + "/like").andExpect(status().isNotFound());
		this.classmate.get(file(this.course, file)).andExpect(status().isNotFound());
		assertThat(count("SELECT COUNT(*) FROM post_likes WHERE post_id = :id", id)).isZero();

		// 작성자와 담당자는 본다.
		this.student.get(qna(this.course)).andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.counts.total").value(2));
		this.professor.get(qna(this.course)).andExpect(jsonPath("$.total").value(2));
		get(this.professor, qna(this.course), "q", "비밀").andExpect(jsonPath("$.total").value(1));
		this.professor.get(question(this.course, id)).andExpect(status().isOk()).andExpect(jsonPath("$.secret").value(true));
		this.student.get(file(this.course, file)).andExpect(status().isOk());
		this.professor.get(file(this.course, file)).andExpect(status().isOk());

		// 비공개 질문의 답글 첨부도 질문의 권한을 따른다.
		long answerUpload = upload(this.professor, "answer.txt", "답변 첨부");
		long answerFile = ApiClient.json(this.professor
			.postJson(question(this.course, id) + "/answers", "{\"body\":\"확인했습니다\",\"uploadIds\":[" + answerUpload + "]}")
			.andExpect(status().isCreated())).get("attachments").get(0).get("fileId").asLong();
		this.student.get(file(this.course, answerFile)).andExpect(status().isOk());
		this.classmate.get(file(this.course, answerFile)).andExpect(status().isNotFound());
		get(this.classmate, qna(this.course), "answered", "true").andExpect(jsonPath("$.total").value(0));
		get(this.professor, qna(this.course), "answered", "true").andExpect(jsonPath("$.total").value(1));

		// 다른 과목 경로로는 첨부에 닿지 않는다. 지운 질문의 첨부는 아무도 읽지 못한다.
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.stu);
		this.student.get(file(otherCourse, file)).andExpect(status().isNotFound());
		this.professor.delete(question(this.course, id)).andExpect(status().isNoContent());
		assertThat(audits("qna_question.moderate", id)).isEqualTo(1);
		this.student.get(file(this.course, file)).andExpect(status().isNotFound());
		this.student.get(file(this.course, answerFile)).andExpect(status().isNotFound());
		this.professor.get(file(this.course, answerFile)).andExpect(status().isNotFound());
		this.student.get(question(this.course, id)).andExpect(status().isNotFound());
	}

	@Test
	void everyEndpointFollowsTheAuthorizationMatrix() throws Exception {
		long id = created(this.student.postJson(qna(this.course), "{\"title\":\"질문\"}"), "postId");
		long answer = created(this.student.postJson(question(this.course, id) + "/answers", "{\"body\":\"추가 질문\"}"),
				"answerId");
		String q = question(this.course, id);
		String a = q + "/answers/" + answer;
		String edit = "{\"title\":\"고친 질문\",\"version\":1}";
		String editAnswer = "{\"body\":\"고친 답\",\"version\":1}";

		// 담당자는 질문을 쓰지 않는다. 다른 학생은 남의 질문·답글을 고치거나 지우지 못한다.
		this.professor.postJson(qna(this.course), "{\"title\":\"교수 질문\"}").andExpect(status().isNotFound());
		put(this.classmate, q, edit).andExpect(status().isNotFound());
		this.classmate.delete(q).andExpect(status().isNotFound());
		put(this.classmate, a, editAnswer).andExpect(status().isNotFound());
		this.classmate.delete(a).andExpect(status().isNotFound());
		put(this.professor, q, edit).andExpect(status().isNotFound());
		put(this.professor, a, editAnswer).andExpect(status().isNotFound());

		// 과목 밖의 사용자, 다른 과목의 경로, 다른 게시판의 경로: 모두 404. 두 과목에 모두 속해도 같다(SC-03).
		long otherCourse = this.data.course();
		this.data.instructor(otherCourse, this.prof);
		this.data.enroll(otherCourse, this.stu);
		String otherQ = question(otherCourse, id);
		for (ApiClient client : List.of(this.outsider, this.student, this.professor)) {
			String base = client == this.outsider ? q : otherQ;
			client.get(base).andExpect(status().isNotFound());
			put(client, base, edit).andExpect(status().isNotFound());
			client.delete(base).andExpect(status().isNotFound());
			put(client, base + "/status", "{\"solved\":true}").andExpect(status().isNotFound());
			put(client, base + "/like").andExpect(status().isNotFound());
			client.delete(base + "/like").andExpect(status().isNotFound());
			client.post(base + "/read").andExpect(status().isNotFound());
			client.get(base + "/answers").andExpect(status().isNotFound());
			client.postJson(base + "/answers", "{\"body\":\"답\"}").andExpect(status().isNotFound());
			put(client, base + "/answers/" + answer, editAnswer).andExpect(status().isNotFound());
			client.delete(base + "/answers/" + answer).andExpect(status().isNotFound());
		}
		this.outsider.get(qna(this.course)).andExpect(status().isNotFound());
		this.outsider.postJson(qna(this.course), "{\"title\":\"질문\"}").andExpect(status().isNotFound());
		this.student.get(qna(otherCourse)).andExpect(jsonPath("$.total").value(0));
		this.student.get(OpenBoardApiTests.post(this.course, id)).andExpect(status().isNotFound());
		this.student.post(OpenBoardApiTests.post(this.course, id) + "/read").andExpect(status().isNotFound());

		// 로그인하지 않은 요청은 401, CSRF token이 없는 변경 요청은 403이다.
		this.anonymous.get(qna(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.get(q).andExpect(status().isUnauthorized());
		this.anonymous.get(q + "/answers").andExpect(status().isUnauthorized());
		this.anonymous.postJson(qna(this.course), "{\"title\":\"질문\"}").andExpect(status().isUnauthorized());
		this.anonymous.post(q + "/read").andExpect(status().isUnauthorized());
		put(this.anonymous, q + "/like").andExpect(status().isUnauthorized());
		this.anonymous.delete(q).andExpect(status().isUnauthorized());
		withoutCsrf(MockMvcRequestBuilders.post(qna(this.course)), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(q), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(q), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(q + "/status"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(q + "/like"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(q + "/like"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(q + "/read"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(q + "/answers"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(a), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(a), this.stu).andExpect(status().isForbidden());

		// 작성자: 자기 답글을 고치고 지운다. 답글이 없으면 질문을 고치고 지운다.
		put(this.student, a, editAnswer).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
		put(this.student, a, editAnswer).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.delete(a).andExpect(status().isNoContent());
		this.student.delete(a).andExpect(status().isNotFound());
		put(this.student, q, edit).andExpect(status().isOk());
		put(this.student, q, edit).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.delete(q).andExpect(status().isNoContent());
		this.student.delete(q).andExpect(status().isNotFound());
		this.classmate.get(qna(this.course)).andExpect(jsonPath("$.total").value(0));
		assertThat(audits("qna_question.delete", id)).isEqualTo(1);
		assertThat(audits("qna_answer.delete", answer)).isEqualTo(1);
	}

	@Test
	void requestBodiesAreCheckedPlainText() throws Exception {
		String[] invalid = { "{\"title\":\"  \"}", "{\"title\":\"a\\u0007b\"}", "{\"title\":\"" + "가".repeat(301) + "\"}",
				"{\"title\":\"질문\",\"userId\":" + this.mate + "}", "{\"title\":\"질문\",\"version\":1}",
				"{\"title\":\"질문\",\"uploadIds\":[0]}", "{\"title\":\"질문\",\"body\":\"" + "x".repeat(20_001) + "\"}" };
		for (String body : invalid) {
			this.student.postJson(qna(this.course), body).andExpect(status().isBadRequest());
		}
		long id = created(this.student.postJson(qna(this.course), "{\"title\":\"질문\"}"), "postId");
		put(this.student, question(this.course, id), "{\"title\":\"질문\"}").andExpect(status().isBadRequest());
		long upload = upload(this.student, "later.txt", "나중 첨부");
		put(this.student, question(this.course, id), "{\"title\":\"질문\",\"version\":1,\"uploadIds\":[" + upload + "]}")
			.andExpect(status().isBadRequest());
		this.student.postJson(question(this.course, id) + "/answers", "{\"body\":\"  \"}").andExpect(status().isBadRequest());
		this.professor.postJson(question(this.course, id) + "/answers", "{\"body\":\"답\",\"anonymous\":true}")
			.andExpect(status().isBadRequest());
		get(this.student, qna(this.course), "size", "51").andExpect(status().isBadRequest());
		get(this.student, qna(this.course), "page", "0").andExpect(status().isBadRequest());
		assertThat(count("SELECT COUNT(*) FROM posts WHERE author_id = :id", this.stu)).isEqualTo(1);
	}

	@Test
	void likeAndReadAreIdempotentPerUser() throws Exception {
		long id = created(this.student.postJson(qna(this.course), "{\"title\":\"추천할 질문\"}"), "postId");
		String q = question(this.course, id);
		put(this.classmate, q + "/like").andExpect(status().isOk())
			.andExpect(jsonPath("$.liked").value(true))
			.andExpect(jsonPath("$.likeCount").value(1));
		put(this.classmate, q + "/like").andExpect(jsonPath("$.likeCount").value(1));
		put(this.student, q + "/like").andExpect(jsonPath("$.likeCount").value(2));
		this.classmate.get(qna(this.course)).andExpect(jsonPath("$.items[0].liked").value(true))
			.andExpect(jsonPath("$.items[0].likeCount").value(2));
		this.professor.get(q).andExpect(jsonPath("$.liked").value(false));
		this.classmate.delete(q + "/like").andExpect(jsonPath("$.liked").value(false))
			.andExpect(jsonPath("$.likeCount").value(1));
		this.classmate.delete(q + "/like").andExpect(jsonPath("$.likeCount").value(1));
		assertThat(count("SELECT COUNT(*) FROM post_likes WHERE post_id = :id", id)).isEqualTo(1);

		// 조회는 상태를 바꾸지 않는다. 읽음은 몇 번 보내도 한 행이고 조회수는 처음 한 번만 오른다.
		this.classmate.get(q).andExpect(jsonPath("$.read").value(false)).andExpect(jsonPath("$.viewCount").value(0));
		assertThat(count("SELECT COUNT(*) FROM post_read_receipts WHERE post_id = :id", id)).isZero();
		this.classmate.post(q + "/read").andExpect(jsonPath("$.read").value(true)).andExpect(jsonPath("$.viewCount").value(1));
		this.classmate.post(q + "/read").andExpect(jsonPath("$.viewCount").value(1));
		this.classmate.get(qna(this.course)).andExpect(jsonPath("$.items[0].read").value(true));
		this.student.get(qna(this.course)).andExpect(jsonPath("$.items[0].read").value(false));
		assertThat(count("SELECT COUNT(*) FROM post_read_receipts WHERE post_id = :id", id)).isEqualTo(1);
	}

	@Test
	void concurrentLikesAndReadsCountEveryUserOnce() throws Exception {
		long first = created(this.student.postJson(qna(this.course), "{\"title\":\"첫 질문\"}"), "postId");
		long second = created(this.student.postJson(qna(this.course), "{\"title\":\"옆 질문\"}"), "postId");
		List<ApiClient> readers = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			long user = this.data.user("STUDENT");
			this.data.enroll(this.course, user);
			readers.add(new ApiClient(this.mvc, this.data.session(user)));
		}
		List<Callable<ResultActions>> requests = new ArrayList<>();
		for (ApiClient reader : readers) {
			for (long id : List.of(first, second)) {
				// 같은 사용자가 같은 글에 두 번씩 보낸다.
				requests.add(() -> put(reader, question(this.course, id) + "/like"));
				requests.add(() -> put(reader, question(this.course, id) + "/like"));
				requests.add(() -> reader.post(question(this.course, id) + "/read"));
			}
		}
		assertThat(concurrently(requests)).containsOnly(200);
		for (long id : List.of(first, second)) {
			assertThat(count("SELECT COUNT(*) FROM post_likes WHERE post_id = :id", id)).isEqualTo(4);
			assertThat(count("SELECT like_count FROM posts WHERE post_id = :id", id)).isEqualTo(4);
			assertThat(count("SELECT view_count FROM posts WHERE post_id = :id", id)).isEqualTo(4);
		}
	}

	@Test
	void titleSearchTreatsWildcardsLiterally() throws Exception {
		for (String title : List.of("100% 이해", "a_b 질문", "보통 질문", "역슬래시\\\\ 질문")) {
			created(this.student.postJson(qna(this.course), "{\"title\":\"" + title + "\"}"), "postId");
		}
		get(this.classmate, qna(this.course), "q", "%").andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].title").value("100% 이해"));
		get(this.classmate, qna(this.course), "q", "_").andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].title").value("a_b 질문"));
		get(this.classmate, qna(this.course), "q", "\\").andExpect(jsonPath("$.total").value(1));
		get(this.classmate, qna(this.course), "q", "질문").andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.counts.total").value(3));
		get(this.classmate, qna(this.course), "q", "질문", "mine", "true").andExpect(jsonPath("$.total").value(0));
		get(this.student, qna(this.course), "q", "질문", "mine", "true").andExpect(jsonPath("$.total").value(3));
		get(this.classmate, qna(this.course), "q", "x".repeat(101)).andExpect(status().isBadRequest());
	}

	@Test
	void pagesStayStableWhenQuestionsArriveBetweenRequests() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			ids.add(created(this.student.postJson(qna(this.course), "{\"title\":\"질문 " + i + "\"}"), "postId"));
		}
		JsonNode first = ApiClient.json(get(this.classmate, qna(this.course), "size", "2")
			.andExpect(jsonPath("$.items[0].postId").value(ids.get(4)))
			.andExpect(jsonPath("$.items[1].postId").value(ids.get(3)))
			.andExpect(jsonPath("$.total").value(5)));
		String snapshot = first.get("snapshot").asText();
		long late = created(this.classmate.postJson(qna(this.course), "{\"title\":\"늦은 질문\"}"), "postId");

		get(this.classmate, qna(this.course), "size", "2", "page", "2", "snapshot", snapshot)
			.andExpect(jsonPath("$.items[0].postId").value(ids.get(2)))
			.andExpect(jsonPath("$.items[1].postId").value(ids.get(1)))
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.snapshot").value(Long.parseLong(snapshot)));
		get(this.classmate, qna(this.course), "size", "2", "page", "3", "snapshot", snapshot)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(ids.get(0)));
		// 새로 첫 쪽을 받으면 늦은 질문이 맨 앞이다.
		get(this.classmate, qna(this.course), "size", "2").andExpect(jsonPath("$.items[0].postId").value(late))
			.andExpect(jsonPath("$.total").value(6));
	}

	@Test
	void attachmentFailureRollsBackTheQuestion() throws Exception {
		long own = upload(this.student, "own.txt", "내 첨부");
		long others = upload(this.classmate, "other.txt", "남의 첨부");
		this.student.postJson(qna(this.course), "{\"title\":\"남의 파일\",\"uploadIds\":[" + own + "," + others + "]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		assertThat(count("SELECT COUNT(*) FROM posts WHERE author_id = :id", this.stu)).isZero();
		assertThat(count("SELECT COUNT(*) FROM attachments WHERE upload_id = :id", own)).isZero();
		// 되돌렸으므로 내 업로드는 다시 쓸 수 있다. 첨부한 업로드는 한 번만 쓴다.
		created(this.student.postJson(qna(this.course), "{\"title\":\"내 파일\",\"uploadIds\":[" + own + "]}"), "postId");
		this.student.postJson(qna(this.course), "{\"title\":\"또 내 파일\",\"uploadIds\":[" + own + "]}")
			.andExpect(status().isConflict());
		assertThat(count("SELECT COUNT(*) FROM posts WHERE author_id = :id", this.stu)).isEqualTo(1);
		// 담당자도 게시글 첨부를 올린다. 과목 밖의 사용자는 올리지 못한다.
		upload(this.professor, "prof.txt", "담당자 첨부");
		this.outsider.upload(this.course, "post_attachment", "x.txt", "x".getBytes(StandardCharsets.UTF_8), null)
			.andExpect(status().isNotFound());
	}

	@Test
	void retriedCreatesWithTheSameIdempotencyKeyMakeOneQuestionAndOneAnswer() throws Exception {
		String body = "{\"title\":\"한 번만\"}";
		long first = created(postWithKey(this.student, qna(this.course), body, "qna-retry-1"), "postId");
		postWithKey(this.student, qna(this.course), body, "qna-retry-1").andExpect(status().isCreated())
			.andExpect(jsonPath("$.postId").value(first));
		postWithKey(this.student, qna(this.course), "{\"title\":\"다른 내용\"}", "qna-retry-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		// key는 사람마다 따로다.
		long other = created(postWithKey(this.classmate, qna(this.course), body, "qna-retry-1"), "postId");
		assertThat(other).isNotEqualTo(first);

		String answers = question(this.course, first) + "/answers";
		long answer = created(postWithKey(this.professor, answers, "{\"body\":\"답\"}", "answer-retry-1"), "answerId");
		postWithKey(this.professor, answers, "{\"body\":\"답\"}", "answer-retry-1")
			.andExpect(jsonPath("$.answerId").value(answer));
		assertThat(count("SELECT COUNT(*) FROM posts WHERE parent_post_id = :id", first)).isEqualTo(1);
		assertThat(count("SELECT answer_count FROM posts WHERE post_id = :id", first)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM posts WHERE author_id = :id", this.stu)).isEqualTo(1);
	}

	@Test
	void concurrentFirstQuestionsShareOneBoard() throws Exception {
		List<Callable<ResultActions>> requests = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			long user = this.data.user("STUDENT");
			this.data.enroll(this.course, user);
			ApiClient client = new ApiClient(this.mvc, this.data.session(user));
			String json = "{\"title\":\"동시 " + i + "\"}";
			requests.add(() -> client.postJson(qna(this.course), json));
		}
		assertThat(concurrently(requests)).containsOnly(201);
		assertThat(count("SELECT COUNT(*) FROM boards WHERE course_id = :id AND board_type = 'K002'", this.course))
			.isEqualTo(1);
		this.student.get(qna(this.course)).andExpect(jsonPath("$.total").value(4));
	}

	@Test
	void concurrentAnswerAndEditNeverLeaveAnEditedAnsweredQuestion() throws Exception {
		for (int round = 0; round < 3; round++) {
			long id = created(this.student.postJson(qna(this.course), "{\"title\":\"경합 " + round + "\"}"), "postId");
			List<Integer> statuses = concurrently(List.of(
					() -> this.professor.postJson(question(this.course, id) + "/answers", "{\"body\":\"답\"}"),
					() -> put(this.student, question(this.course, id), "{\"title\":\"고침\",\"version\":1}")));
			assertThat(statuses.get(0)).isEqualTo(201);
			long version = count("SELECT version FROM posts WHERE post_id = :id", id);
			// 수정이 이겼으면 답글보다 먼저 끝났다. 졌으면 409다. 어느 쪽이든 답글 수는 정확하다.
			assertThat(statuses.get(1)).isIn(200, 409);
			assertThat(version).isEqualTo(statuses.get(1) == 200 ? 2 : 1);
			assertThat(count("SELECT answer_count FROM posts WHERE post_id = :id", id)).isEqualTo(1);
		}
	}

	@Test
	void questionAndAnswerEditsReplaceAttachmentsAndKeepPrivateQuestionFilesPrivate() throws Exception {
		long asked = upload(this.student, "q.txt", "질문 파일");
		JsonNode question = ApiClient.json(this.student.postJson(qna(this.course),
				"{\"title\":\"비공개 질문\",\"secret\":true,\"uploadIds\":[" + asked + "]}").andExpect(status().isCreated()));
		long id = question.get("postId").asLong();
		long askedFile = question.get("attachments").get(0).get("fileId").asLong();
		long replaced = upload(this.student, "q2.txt", "바꾼 질문 파일");
		JsonNode edited = ApiClient.json(put(this.student, question(this.course, id),
				"{\"title\":\"비공개 질문\",\"secret\":true,\"attachments\":{\"keep\":[],\"add\":[" + replaced + "]},\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(1)));
		long newFile = edited.get("attachments").get(0).get("fileId").asLong();
		this.student.get(file(this.course, askedFile)).andExpect(status().isNotFound());
		this.professor.get(file(this.course, newFile)).andExpect(status().isOk());
		this.classmate.get(file(this.course, newFile)).andExpect(status().isNotFound());

		long answerUpload = upload(this.professor, "a.txt", "답 파일");
		JsonNode answer = ApiClient.json(this.professor.postJson(question(this.course, id) + "/answers",
				"{\"body\":\"답\",\"uploadIds\":[" + answerUpload + "]}").andExpect(status().isCreated()));
		long answerId = answer.get("answerId").asLong();
		long answerFile = answer.get("attachments").get(0).get("fileId").asLong();
		String a = question(this.course, id) + "/answers/" + answerId;
		long another = upload(this.professor, "a2.txt", "추가 답 파일");
		put(this.professor, a, "{\"body\":\"고친 답\",\"attachments\":{\"keep\":[" + answerFile + "],\"add\":[" + another
				+ "]},\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(2))
			.andExpect(jsonPath("$.version").value(2));
		// 남의 답글은 고치지 못하고, 낡은 version이면 첨부도 그대로다.
		put(this.student, a, "{\"body\":\"x\",\"attachments\":{\"keep\":[],\"add\":[]},\"version\":2}")
			.andExpect(status().isNotFound());
		put(this.professor, a, "{\"body\":\"낡음\",\"attachments\":{\"keep\":[],\"add\":[]},\"version\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.get(file(this.course, answerFile)).andExpect(status().isOk());
		this.classmate.get(file(this.course, answerFile)).andExpect(status().isNotFound());
		// 답글이 달린 질문은 첨부도 고치지 못한다.
		put(this.student, question(this.course, id),
				"{\"title\":\"비공개 질문\",\"secret\":true,\"attachments\":{\"keep\":[],\"add\":[]},\"version\":2}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_answered"));
		this.professor.get(file(this.course, newFile)).andExpect(status().isOk());
	}

	static String qna(long courseId) {
		return "/api/courses/" + courseId + "/qna";
	}

	static String question(long courseId, long questionId) {
		return qna(courseId) + "/" + questionId;
	}

	static String file(long courseId, long fileId) {
		return "/api/courses/" + courseId + "/files/" + fileId + "/content";
	}

	private long answerNotifications(long questionId) {
		return count("SELECT COUNT(*) FROM notifications WHERE notification_type = 'QUESTION_ANSWERED' AND target_ref_id = :id",
				questionId);
	}

}
