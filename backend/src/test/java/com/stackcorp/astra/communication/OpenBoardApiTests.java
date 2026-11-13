package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
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

	@Test
	void onlyTheAuthorEditsACommentWithTheVersionTheyRead() throws Exception {
		long id = created(this.student.postJson(posts(this.course), "{\"title\":\"글\"}"), "postId");
		String comments = post(this.course, id) + "/comments";
		long comment = created(this.student.postJson(comments, "{\"body\":\"처음 댓글\"}")
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.canEdit").value(true)), "commentId");
		String c = comments + "/" + comment;
		String edit = "{\"body\":\"고친 댓글\",\"version\":1}";
		this.classmate.get(comments).andExpect(jsonPath("$.items[0].canEdit").value(false))
			.andExpect(jsonPath("$.items[0].edited").value(false))
			.andExpect(jsonPath("$.items[0].updatedAt").doesNotExist());

		// 담당자도 남의 댓글은 고치지 못한다. 과목 밖 사람과 다른 과목 경로도 404다.
		put(this.classmate, c, edit).andExpect(status().isNotFound());
		put(this.professor, c, edit).andExpect(status().isNotFound());
		put(this.outsider, c, edit).andExpect(status().isNotFound());
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.stu);
		put(this.student, post(otherCourse, id) + "/comments/" + comment, edit).andExpect(status().isNotFound());
		put(this.anonymous, c, edit).andExpect(status().isUnauthorized());
		withoutCsrf(MockMvcRequestBuilders.put(c), this.stu).andExpect(status().isForbidden());

		// 작성할 때와 같은 글 규칙이고 version이 필요하다.
		put(this.student, c, "{\"body\":\"" + "가".repeat(901) + "\",\"version\":1}").andExpect(status().isBadRequest());
		put(this.student, c, "{\"body\":\" \",\"version\":1}").andExpect(status().isBadRequest());
		put(this.student, c, "{\"body\":\"x\"}").andExpect(status().isBadRequest());
		put(this.student, c, edit).andExpect(status().isOk())
			.andExpect(jsonPath("$.body").value("고친 댓글"))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.edited").value(true));
		put(this.student, c, edit).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
		assertThat(audits("board_comment.update", comment)).isEqualTo(1);
		this.classmate.get(comments).andExpect(jsonPath("$.items[0].body").value("고친 댓글"))
			.andExpect(jsonPath("$.items[0].edited").value(true));

		// 지운 댓글과 폐강한 과목의 댓글은 고치지 못한다.
		long gone = created(this.student.postJson(comments, "{\"body\":\"지울 댓글\"}"), "commentId");
		this.student.delete(comments + "/" + gone).andExpect(status().isNoContent());
		put(this.student, comments + "/" + gone, edit).andExpect(status().isNotFound());
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :id").param("id", this.course).update();
		put(this.student, c, "{\"body\":\"폐강 뒤\",\"version\":2}").andExpect(status().isNotFound());
	}

	@Test
	void postEditKeepsAddsAndRemovesAttachmentsUnderTheVersionCheck() throws Exception {
		long first = upload(this.student, "a.txt", "첫 파일");
		long second = upload(this.student, "b.txt", "둘째 파일");
		JsonNode created = ApiClient.json(this.student.postJson(posts(this.course),
				"{\"title\":\"자료\",\"uploadIds\":[" + first + "," + second + "]}").andExpect(status().isCreated()));
		long id = created.get("postId").asLong();
		long kept = created.get("attachments").get(0).get("fileId").asLong();
		long removed = created.get("attachments").get(1).get("fileId").asLong();
		String p = post(this.course, id);

		long added = upload(this.student, "c.txt", "새 파일");
		JsonNode edited = ApiClient.json(put(this.student, p,
				"{\"title\":\"자료\",\"attachments\":{\"keep\":[" + kept + "],\"add\":[" + added + "]},\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.edited").value(true))
			.andExpect(jsonPath("$.attachments.length()").value(2))
			.andExpect(jsonPath("$.attachments[0].fileId").value(kept)));
		long addedFile = edited.get("attachments").get(1).get("fileId").asLong();
		this.classmate.get(QnaApiTests.file(this.course, kept)).andExpect(status().isOk());
		this.classmate.get(QnaApiTests.file(this.course, addedFile)).andExpect(status().isOk());
		this.classmate.get(QnaApiTests.file(this.course, removed)).andExpect(status().isNotFound());
		this.student.get(QnaApiTests.file(this.course, removed)).andExpect(status().isNotFound());

		// 읽은 version이 낡았으면 글도 첨부도 그대로다. 보낸 업로드는 첨부되지 않는다.
		long late = upload(this.student, "d.txt", "늦은 파일");
		put(this.student, p, "{\"title\":\"낡은 수정\",\"attachments\":{\"keep\":[],\"add\":[" + late + "]},\"version\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		assertThat(count("SELECT COUNT(*) FROM attachments WHERE upload_id = :id", late)).isZero();
		// 남의 업로드나 다른 글의 파일은 넣지 못한다. 실패하면 글도 바뀌지 않는다.
		long others = upload(this.classmate, "e.txt", "남의 파일");
		long otherPostFile = ApiClient.json(this.classmate.postJson(posts(this.course),
				"{\"title\":\"다른 글\",\"uploadIds\":[" + others + "]}").andExpect(status().isCreated()))
			.get("attachments").get(0).get("fileId").asLong();
		put(this.student, p, "{\"title\":\"남의 것\",\"attachments\":{\"keep\":[" + otherPostFile + "],\"add\":[]},\"version\":2}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		put(this.student, p, "{\"title\":\"남의 것\",\"attachments\":{\"keep\":[],\"add\":[" + late + "," + late + "]},\"version\":2}")
			.andExpect(status().isBadRequest());
		this.student.get(p).andExpect(jsonPath("$.title").value("자료")).andExpect(jsonPath("$.version").value(2));
		// attachments를 보내지 않으면 첨부는 그대로다. 수정에서 uploadIds는 받지 않는다.
		put(this.student, p, "{\"title\":\"제목만\",\"version\":2}").andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(2));
		put(this.student, p, "{\"title\":\"x\",\"uploadIds\":[" + late + "],\"version\":3}").andExpect(status().isBadRequest());
		// 모두 빼면 첨부가 없다.
		put(this.student, p, "{\"title\":\"빈 첨부\",\"attachments\":{\"keep\":[],\"add\":[]},\"version\":3}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attachments.length()").value(0));
		this.classmate.get(QnaApiTests.file(this.course, kept)).andExpect(status().isNotFound());
	}

	@Test
	void authorsCarryTheirIdAndAPhotoFlagThatMatchesTheAvatarEndpoint() throws Exception {
		this.data.photo(this.stu);
		long withPhoto = created(this.student.postJson(posts(this.course), "{\"title\":\"사진 있음\"}"), "postId");
		long withoutPhoto = created(this.professor.postJson(posts(this.course), "{\"title\":\"사진 없음\"}"), "postId");
		this.classmate.get(posts(this.course))
			.andExpect(jsonPath("$.items[0].postId").value(withoutPhoto))
			.andExpect(jsonPath("$.items[0].authorId").value(this.prof))
			.andExpect(jsonPath("$.items[0].authorHasPhoto").value(false))
			.andExpect(jsonPath("$.items[0].edited").value(false))
			.andExpect(jsonPath("$.items[1].postId").value(withPhoto))
			.andExpect(jsonPath("$.items[1].authorId").value(this.stu))
			.andExpect(jsonPath("$.items[1].authorName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.items[1].authorHasPhoto").value(true));
		this.student.get(post(this.course, withPhoto))
			.andExpect(jsonPath("$.authorId").value(this.stu))
			.andExpect(jsonPath("$.authorHasPhoto").value(true));
		String comments = post(this.course, withoutPhoto) + "/comments";
		created(this.student.postJson(comments, "{\"body\":\"사진 있는 댓글\"}"), "commentId");
		this.professor.get(comments)
			.andExpect(jsonPath("$.items[0].authorId").value(this.stu))
			.andExpect(jsonPath("$.items[0].authorHasPhoto").value(true));
		// 작성자 본인이 보는 목록과 댓글에서도 자기 사진 유무가 보인다.
		this.student.get(posts(this.course))
			.andExpect(jsonPath("$.items[1].authorId").value(this.stu))
			.andExpect(jsonPath("$.items[1].authorHasPhoto").value(true));
		this.student.get(comments)
			.andExpect(jsonPath("$.items[0].authorId").value(this.stu))
			.andExpect(jsonPath("$.items[0].authorHasPhoto").value(true));

		// 목록의 edited는 상세와 같은 뜻이다.
		put(this.student, post(this.course, withPhoto), "{\"title\":\"고친 글\",\"version\":1}").andExpect(status().isOk());
		this.classmate.get(posts(this.course)).andExpect(jsonPath("$.items[1].edited").value(true));

		// 과목을 떠난 작성자는 그대로 보이지만 사진은 함께하는 과목이 있어야 보인다. 아바타 경로가 404인 사람은 false다.
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", this.course)
			.param("u", this.stu)
			.update();
		this.classmate.get(avatar(this.stu)).andExpect(status().isNotFound());
		this.classmate.get(posts(this.course))
			.andExpect(jsonPath("$.items[1].authorId").value(this.stu))
			.andExpect(jsonPath("$.items[1].authorHasPhoto").value(false));
	}

	@Test
	void photoFlagsTakeTheSameNumberOfStatementsHoweverManyAuthorsAPageHas() throws Exception {
		created(this.student.postJson(posts(this.course), "{\"title\":\"하나\"}"), "postId");
		this.data.photo(this.stu);
		long few = statements();
		for (int i = 0; i < 6; i++) {
			long author = this.data.user("STUDENT");
			this.data.enroll(this.course, author);
			this.data.photo(author);
			created(new ApiClient(this.mvc, this.data.session(author)).postJson(posts(this.course),
					"{\"title\":\"글 " + i + "\"}"), "postId");
		}
		this.classmate.get(posts(this.course))
			.andExpect(jsonPath("$.items.length()").value(7))
			.andExpect(jsonPath("$.items[*].authorHasPhoto").value(everyItem(is(true))));
		assertThat(statements()).isEqualTo(few);
	}

	/** 열린게시판 목록 한 번 동안 DB가 받은 문장 수. 서버 전체 counter라 다른 작업이 끼면 늘어나기만 하므로 세 번 재어 가장 작은 값을 쓴다. */
	private long statements() throws Exception {
		long min = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = questions();
			this.classmate.get(posts(this.course)).andExpect(status().isOk());
			min = Math.min(min, questions() - before);
		}
		return min;
	}

	private long questions() {
		return this.jdbc.sql("SHOW GLOBAL STATUS LIKE 'Questions'").query((rs, n) -> rs.getLong(2)).single();
	}

	static String avatar(long userId) {
		return "/api/users/" + userId + "/avatar";
	}

	static String posts(long courseId) {
		return "/api/courses/" + courseId + "/open-board/posts";
	}

	static String post(long courseId, long postId) {
		return posts(courseId) + "/" + postId;
	}

}
