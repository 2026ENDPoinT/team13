package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.DirectNotification;
import com.stackcorp.astra.communication.BoardAccess.Viewer;
import com.stackcorp.astra.communication.BoardPostRepository.Locked;
import com.stackcorp.astra.communication.BoardPostRepository.NewText;
import com.stackcorp.astra.communication.BoardPostRepository.PostRow;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질의응답 답글(EP-0063~0066). 질문을 볼 수 있는 구성원(담당자와 수강생)이 단다. 비공개 질문이면 질문 작성자와 담당자만 보므로 그들만 단다.
 * 답글은 작성자만 고친다. 작성자와 담당자가 지운다. 학생이 담당자의 답글을 지우지 못한다.
 * 답글을 만들고 지울 때는 질문 행을 먼저 잠그고 답글 수를 같은 transaction에서 바꾼다. 질문 수정·삭제의 "답글 전" 조건이 이 수를 본다.
 */
@Service
class QnaAnswerService {

	static final int MAX_BODY = 20_000;

	static final int MAX_PAGE_SIZE = 50;

	private static final String ANSWER_PREFIX = "re : ";

	private final BoardPostRepository posts;

	private final BoardAccess access;

	private final BoardFiles files;

	private final UserNames userNames;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final Notifications notifications;

	QnaAnswerService(BoardPostRepository posts, BoardAccess access, BoardFiles files, UserNames userNames,
			RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit, Notifications notifications) {
		this.posts = posts;
		this.access = access;
		this.files = files;
		this.userNames = userNames;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.notifications = notifications;
	}

