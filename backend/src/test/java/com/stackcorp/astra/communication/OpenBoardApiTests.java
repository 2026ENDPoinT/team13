package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 강의실 열린게시판. 구성원 누구나 쓰고 댓글을 달며, 작성자만 고친다. 작성자와 담당자가 지우고, 담당자가 남의 글을 지우면 관리 삭제로 감사
 * 기록이 남는다. 목록은 cursor로 이어 받고 새 글이 올라와도 밀리지 않는다.
 */
class OpenBoardApiTests extends BoardTestSupport {

	@Test
	void membersPostAndCommentAndDeletedCommentsKeepTheirRepliesThreaded() throws Exception {
		long upload = upload(this.student, "slides.txt", "발표 자료");
		JsonNode created = ApiClient.json(this.student
			.postJson(posts(this.course), "{\"title\":\"스터디 모집\",\"body\":\"목요일 저녁\",\"uploadIds\":[" + upload + "]}")
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.authorName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.commentCount").value(0))
			.andExpect(jsonPath("$.anonymous").doesNotExist())
			.andExpect(jsonPath("$.answerCount").doesNotExist())
			.andExpect(jsonPath("$.canSetSolved").doesNotExist()));
		long id = created.get("postId").asLong();
		long file = created.get("attachments").get(0).get("fileId").asLong();
		this.classmate.get(QnaApiTests.file(this.course, file)).andExpect(status().isOk());
		this.outsider.get(QnaApiTests.file(this.course, file)).andExpect(status().isNotFound());
		// 담당자도 글을 쓴다.
		created(this.professor.postJson(posts(this.course), "{\"title\":\"공지 대신\"}"), "postId");

		String comments = post(this.course, id) + "/comments";
		long parent = created(this.classmate.postJson(comments, "{\"body\":\"참여합니다\"}")
			.andExpect(jsonPath("$.mine").value(true))
			.andExpect(jsonPath("$.canDelete").value(true)), "commentId");
		long reply = created(this.student.postJson(comments, "{\"body\":\"환영합니다\",\"parentCommentId\":" + parent + "}")
			.andExpect(jsonPath("$.parentCommentId").value(parent)), "commentId");
		// 답글에는 답글을 달지 못한다. 다른 글의 댓글에도 달지 못한다.
		this.student.postJson(comments, "{\"body\":\"x\",\"parentCommentId\":" + reply + "}").andExpect(status().isBadRequest());
		long otherPost = created(this.classmate.postJson(posts(this.course), "{\"title\":\"다른 글\"}"), "postId");
		this.student.postJson(post(this.course, otherPost) + "/comments", "{\"body\":\"x\",\"parentCommentId\":" + parent + "}")
			.andExpect(status().isBadRequest());
		long third = created(this.professor.postJson(comments, "{\"body\":\"좋은 모임\"}"), "commentId");
		this.student.get(post(this.course, id)).andExpect(jsonPath("$.commentCount").value(3));

		// 남의 댓글은 지우지 못한다. 담당자는 지운다(관리 삭제).
		this.student.delete(comments + "/" + parent).andExpect(status().isNotFound());
		this.professor.delete(comments + "/" + parent).andExpect(status().isNoContent());
		assertThat(audits("board_comment.moderate", parent)).isEqualTo(1);
		// 지운 댓글은 답글이 남아 있는 동안 본문과 작성자 없이 자리만 남는다.
		this.classmate.get(comments)
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[0].commentId").value(parent))
			.andExpect(jsonPath("$.items[0].deleted").value(true))
			.andExpect(jsonPath("$.items[0].body").doesNotExist())
			.andExpect(jsonPath("$.items[0].authorName").doesNotExist())
			.andExpect(jsonPath("$.items[1].commentId").value(reply))
			.andExpect(jsonPath("$.items[1].body").value("환영합니다"))
			.andExpect(jsonPath("$.items[1].mine").value(false))
			.andExpect(jsonPath("$.items[2].commentId").value(third));
		this.student.delete(comments + "/" + reply).andExpect(status().isNoContent());
		assertThat(audits("board_comment.delete", reply)).isEqualTo(1);
		this.student.delete(comments + "/" + reply).andExpect(status().isNotFound());
		this.classmate.get(comments).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].commentId").value(third));
		this.classmate.get(posts(this.course)).andExpect(jsonPath("$.total").value(3));
		this.classmate.get(post(this.course, id)).andExpect(jsonPath("$.commentCount").value(1));

