package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.assessment.Survey.Phase;
import com.stackcorp.astra.assessment.SurveyRecords.Answer;
import com.stackcorp.astra.assessment.SurveyRecords.Participation;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.SurveyActivities;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설문 등록·수정과 응답 완료(SC-05, SC-05-A, SC-06, SC-09, ADR-0022). 권한과 입력 형식은 부르는 쪽이 먼저 판정한다.
 * <p>
 * 완료는 참여 원장 한 행과 응답 세트 하나를 한 transaction에서 쓴다. 답 전체를 문항 정의로 검사한 뒤에만 쓰므로 일부만 답한
 * 세트가 완료로 남지 않는다. 같은 사람의 참여는 {@code (survey_id, user_id)} 유일 제약으로 하나뿐이다.
 * <ul>
 * <li>익명 설문: 공용 멱등 원장을 쓰지 않는다. 원장은 principal 단위로 요청 지문과 receipt를 저장하므로 답에서 나온 값이
 * 사용자와 이어진다. 대신 참여 원장에 {@code (설문, 사용자, key)}와 최소 결과(상태, 차수)만 남긴다. 같은 key는 본문이 달라도 처음
 * 결과를 돌려주고 답을 바꾸지 않는다. 다른 key는 409다. 감사 기록에는 requestId를 남기지 않는다.</li>
 * <li>실명 설문: 공용 멱등 원장(SC-05)을 쓴다. 같은 key에 다른 답이면 409 {@code idempotency_conflict}다.</li>
 * </ul>
 * 완료 transaction은 READ COMMITTED로 실행하고 설문 행을 먼저 공유 잠금으로 잡는다. 문항 교체와 응답 방식 변경은 같은 행을
 * 배타 잠금으로 잡으므로 완료와 겹치지 않는다. deadlock이나 잠금 대기 초과는 정해진 횟수만 다시 시도한다.
 */
@Service
class SurveyService {

	static final String COMPLETE = "survey.complete";

	private static final int ATTEMPTS = 3;

	private final SurveyAccess access;

	private final SurveyRecords records;

	private final SurveyActivities activities;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final TransactionTemplate readCommitted;