	/** 먼저 쓴 답글부터. 다음 묶음은 nextCursor를 cursor로 보내 받는다(EP-0065). */
	@Transactional(readOnly = true)
	AnswerPage list(long userId, long courseId, long questionId, Long cursor, int size) {
		Viewer viewer = this.access.viewer(userId, courseId);
		if (size < 1 || size > MAX_PAGE_SIZE || (cursor != null && cursor < 1)) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하, cursor는 1 이상이어야 합니다.");
		}
		question(viewer, questionId);
		List<PostRow> rows = this.posts.answers(questionId, userId, cursor, size + 1);
		Long next = rows.size() > size ? rows.get(size - 1).postId() : null;
		return new AnswerPage(views(viewer, rows.subList(0, Math.min(size, rows.size()))), next);
	}

	/** 답글을 달고 첨부를 확정한다. 같은 Idempotency-Key로 다시 보내면 처음 만든 답글을 돌려준다. */
	AnswerView create(Actor actor, long courseId, long questionId, AnswerRequest body, String idempotencyKey) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		question(viewer, questionId);
		if (body.version() != null || body.attachments() != null) {
			throw ApiException.invalid("새 답글에는 version이나 attachments를 줄 수 없습니다. 첨부는 uploadIds로 보냅니다.");
		}
		NewAnswer answer = new NewAnswer(questionId, body(body), anonymous(viewer, body),
				BoardFiles.validated(body.uploadIds()));
		this.rateLimiter.check("qna-answer-create", actor.userId(), 30, Duration.ofMinutes(10));
		return this.idempotency.execute(actor.userId(), "qna_answer.create", idempotencyKey, List.of(courseId, answer),
				AnswerView.class, () -> {
					// 질문 행을 먼저 잠근다. 질문 수정·삭제와 답글 작성이 이 행에서 줄을 선다.
					Locked question = this.posts.lockVisible(viewer, BoardKind.QNA, questionId)
						.orElseThrow(ApiException::notFound);
					String title = ANSWER_PREFIX + question.title();
					Long group = this.files.attach(actor.userId(), courseId, answer.uploadIds(), title);
					this.posts.countAnswer(questionId, 1);
					long answerId = this.posts.insert(question.boardId(), actor.userId(), "ANSWER", questionId,
							new NewText(title, answer.body(), answer.anonymous(), false), group);
					this.audit.success(actor.userId(), actor.sessionId(), "qna_answer.create", "post", answerId,
							courseId);
					// 질문 작성자에게 알린다. 자기 질문에 단 답글은 알리지 않는다. 질문마다 알림은 하나다(ADR-0027).
					if (question.authorId() != actor.userId()) {
						this.notifications.send(new DirectNotification(NotificationKind.QUESTION_ANSWERED,
								List.of(question.authorId()), courseId, questionId, question.title()));
					}
					return view(viewer, this.posts.answer(questionId, answerId, actor.userId()).orElseThrow());
				});
	}

	/**
	 * 작성자의 수정. 본문의 version이 다르면 409 version_conflict. attachments가 있으면 첨부를 그 최종 집합으로 바꾸고 없으면 그대로
	 * 둔다. 질문 행을 먼저 잠그므로 답글 삭제·작성과 차례를 지킨다.
	 */
	@Transactional
	AnswerView update(Actor actor, long courseId, long questionId, long answerId, AnswerRequest body) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		question(viewer, questionId);
		PostRow row = this.posts.answer(questionId, answerId, actor.userId()).orElseThrow(ApiException::notFound);
		if (row.authorId() != actor.userId()) {
			throw ApiException.notFound();
		}
		if (body.version() == null || body.version() < 1) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		if (body.uploadIds() != null && !body.uploadIds().isEmpty()) {
			throw ApiException.invalid("수정할 때는 uploadIds 대신 attachments(keep, add)로 첨부를 보냅니다.");
		}
		String text = body(body);
		BoardFiles.Change attachments = BoardFiles.validated(body.attachments(), BoardFiles.MAX_UPLOADS);
		this.rateLimiter.check("qna-answer-update", actor.userId(), 60, Duration.ofMinutes(10));
		Locked question = this.posts.lockVisible(viewer, BoardKind.QNA, questionId).orElseThrow(ApiException::notFound);
		if (this.posts.updateAnswer(questionId, answerId, actor.userId(), body.version(), text,
				anonymous(viewer, body)) == 0) {
			this.posts.current(answerId).orElseThrow(ApiException::notFound);
			throw BoardPostService.versionConflict();
		}
		if (attachments != null) {
			Long group = this.files.change(BoardFiles.PURPOSE, actor.userId(), courseId,
					this.posts.attachmentGroup(answerId), attachments, ANSWER_PREFIX + question.title());
			this.posts.setAttachmentGroup(answerId, group);
		}
		this.audit.success(actor.userId(), actor.sessionId(), "qna_answer.update", "post", answerId, courseId);
		return view(viewer, this.posts.answer(questionId, answerId, actor.userId()).orElseThrow());
	}

	/** 작성자 또는 담당자의 소프트 삭제. 담당자가 남의 답글을 지우면 관리 삭제로 감사 기록을 남긴다. */
	@Transactional
	void delete(Actor actor, long courseId, long questionId, long answerId) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		this.posts.lockVisible(viewer, BoardKind.QNA, questionId).orElseThrow(ApiException::notFound);
		PostRow row = this.posts.answer(questionId, answerId, actor.userId()).orElseThrow(ApiException::notFound);
		boolean own = row.authorId() == actor.userId();
		if (!own && !viewer.manager()) {
			throw ApiException.notFound();
		}
		if (this.posts.deleteAnswer(questionId, answerId) == 0) {
			throw ApiException.notFound();
		}
		this.posts.countAnswer(questionId, -1);
		// 남은 답글이 없으면 "답변이 등록되었습니다" 알림도 거둔다.
		if (this.posts.current(questionId).map((state) -> state.answerCount() == 0).orElse(true)) {
			this.notifications.withdraw(NotificationKind.QUESTION_ANSWERED, questionId);
		}
		this.audit.success(actor.userId(), actor.sessionId(), own ? "qna_answer.delete" : "qna_answer.moderate", "post",
				answerId, courseId);
	}

	private void question(Viewer viewer, long questionId) {
		this.posts.find(viewer, BoardKind.QNA, questionId).orElseThrow(ApiException::notFound);
	}

	private static String body(AnswerRequest body) {
		return PlainText.requiredBody(body.body(), MAX_BODY);
	}

	/** 담당자는 익명으로 답하지 않는다. 답글의 instructor 표시가 익명성을 깨기 때문이다. */
	private static boolean anonymous(Viewer viewer, AnswerRequest body) {
		boolean anonymous = Boolean.TRUE.equals(body.anonymous());
		if (anonymous && viewer.manager()) {
			throw ApiException.invalid("담당자는 익명으로 답할 수 없습니다.");
		}
		return anonymous;
	}

	private List<AnswerView> views(Viewer viewer, List<PostRow> rows) {
		List<Long> authors = rows.stream().map(PostRow::authorId).distinct().toList();
		Map<Long, Boolean> instructors = new HashMap<>();
		for (long author : authors) {
			instructors.put(author, this.access.instructor(author, viewer.courseId()));
		}
		Map<Long, String> names = this.userNames.namesOf(rows.stream()
			.filter((r) -> viewer.seesAuthor(r.authorId(), r.anonymous()))
			.map(PostRow::authorId)
			.distinct()
			.toList());
		Map<Long, List<AttachedFile>> attached = this.files
			.list(rows.stream().map(PostRow::attachmentGroupId).filter(Objects::nonNull).toList());
		return rows.stream()
			.map((row) -> AnswerView.of(row, viewer,
					viewer.seesAuthor(row.authorId(), row.anonymous()) ? names.get(row.authorId()) : null,
					instructors.get(row.authorId()),
					row.attachmentGroupId() == null ? List.of() : attached.getOrDefault(row.attachmentGroupId(), List.of())))
			.toList();
	}

	private AnswerView view(Viewer viewer, PostRow row) {
		return views(viewer, List.of(row)).getFirst();
	}

	/**
	 * @param anonymous 학생만. 다른 학생에게 작성자 이름을 숨긴다. 담당자에게는 보인다
	 * @param uploadIds 새 답글에만. 본인이 용도 post_attachment로 올린 업로드
	 * @param attachments 수정할 때만. 첨부의 최종 집합. 없으면 첨부를 바꾸지 않는다
	 * @param version 수정할 때만
	 */
	record AnswerRequest(String body, Boolean anonymous, List<Long> uploadIds, BoardFiles.AttachmentSet attachments,
			Integer version) {
	}

	/** 멱등 지문을 만드는 검사된 입력. */
	record NewAnswer(long questionId, String body, boolean anonymous, List<Long> uploadIds) {
	}

	/**
	 * @param authorName 볼 수 없는 익명 작성자면 빠진다
	 * @param instructor 그 과목 담당자가 쓴 답글인지
	 * @param edited 작성 뒤 고친 적이 있으면 true(version이 1보다 크다)
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AnswerView(long answerId, String body, String authorName, boolean mine, boolean anonymous,
			boolean instructor, List<AttachedFile> attachments, int version, boolean edited, LocalDateTime createdAt,
			LocalDateTime updatedAt, boolean canEdit, boolean canDelete) {

		static AnswerView of(PostRow row, Viewer viewer, String authorName, boolean instructor,
				List<AttachedFile> files) {
			boolean mine = row.authorId() == viewer.userId();
			return new AnswerView(row.postId(), row.body(), authorName, mine, row.anonymous(), instructor, files,
					row.version(), row.version() > 1, row.createdAt(), row.updatedAt(), mine,
					mine || viewer.manager());
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record AnswerPage(List<AnswerView> items, Long nextCursor) {
	}

}