		// 댓글은 원본처럼 1800 byte(한글 2 byte)까지다.
		this.student.postJson(comments, "{\"body\":\"" + "가".repeat(900) + "\"}").andExpect(status().isCreated());
		this.student.postJson(comments, "{\"body\":\"" + "가".repeat(901) + "\"}").andExpect(status().isBadRequest());
		this.student.postJson(comments, "{\"body\":\" \"}").andExpect(status().isBadRequest());
		// 익명·비공개는 이 게시판에 없다.
		this.student.postJson(posts(this.course), "{\"title\":\"익명\",\"anonymous\":true}").andExpect(status().isBadRequest());
		this.student.postJson(posts(this.course), "{\"title\":\"비공개\",\"secret\":true}").andExpect(status().isBadRequest());
	}

	@Test
	void everyEndpointFollowsTheAuthorizationMatrix() throws Exception {
		long id = created(this.student.postJson(posts(this.course), "{\"title\":\"글\"}"), "postId");
		long comment = created(this.student.postJson(post(this.course, id) + "/comments", "{\"body\":\"댓글\"}"), "commentId");
		String p = post(this.course, id);
		String c = p + "/comments/" + comment;
		String edit = "{\"title\":\"고친 글\",\"version\":1}";

		// 작성자만 고친다. 담당자도 남의 글은 고치지 못한다.
		put(this.classmate, p, edit).andExpect(status().isNotFound());
		put(this.professor, p, edit).andExpect(status().isNotFound());
		this.classmate.delete(p).andExpect(status().isNotFound());
		this.classmate.delete(c).andExpect(status().isNotFound());

		long otherCourse = this.data.course();
		this.data.instructor(otherCourse, this.prof);
		this.data.enroll(otherCourse, this.stu);
		String otherP = post(otherCourse, id);
		for (ApiClient client : List.of(this.outsider, this.student, this.professor)) {
			String base = client == this.outsider ? p : otherP;
			client.get(base).andExpect(status().isNotFound());
			put(client, base, edit).andExpect(status().isNotFound());
			client.delete(base).andExpect(status().isNotFound());
			put(client, base + "/like").andExpect(status().isNotFound());
			client.delete(base + "/like").andExpect(status().isNotFound());
			client.post(base + "/read").andExpect(status().isNotFound());
			client.get(base + "/comments").andExpect(status().isNotFound());
			client.postJson(base + "/comments", "{\"body\":\"x\"}").andExpect(status().isNotFound());
			client.delete(base + "/comments/" + comment).andExpect(status().isNotFound());
		}
		this.outsider.get(posts(this.course)).andExpect(status().isNotFound());
		this.outsider.postJson(posts(this.course), "{\"title\":\"글\"}").andExpect(status().isNotFound());
		this.student.get(posts(otherCourse)).andExpect(jsonPath("$.total").value(0));
		this.student.get(QnaApiTests.question(this.course, id)).andExpect(status().isNotFound());

		this.anonymous.get(posts(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.get(p).andExpect(status().isUnauthorized());
		this.anonymous.get(p + "/comments").andExpect(status().isUnauthorized());
		this.anonymous.postJson(posts(this.course), "{\"title\":\"글\"}").andExpect(status().isUnauthorized());
		this.anonymous.delete(p).andExpect(status().isUnauthorized());
		withoutCsrf(MockMvcRequestBuilders.post(posts(this.course)), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(p), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(p), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(p + "/like"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(p + "/read"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(p + "/comments"), this.stu).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(c), this.stu).andExpect(status().isForbidden());

		// 작성자는 고치고 지운다. 다른 학생은 읽음·추천·댓글만 한다.
		put(this.classmate, p + "/like").andExpect(jsonPath("$.likeCount").value(1));
		// 추천 수가 바뀐 요청만 감사 기록을 남긴다. 같은 상태의 재전송은 남기지 않는다.
		put(this.classmate, p + "/like").andExpect(jsonPath("$.likeCount").value(1));
		assertThat(audits("board_post.like", id)).isEqualTo(1);
		this.classmate.delete(p + "/like").andExpect(jsonPath("$.likeCount").value(0));
		this.classmate.delete(p + "/like").andExpect(jsonPath("$.likeCount").value(0));
		assertThat(audits("board_post.unlike", id)).isEqualTo(1);
		this.classmate.post(p + "/read").andExpect(jsonPath("$.viewCount").value(1));
		put(this.student, p, edit).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("고친 글"));
		put(this.student, p, edit).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.delete(p).andExpect(status().isNoContent());
		this.student.delete(p).andExpect(status().isNotFound());
		this.classmate.get(p + "/comments").andExpect(status().isNotFound());
		assertThat(audits("board_post.delete", id)).isEqualTo(1);

		// 담당자는 남의 글을 지운다(관리 삭제).
		long other = created(this.classmate.postJson(posts(this.course), "{\"title\":\"부적절한 글\"}"), "postId");
		this.professor.delete(post(this.course, other)).andExpect(status().isNoContent());
		assertThat(audits("board_post.moderate", other)).isEqualTo(1);
		this.classmate.get(post(this.course, other)).andExpect(status().isNotFound());
	}

	@Test
	void cursorPagesStayStableWhenPostsArrive() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			ids.add(created(this.student.postJson(posts(this.course), "{\"title\":\"글 " + i + "\"}"), "postId"));
		}
		JsonNode first = ApiClient.json(get(this.classmate, posts(this.course), "size", "2")
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.items[0].postId").value(ids.get(4)))
			.andExpect(jsonPath("$.items[1].postId").value(ids.get(3))));
		created(this.classmate.postJson(posts(this.course), "{\"title\":\"새 글\"}"), "postId");
		JsonNode second = ApiClient.json(
				get(this.classmate, posts(this.course), "size", "2", "cursor", first.get("nextCursor").asText())
					.andExpect(jsonPath("$.items[0].postId").value(ids.get(2)))
					.andExpect(jsonPath("$.items[1].postId").value(ids.get(1))));
		get(this.classmate, posts(this.course), "size", "2", "cursor", second.get("nextCursor").asText())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].postId").value(ids.get(0)))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		get(this.classmate, posts(this.course), "q", "새").andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].mine").value(true));
		get(this.classmate, posts(this.course), "cursor", "0").andExpect(status().isBadRequest());
		get(this.classmate, posts(this.course), "size", "0").andExpect(status().isBadRequest());
	}

	@Test
	void retriedCommentWithTheSameKeyMakesOneComment() throws Exception {
		long id = created(this.student.postJson(posts(this.course), "{\"title\":\"글\"}"), "postId");
		String comments = post(this.course, id) + "/comments";
		long first = created(postWithKey(this.classmate, comments, "{\"body\":\"한 번\"}", "comment-1"), "commentId");
		postWithKey(this.classmate, comments, "{\"body\":\"한 번\"}", "comment-1")
			.andExpect(jsonPath("$.commentId").value(first));
		assertThat(count("SELECT comment_count FROM posts WHERE post_id = :id", id)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM comments WHERE post_id = :id", id)).isEqualTo(1);
	}

	@Test
	void concurrentCommentsOnNeighbouringPostsAreAllCounted() throws Exception {
		long first = created(this.student.postJson(posts(this.course), "{\"title\":\"첫 글\"}"), "postId");
		long second = created(this.student.postJson(posts(this.course), "{\"title\":\"옆 글\"}"), "postId");
		List<Callable<ResultActions>> requests = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			for (long id : List.of(first, second)) {
				String body = "{\"body\":\"댓글 " + i + "\"}";
				requests.add(() -> this.classmate.postJson(post(this.course, id) + "/comments", body));
				requests.add(() -> put(this.classmate, post(this.course, id) + "/like"));
			}
		}
		assertThat(concurrently(requests)).containsOnly(201, 200);
		for (long id : List.of(first, second)) {
			assertThat(count("SELECT comment_count FROM posts WHERE post_id = :id", id)).isEqualTo(3);
			assertThat(count("SELECT like_count FROM posts WHERE post_id = :id", id)).isEqualTo(1);
		}
	}

	static String posts(long courseId) {
		return "/api/courses/" + courseId + "/open-board/posts";
	}

	static String post(long courseId, long postId) {
		return posts(courseId) + "/" + postId;
	}

}