	SurveyService(SurveyAccess access, SurveyRecords records, SurveyActivities activities, Idempotency idempotency,
			AuditLog audit, PlatformTransactionManager transactionManager) {
		this.access = access;
		this.records = records;
		this.activities = activities;
		this.idempotency = idempotency;
		this.audit = audit;
		this.readCommitted = new TransactionTemplate(transactionManager);
		this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	/**
	 * 활동, 설문 설정, 문항을 한 transaction으로 만든다. 멱등 원장의 transaction 안에서 부르면 거기에 들어간다.
	 *
	 * @return 설문 ID
	 */
	@Transactional
	long create(Actor actor, long courseId, SurveyInput input) {
		long activityId = this.activities.create(courseId, actor.userId(), input.activity());
		long surveyId = this.records.insert(activityId, actor.userId(), input);
		this.records.insertQuestions(surveyId, input.questions());
		this.audit.success(actor.userId(), actor.sessionId(), "survey.create", "survey", surveyId, courseId);
		return surveyId;
	}

	/**
	 * 읽은 수정 번호가 지금 값과 같을 때만 바꾼다. 완료한 사람이 있으면 문항과 응답 방식은 바꾸지 않는다. 이미 받은 답의 뜻이
	 * 바뀌거나, 실명으로 받은 응답이 익명 결과에 섞이기 때문이다. 제목, 안내, 기간, 공개, 결과 공개는 언제나 바꾼다.
	 */
	void update(Actor actor, long courseId, Survey current, long revision, SurveyInput input) {
		String mode = input.anonymous() == null ? current.settings().responseMode()
				: input.anonymous() ? Survey.ANONYMOUS : Survey.NAMED;
		this.readCommitted.executeWithoutResult((tx) -> {
			if (!this.records.update(current.surveyId(), revision, input, mode)) {
				throw new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
			}
			boolean redefines = input.questions() != null || !mode.equals(current.settings().responseMode());
			if (redefines && this.records.hasParticipations(current.surveyId())) {
				throw new ApiException(HttpStatus.CONFLICT, "survey_has_responses", "응답이 있는 설문의 문항과 응답 방식은 바꿀 수 없습니다.");
			}
			if (!this.activities.update(courseId, current.activity().activityId(), input.activity())) {
				throw ApiException.notFound();
			}
			if (input.questions() != null) {
				this.records.replaceQuestions(current.surveyId(), input.questions());
			}
			this.audit.success(actor.userId(), actor.sessionId(), "survey.update", "survey", current.surveyId(), courseId);
		});
	}

	/**
	 * 설문을 지운다(ADR-0011, ADR-0037). 활동에 삭제 표시만 남기고 설정, 문항, 참여 원장, 응답 저장부는 그대로 둔다. 응답 쪽 행은 읽지도
	 * 바꾸지도 않으므로 익명 설문의 비식별(ADR-0022)은 그대로다. 수정 번호를 먼저 올려 설문 행을 배타 잠금하므로, 진행 중인 완료(공유
	 * 잠금)는 삭제 전에 commit하거나 삭제 뒤에 설문을 다시 읽어 404가 된다. 설문은 알림을 만들지 않으므로 거둘 알림이 없다.
	 */
	void delete(Actor actor, long courseId, Survey current, long revision) {
		this.readCommitted.executeWithoutResult((tx) -> {
			if (!this.records.bumpRevision(current.surveyId(), revision)) {
				throw new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
			}
			if (!this.activities.delete(courseId, current.activity().activityId(), actor.userId())) {
				throw ApiException.notFound();
			}
			this.audit.success(actor.userId(), actor.sessionId(), "survey.delete", "survey", current.surveyId(), courseId);
		});
	}

	/**
	 * 응답 완료. 응답은 최소 결과만 담은 receipt다.
	 *
	 * @param seen 권한 판정에 쓴 설문. transaction 안에서 잠근 뒤 다시 읽는다
	 * @param answers {@link SurveyAnswers#normalized}를 거친 답
	 */
	Completion complete(Actor actor, Survey seen, String key, List<Answer> answers) {
		return seen.anonymous() ? completeAnonymous(actor, seen, key, answers) : completeNamed(actor, seen, key, answers);
	}

	// SC-05-A: 참여 원장의 key로 재응답을 판정한다. 기간과 답을 다시 검사하기 전에 처음 결과를 돌려준다.
	private Completion completeAnonymous(Actor actor, Survey seen, String key, List<Answer> answers) {
		Optional<Participation> existing = this.records.participation(seen.surveyId(), actor.userId());
		if (existing.isPresent()) {
			return replay(seen.surveyId(), existing.get(), key);
		}
		for (int attempt = 1;; attempt++) {
			try {
				return this.readCommitted.execute((tx) -> write(actor, seen, key, answers));
			}
			catch (AlreadyCompleted | DuplicateKeyException ex) {
				// 다른 요청이 먼저 완료했다. 그 요청이 commit한 참여를 기준으로 답한다.
				return this.records.participation(seen.surveyId(), actor.userId())
					.map((p) -> replay(seen.surveyId(), p, key))
					.orElseThrow(() -> ex);
			}
			catch (PessimisticLockingFailureException ex) {
				if (attempt == ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	private Completion completeNamed(Actor actor, Survey seen, String key, List<Answer> answers) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.idempotency.execute(actor.userId(), COMPLETE, key, List.of(seen.surveyId(), answers),
						Completion.class, () -> {
							try {
								return write(actor, seen, null, answers);
							}
							catch (AlreadyCompleted | DuplicateKeyException ex) {
								throw alreadyCompleted();
							}
						});
			}
			catch (PessimisticLockingFailureException ex) {
				if (attempt == ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	/**
	 * 완료 transaction 본문. 순서: 설문 행 공유 잠금 → DB 시각 → 설문 다시 읽기 → 응답 방식·공개·기간 판정 → 참여 확인 → 문항 정의로
	 * 답 검사 → 참여 행 → 응답 세트 → 감사 기록.
	 *
	 * @param completionKey 익명 설문이면 참여 원장에 남길 key. 실명 설문이면 null
	 */
	private Completion write(Actor actor, Survey seen, String completionKey, List<Answer> answers) {
		long surveyId = seen.surveyId();
		this.records.lockShared(surveyId);
		LocalDateTime now = this.access.now();
		Survey survey = reload(seen).orElseThrow(ApiException::notFound);
		if (survey.anonymous() != seen.anonymous()) {
			throw new ApiException(HttpStatus.CONFLICT, "survey_changed", "설문 설정이 바뀌었습니다. 다시 불러온 뒤 응답해 주세요.");
		}
		if (!survey.openToRespondents(now)) {
			throw ApiException.notFound();
		}
		if (survey.phase(now) != Phase.OPEN) {
			throw new ApiException(HttpStatus.CONFLICT, "deadline_passed", "응답 기간이 아닙니다.");
		}
		if (this.records.participation(surveyId, actor.userId()).isPresent()) {
			throw new AlreadyCompleted();
		}
		List<Answer> valid = SurveyAnswers.validated(this.records.questions(surveyId), answers);
		boolean anonymous = survey.anonymous();
		// 익명 설문은 참여, 응답, 감사 기록 어느 쪽에도 정밀 시각을 남기지 않는다(SC-05-A). 응답 세트에는 사용자도 key도 없고, 응답 쪽
		// 행 ID는 임의 값이라 참여 행이나 감사 기록의 입력 순서와 맞출 수 없다(ADR-0022).
		LocalDateTime stamp = anonymous ? now.toLocalDate().atStartOfDay() : now;
		this.records.insertParticipation(surveyId, actor.userId(), anonymous ? completionKey : null, stamp);
		this.records.insertResponse(surveyId, anonymous ? null : actor.userId(), stamp, valid);
		if (anonymous) {
			this.audit.successWithoutRequestId(actor.userId(), actor.sessionId(), COMPLETE, "survey", surveyId,
					survey.courseId());
		}
		else {
			this.audit.success(actor.userId(), actor.sessionId(), COMPLETE, "survey", surveyId, survey.courseId());
		}
		return new Completion(surveyId, "completed", 1);
	}

	private Optional<Survey> reload(Survey seen) {
		if (seen.community()) {
			return this.records.find(seen.surveyId())
				.filter((settings) -> settings.activityId() == null)
				.map((settings) -> new Survey(settings, null));
		}
		return this.access.find(seen.courseId(), seen.surveyId());
	}

	private static Completion replay(long surveyId, Participation participation, String key) {
		if (!key.equals(participation.completionKey())) {
			throw alreadyCompleted();
		}
		return new Completion(surveyId, participation.state(), participation.revision());
	}

	private static ApiException alreadyCompleted() {
		return new ApiException(HttpStatus.CONFLICT, "already_completed", "이미 응답을 완료한 설문입니다.");
	}

	/** 이 transaction이 참여를 확인하기 전에 다른 요청이 완료했다. */
	private static final class AlreadyCompleted extends RuntimeException {

		AlreadyCompleted() {
			super(null, null, false, false);
		}

	}

	/**
	 * 완료 receipt. 참여의 최소 결과만 담는다. 응답 세트 ID, 답, 접수 시각은 넣지 않는다(SC-05-A).
	 *
	 * @param state completed
	 * @param revision 참여 차수. 재응답이 없으므로 1이다
	 */
	record Completion(long surveyId, String state, int revision) {
	}

}
