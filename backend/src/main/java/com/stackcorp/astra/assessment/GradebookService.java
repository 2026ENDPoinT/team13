package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.assessment.GradebookRecords.Score;
import com.stackcorp.astra.assessment.GradebookRecords.Settings;
import com.stackcorp.astra.assessment.ScoreRowsRejected.RowError;
import com.stackcorp.astra.assessment.SubmissionRecords.Submission;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 성적부(ADR-0023). 권한은 과목 관계로 정한다. 담당자는 그 과목의 성적부 전체를 보고 직접 입력 항목을 관리한다. 학생은 자기 성적만 보고,
 * 공개하지 않은 항목의 점수, 의견, 그 점수가 들어간 합계는 응답에 속성 자체가 없다(SEC-14). 권한이 없거나 경로의 과목과 항목이 맞지 않으면
 * 404다.
 * <p>
 * 항목 소유: 과제 항목과 퀴즈 항목(활동이 있는 항목)의 이름, 배점, 공개, 점수는 그 활동의 화면이 정한다. 성적부에서는 비율만 바꾸고, 그
 * 밖의 변경은 409 {@code managed_by_assignment} 또는 {@code managed_by_quiz}다. 직접 입력 항목은 성적부가 모두 정한다. 학습활동은
 * {@link AssignmentAccess#all}(learning.api.ReportActivities)과 {@link QuizAccess#all}(learning.api.QuizActivities) 두 곳으로만
 * 읽고, 거기에 없는 활동(지운 활동 포함)의 항목은 없는 것으로 본다. 퀴즈 항목의 학생 공개는 {@link Quiz#release}가 정한다.
 * <p>
 * 판정 시각은 요청마다 DB 시계 한 번({@link QuizAccess#now})이다. 쿼리 수는 항목 수와 학생 수에 관계없다.
 */
@Service
class GradebookService {

	static final int MAX_NAME = 200;

	static final BigDecimal MAX_SCORE = new BigDecimal("1000");

	static final BigDecimal MAX_WEIGHT = new BigDecimal("100");

	static final int MAX_FEEDBACK = 1_000;

	/** 한 번에 저장하는 점수 행의 상한. 성적부 한 쪽(학생 100명)의 두 배다. */
	static final int MAX_ROWS = 200;

	static final int MAX_PAGE_SIZE = 100;

	/** 과목당 직접 입력 항목의 상한. 성적부 응답 크기의 상한이다. */
	static final int MAX_MANUAL_ITEMS = 100;

	static final int MAX_QUERY = 100;

	private static final Set<String> TYPES = Set.of(GradeItem.ASSIGNMENT, GradeItem.QUIZ, GradeItem.MANUAL);

	private final AssignmentAccess access;

	private final QuizAccess quizzes;

	private final GradebookRecords records;

	private final SubmissionRecords submissions;

	private final CourseRosters rosters;

	private final UserNames userNames;

	private final GradeWrites writes;

	private final AuditLog audit;

	GradebookService(AssignmentAccess access, QuizAccess quizzes, GradebookRecords records, SubmissionRecords submissions,
			CourseRosters rosters, UserNames userNames, GradeWrites writes, AuditLog audit) {
		this.access = access;
		this.quizzes = quizzes;
		this.records = records;
		this.submissions = submissions;
		this.rosters = rosters;
		this.userNames = userNames;
		this.writes = writes;
		this.audit = audit;
	}

	void requireInstructor(long userId, long courseId) {
		if (this.access.role(userId, courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
	}

	/** 담당자의 성적부. 학생은 수강생 명단의 사용자 ID 순서로 쪽을 나눈다. 합계는 공개 여부와 관계없이 점수를 받은 항목 전부다. */
	Gradebook gradebook(long userId, long courseId, int page, int size) {
		requireInstructor(userId, courseId);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		LocalDateTime now = this.quizzes.now();
		Live live = live(courseId);
		List<Long> itemIds = live.items().stream().map(GradeItem::gradeItemId).toList();
		List<Long> roster = this.rosters.students(courseId);
		int from = (int) Math.min((long) (page - 1) * size, roster.size());
		List<Long> pageIds = roster.subList(from, Math.min(from + size, roster.size()));
		Map<Long, Integer> graded = this.records.gradedCounts(itemIds, roster);
		Map<Long, Map<Long, Score>> scores = this.records.scores(itemIds, pageIds);
		Map<Long, String> names = this.userNames.namesOf(pageIds);
		List<StudentRow> students = pageIds.stream().map((studentId) -> {
			Map<Long, Score> mine = scores.getOrDefault(studentId, Map.of());
			List<ScoreCell> cells = live.items()
				.stream()
				.filter((item) -> mine.containsKey(item.gradeItemId()))
				.map((item) -> ScoreCell.of(mine.get(item.gradeItemId())))
				.toList();
			GradeTotal total = GradeTotal.of(live.items().stream().map((item) -> part(item, mine)).toList());
			return new StudentRow(studentId, names.get(studentId), cells, total);
		}).toList();
		List<ItemView> items = live.items()
			.stream()
			.map((item) -> ItemView.of(item, live, graded.getOrDefault(item.gradeItemId(), 0), now))
			.toList();
		Settings settings = this.records.settings(courseId);
		return new Gradebook(courseId, now, new SettingsView(settings.activitiesVisible(), settings.revision()), items,
				students, roster.size(), page, size);
	}

	/**
	 * 담당자가 보는 성적부의 항목 하나. 항목은 성적부와 같은 모양이고, 학생 쪽은 성적부와 같이 수강생 명단의 사용자 ID 순서로 나눈다. 학생마다
	 * 그 항목의 점수, 의견, 수정 번호만 있다. 지운 활동의 항목과 다른 과목의 항목은 404다. 쿼리 수는 학생 수에 관계없다.
	 */
	ItemSheet itemSheet(long userId, long courseId, long itemId, int page, int size) {
		requireInstructor(userId, courseId);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		LocalDateTime now = this.quizzes.now();
		GradeItem item = this.records.find(courseId, itemId).orElseThrow(ApiException::notFound);
		Live live = item.manual() ? new Live(List.of(item), Map.of(), Map.of()) : live(courseId);
		if (!live.contains(item)) {
			throw ApiException.notFound();
		}
		List<Long> roster = this.rosters.students(courseId);
		int from = (int) Math.min((long) (page - 1) * size, roster.size());
		List<Long> pageIds = roster.subList(from, Math.min(from + size, roster.size()));
		int graded = this.records.gradedCounts(List.of(itemId), roster).getOrDefault(itemId, 0);
		Map<Long, Score> scores = this.records.scoresOf(itemId, pageIds);
		Map<Long, String> names = this.userNames.namesOf(pageIds);
		List<ItemStudent> students = pageIds.stream()
			.map((studentId) -> ItemStudent.of(studentId, names.get(studentId), scores.get(studentId)))
			.toList();
		return new ItemSheet(courseId, now, ItemView.of(item, live, graded, now), students, roster.size(), page, size);
	}

	/**
	 * 학생 본인의 성적(EP-0035, EP-0036). 과목이 평가 활동을 비공개로 두면 목록과 합계가 없다. 과제·퀴즈 항목은 학생이 그 활동을 볼 수 있을
	 * 때만 넣는다. 공개하지 않은 항목은 이름과 배점만 있고, 그 점수는 읽지도 않는다. 합계는 공개한 항목만 더한다. 거르기(type, q)는 목록에만
	 * 적용하고 합계는 거르지 않는다.
	 */
	MyGrades mine(long userId, long courseId, String type, String query) {
		if (this.access.role(userId, courseId) != CourseRole.STUDENT) {
			throw ApiException.notFound();
		}
		String typeFilter = validatedType(type);
		String q = validatedQuery(query);
		LocalDateTime now = this.quizzes.now();
		if (!this.records.settings(courseId).activitiesVisible()) {
			return new MyGrades(courseId, now, false, null, null);
		}
		Live live = live(courseId);
		List<GradeItem> visible = live.items().stream().filter((item) -> live.openToStudents(item, now)).toList();
		List<GradeItem> released = visible.stream().filter((item) -> live.released(item, now)).toList();
		// 공개하지 않은 항목의 점수는 읽지 않는다. 응답에 들어갈 길을 만들지 않는다(SEC-14).
		Map<Long, Score> mine = this.records.scores(released.stream().map(GradeItem::gradeItemId).toList(), List.of(userId))
			.getOrDefault(userId, Map.of());
		Map<Long, Submission> submitted = this.submissions.ofUser(userId,
				visible.stream()
					.map(live::assignmentOf)
					.filter((assignment) -> assignment != null)
					.map(Assignment::assignmentId)
					.toList());
		List<MyGradeItem> rows = visible.stream()
			.filter((item) -> typeFilter == null || typeFilter.equals(item.type()))
			.filter((item) -> q == null || item.name().toLowerCase(Locale.ROOT).contains(q))
			.map((item) -> MyGradeItem.of(item, live, live.released(item, now), mine.get(item.gradeItemId()), submitted))
			.toList();
		GradeTotal total = GradeTotal.of(released.stream().map((item) -> part(item, mine)).toList());
		return new MyGrades(courseId, now, true, rows, total);
	}

	/** 성적 설정을 바꾼다. 행이 없으면 읽은 수정 번호 0으로 처음 만든다. */
	SettingsView updateSettings(Actor actor, long courseId, boolean activitiesVisible, long revision) {
		requireInstructor(actor.userId(), courseId);
		this.writes.run(() -> {
			if (revision == 0) {
				try {
					this.records.insertSettings(courseId, activitiesVisible);
				}
				catch (DuplicateKeyException ex) {
					throw editConflict();
				}
			}
			else if (!this.records.updateSettings(courseId, revision, activitiesVisible)) {
				throw editConflict();
			}
			this.audit.success(actor.userId(), actor.sessionId(), "grade_settings.update", "course", courseId, courseId);
			return courseId;
		});
		Settings settings = this.records.settings(courseId);
		return new SettingsView(settings.activitiesVisible(), settings.revision());
	}

	/** 직접 입력 항목을 만든다. 처음에는 공개하지 않는다. 부르는 쪽이 담당자 권한을 먼저 판정하고 transaction 안에서 부른다. */
	ItemView create(Actor actor, long courseId, ItemInput input) {
		if (this.records.countManual(courseId) >= MAX_MANUAL_ITEMS) {
			throw new ApiException(HttpStatus.CONFLICT, "too_many_items", "직접 입력 항목은 과목당 " + MAX_MANUAL_ITEMS + "개까지입니다.");
		}
		long itemId = this.records.insertManual(courseId, actor.userId(), input.name(), input.maxScore(), input.weight());
		this.audit.success(actor.userId(), actor.sessionId(), "grade_item.create", "grade_item", itemId, courseId);
		return view(courseId, itemId);
	}

	/**
	 * 항목을 고친다. 직접 입력 항목은 이름, 배점, 비율을 바꾼다. 과제·퀴즈 항목은 비율만 바꾸고, 이름이나 배점을 보내면 409
	 * {@code managed_by_assignment} 또는 {@code managed_by_quiz}다. 배점을 이미 준 점수보다 낮추면 409 {@code score_exceeds_max}다.
	 */
	ItemView update(Actor actor, long courseId, long itemId, ItemUpdate input) {
		GradeItem current = managedItem(actor.userId(), courseId, itemId);
		if (!current.manual() && (input.name() != null || input.maxScore() != null)) {
			throw managedElsewhere(current);
		}
		if (current.manual() && (input.name() == null || input.maxScore() == null)) {
			throw ApiException.invalid("직접 입력 항목은 name과 maxScore를 함께 보내야 합니다.");
		}
		this.writes.run(() -> {
			GradeItem locked = lockAt(itemId, input.revision());
			if (locked.manual()) {
				if (this.records.hasScoreAbove(itemId, input.maxScore())) {
					throw new ApiException(HttpStatus.CONFLICT, "score_exceeds_max", "이미 준 점수보다 배점을 낮출 수 없습니다.");
				}
				this.records.updateManual(itemId, input.name(), input.maxScore(), input.weight());
			}
			else {
				this.records.updateWeight(itemId, input.weight());
			}
			this.audit.success(actor.userId(), actor.sessionId(), "grade_item.update", "grade_item", itemId, courseId);
			return itemId;
		});
		return view(courseId, itemId);
	}

	/**
	 * 직접 입력 항목의 점수를 학생에게 공개하거나 거둔다. 과제 항목은 과제의 공개 명령({@link AssignmentService#release}), 퀴즈 항목은
	 * 퀴즈 수정(resultReleaseAt)으로만 바꾼다.
	 */
	ItemView release(Actor actor, long courseId, long itemId, boolean released, long revision) {
		requireManual(managedItem(actor.userId(), courseId, itemId));
		this.writes.run(() -> {
			lockAt(itemId, revision);
			this.records.setReleased(itemId, released);
			this.audit.success(actor.userId(), actor.sessionId(), released ? "grade_item.release" : "grade_item.withhold",
					"grade_item", itemId, courseId);
			return itemId;
		});
		return view(courseId, itemId);
	}

	/** 직접 입력 항목을 지운다. 점수나 의견이 남은 학생이 있으면 409 {@code item_has_scores}다. 먼저 점수를 비워야 한다. */
	void delete(Actor actor, long courseId, long itemId, long revision) {
		requireManual(managedItem(actor.userId(), courseId, itemId));
		this.writes.run(() -> {
			lockAt(itemId, revision);
			if (this.records.hasRecordedScores(itemId)) {
				throw new ApiException(HttpStatus.CONFLICT, "item_has_scores", "점수나 의견이 남아 있는 항목은 지울 수 없습니다.");
			}
			this.records.deleteManual(itemId);
			this.audit.success(actor.userId(), actor.sessionId(), "grade_item.delete", "grade_item", itemId, courseId);
			return itemId;
		});
	}

	/**
	 * 직접 입력 항목의 점수를 여러 학생에게 한 번에 쓴다. 행마다 학생, 점수(0~배점, 소수 둘째 자리, null이면 비움), 의견, 읽은 수정 번호를
	 * 검사하고, 한 행이라도 틀리거나 수정 번호가 다르면 아무것도 저장하지 않는다. 형식 오류는 400 {@code invalid_rows}, 수정 번호
	 * 불일치는 409 {@code version_conflict}이고 틀린 행의 순번과 이유를 함께 돌려준다. 행마다 감사 기록을 남긴다.
	 */
	ScoreSheet putScores(Actor actor, long courseId, long itemId, List<ScoreRow> rows) {
		requireManual(managedItem(actor.userId(), courseId, itemId));
		return writeScores(actor, courseId, itemId, rows);
	}

	/**
	 * 오프라인 시험의 점수를 여러 학생에게 한 번에 쓴다(ADR-0021 13번, ADR-0037). 규칙은 직접 입력 항목의 일괄 저장과 같다(전부 아니면
	 * 전무, 행마다 수정 번호, 배점 이하, 행마다 감사 기록). 학생에게는 그 퀴즈의 결과 공개 일시부터 보인다({@link Quiz#release}). 온라인
	 * 시험의 점수는 응시 채점으로만 정해지므로 409 {@code online_quiz}다.
	 */
	ScoreSheet putQuizScores(Actor actor, long courseId, long quizId, List<ScoreRow> rows) {
		Quiz quiz = this.quizzes.managed(actor.userId(), courseId, quizId);
		if (quiz.online()) {
			throw new ApiException(HttpStatus.CONFLICT, "online_quiz", "온라인 시험의 점수는 응시 채점으로 정해집니다.");
		}
		return writeScores(actor, courseId, quiz.settings().gradeItemId(), rows);
	}

	private ScoreSheet writeScores(Actor actor, long courseId, long itemId, List<ScoreRow> rows) {
		Set<Long> roster = new HashSet<>(this.rosters.students(courseId));
		List<RowError> errors = new ArrayList<>();
		List<Indexed> checked = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < rows.size(); i++) {
			String error = rowError(rows.get(i), roster, seen);
			if (error != null) {
				errors.add(new RowError(i, error));
			}
			else {
				ScoreRow row = rows.get(i);
				checked.add(new Indexed(i, row.studentId(), row.score(),
						PlainText.block(row.feedback(), MAX_FEEDBACK, "채점 의견"), row.version()));
			}
		}
		if (!errors.isEmpty()) {
			throw new ScoreRowsRejected(HttpStatus.BAD_REQUEST, "invalid_rows", "올바르지 않은 행이 있어 아무것도 저장하지 않았습니다.",
					errors);
		}
		// 같은 순서로 쓰도록 학생 ID 순으로 정렬한다.
		checked.sort(Comparator.comparingLong(Indexed::studentId));
		List<Long> studentIds = checked.stream().map(Indexed::studentId).toList();
		return this.writes.run(() -> {
			GradeItem item = this.records.lock(itemId);
			List<RowError> over = checked.stream()
				.filter((row) -> row.score() != null && row.score().compareTo(item.maxScore()) > 0)
				.map((row) -> new RowError(row.index(), "score_exceeds_max"))
				.toList();
			if (!over.isEmpty()) {
				throw new ScoreRowsRejected(HttpStatus.BAD_REQUEST, "invalid_rows", "올바르지 않은 행이 있어 아무것도 저장하지 않았습니다.",
						over);
			}
			Map<Long, Score> current = this.records.scoresOf(itemId, studentIds);
			List<RowError> conflicts = checked.stream()
				.filter((row) -> row.version() != (current.containsKey(row.studentId())
						? current.get(row.studentId()).version() : 0))
				.map((row) -> new RowError(row.index(), "version_conflict"))
				.toList();
			if (!conflicts.isEmpty()) {
				throw new ScoreRowsRejected(HttpStatus.CONFLICT, "version_conflict",
						"다른 수정이 먼저 저장된 행이 있어 아무것도 저장하지 않았습니다. 다시 불러온 뒤 저장해 주세요.", conflicts);
			}
			for (Indexed row : checked) {
				this.records.putScore(itemId, row.studentId(), row.score(), row.feedback(), actor.userId());
			}
			Map<Long, Score> written = this.records.scoresOf(itemId, studentIds);
			for (Indexed row : checked) {
				this.audit.success(actor.userId(), actor.sessionId(), "grade_score.write", "grade_score",
						written.get(row.studentId()).scoreId(), courseId);
			}
			return new ScoreSheet(itemId, checked.stream().map((row) -> StudentScore.of(written.get(row.studentId()))).toList());
		});
	}

	/** @return 틀린 이유 코드. 맞으면 null */
	private static String rowError(ScoreRow row, Set<Long> roster, Set<Long> seen) {
		if (row == null || row.studentId() == null) {
			return "invalid_student";
		}
		if (!roster.contains(row.studentId())) {
			return "not_student";
		}
		if (!seen.add(row.studentId())) {
			return "duplicate_student";
		}
		if (row.version() == null || row.version() < 0) {
			return "invalid_version";
		}
		if (row.score() == null) {
			return row.feedback() != null && !row.feedback().isBlank() ? "feedback_without_score" : null;
		}
		if (row.score().signum() < 0 || row.score().compareTo(MAX_SCORE) > 0 || row.score().stripTrailingZeros().scale() > 2) {
			return "invalid_score";
		}
		try {
			PlainText.block(row.feedback(), MAX_FEEDBACK, "채점 의견");
		}
		catch (ApiException ex) {
			return "invalid_feedback";
		}
		return null;
	}

	private GradeItem managedItem(long userId, long courseId, long itemId) {
		requireInstructor(userId, courseId);
		GradeItem item = this.records.find(courseId, itemId).orElseThrow(ApiException::notFound);
		if (!item.manual() && !live(courseId).contains(item)) {
			throw ApiException.notFound();
		}
		return item;
	}

	/** 항목 행을 잠그고 읽은 수정 번호와 비교한다. */
	private GradeItem lockAt(long itemId, long revision) {
		GradeItem locked = this.records.lock(itemId);
		if (locked.revision() != revision) {
			throw editConflict();
		}
		return locked;
	}

	private ItemView view(long courseId, long itemId) {
		GradeItem item = this.records.find(courseId, itemId).orElseThrow(ApiException::notFound);
		Live live = item.manual() ? new Live(List.of(item), Map.of(), Map.of()) : live(courseId);
		int graded = this.records.gradedCounts(List.of(itemId), this.rosters.students(courseId)).getOrDefault(itemId, 0);
		return ItemView.of(item, live, graded, this.quizzes.now());
	}

	/** 지금 있는 항목. 직접 입력 항목과, learning이 돌려주는 과제·퀴즈 활동(지우지 않은 것)에 딸린 항목만이다. */
	private Live live(long courseId) {
		Map<Long, Assignment> assignments = new HashMap<>();
		this.access.all(courseId).forEach((a) -> assignments.put(a.settings().gradeItemId(), a));
		Map<Long, Quiz> quizzes = new HashMap<>();
		this.quizzes.all(courseId).forEach((q) -> quizzes.put(q.settings().gradeItemId(), q));
		Live candidates = new Live(this.records.items(courseId), assignments, quizzes);
		return new Live(candidates.items().stream().filter(candidates::contains).toList(), assignments, quizzes);
	}

	private static GradeTotal.Part part(GradeItem item, Map<Long, Score> scores) {
		Score score = scores.get(item.gradeItemId());
		return new GradeTotal.Part(score == null ? null : score.score(), item.maxScore(), item.weight());
	}

	private static void requireManual(GradeItem item) {
		if (!item.manual()) {
			throw managedElsewhere(item);
		}
	}

	/** 활동에 딸린 항목을 성적부에서 바꾸려 할 때. 퀴즈 항목이면 managed_by_quiz, 과제 항목이면 managed_by_assignment다. */
	private static ApiException managedElsewhere(GradeItem item) {
		if (item.quiz()) {
			return new ApiException(HttpStatus.CONFLICT, "managed_by_quiz", "퀴즈 항목의 이름, 배점, 공개, 점수는 퀴즈 화면에서 바꿉니다.");
		}
		return new ApiException(HttpStatus.CONFLICT, "managed_by_assignment", "과제 항목의 이름, 배점, 공개, 점수는 과제 화면에서 바꿉니다.");
	}

	private static ApiException editConflict() {
		return new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
	}

	static String validatedType(String type) {
		if (type == null || type.isEmpty() || "all".equals(type)) {
			return null;
		}
		if (!TYPES.contains(type)) {
			throw ApiException.invalid("type은 all, assignment, quiz, manual 중 하나여야 합니다.");
		}
		return type;
	}

	static String validatedQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		String q = query.strip();
		if (q.length() > MAX_QUERY || q.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("q는 " + MAX_QUERY + "자 이하여야 합니다.");
		}
		return q.toLowerCase(Locale.ROOT);
	}

	private record Live(List<GradeItem> items, Map<Long, Assignment> assignments, Map<Long, Quiz> quizzes) {

		/** 과제 항목의 과제. 과제 항목이 아니면 null */
		Assignment assignmentOf(GradeItem item) {
			return item.manual() || item.quiz() ? null : this.assignments.get(item.gradeItemId());
		}

		/** 퀴즈 항목의 퀴즈. 퀴즈 항목이 아니면 null */
		Quiz quizOf(GradeItem item) {
			return item.quiz() ? this.quizzes.get(item.gradeItemId()) : null;
		}

		/** 직접 입력 항목이거나, 그 항목의 과제나 퀴즈가 learning에 살아 있는지. */
		boolean contains(GradeItem item) {
			return item.manual() || assignmentOf(item) != null || quizOf(item) != null;
		}

		/** 학생에게 그 항목이 보이는지. 과제·퀴즈 항목은 그 활동을 학생이 볼 수 있을 때만이다. */
		boolean openToStudents(GradeItem item, LocalDateTime now) {
			Quiz quiz = quizOf(item);
			if (quiz != null) {
				return quiz.activity().openToStudents(now);
			}
			Assignment assignment = assignmentOf(item);
			return assignment == null ? item.manual() : assignment.activity().openToStudents(now);
		}

		/** 학생에게 점수를 공개했는지. 퀴즈 항목은 {@link Quiz#release}, 그 밖은 {@link GradeItem#released}다. */
		boolean released(GradeItem item, LocalDateTime now) {
			Quiz quiz = quizOf(item);
			return quiz != null ? quiz.release(now).scores() : !item.quiz() && item.released(now);
		}

	}

	private record Indexed(int index, long studentId, BigDecimal score, String feedback, long version) {
	}

	/** 직접 입력 항목을 만들 때 검사한 입력. */
	record ItemInput(String name, BigDecimal maxScore, BigDecimal weight) {
	}

	/**
	 * @param name 직접 입력 항목만. 과제 항목이면 null
	 * @param maxScore 직접 입력 항목만. 과제 항목이면 null
	 * @param weight 비율. null이면 비율을 지운다
	 */
	record ItemUpdate(String name, BigDecimal maxScore, BigDecimal weight, long revision) {
	}

	/**
	 * 점수 한 행. studentId는 바꿀 대상 학생이고 작성자는 session에서 정한다(SC-02).
	 *
	 * @param score null이면 점수와 의견을 비운다
	 * @param version 그 학생 성적의 읽은 수정 번호. 아직 없으면 0
	 */
	record ScoreRow(Long studentId, BigDecimal score, String feedback, Long version) {
	}

	record Gradebook(long courseId, LocalDateTime serverTime, SettingsView settings, List<ItemView> items,
			List<StudentRow> students, long totalStudents, int page, int size) {
	}

	/**
	 * @param activitiesVisible 학생에게 평가 활동 목록을 보이는지(원본 43c의 평가 활동 비공개)
	 * @param revision 수정 요청에 그대로 보낼 번호. 아직 저장한 적이 없으면 0
	 */
	record SettingsView(boolean activitiesVisible, long revision) {
	}

	/**
	 * @param type assignment, quiz, manual
	 * @param assignmentId 과제 항목의 과제. 그 밖의 항목에는 없다
	 * @param quizId 퀴즈 항목의 퀴즈. 그 밖의 항목에는 없다
	 * @param released 학생에게 점수를 공개했는지. 퀴즈 항목은 퀴즈의 결과 공개 판정이다
	 * @param managedByAssignment 이름, 배점, 공개, 점수를 과제 화면에서 바꾸는 항목인지
	 * @param managedByQuiz 이름, 배점, 공개, 점수를 퀴즈 화면에서 바꾸는 항목인지
	 * @param gradedCount 수강생 가운데 점수를 받은 학생 수
	 */
	record ItemView(long itemId, String name, String type, @JsonInclude(JsonInclude.Include.NON_NULL) Long assignmentId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long quizId, BigDecimal maxScore, BigDecimal weight,
			boolean released, LocalDateTime releaseAt, boolean managedByAssignment, boolean managedByQuiz,
			long revision, int gradedCount) {

		private static ItemView of(GradeItem item, Live live, int gradedCount, LocalDateTime now) {
			Assignment assignment = live.assignmentOf(item);
			Quiz quiz = live.quizOf(item);
			return new ItemView(item.gradeItemId(), item.name(), item.type(),
					assignment == null ? null : assignment.assignmentId(), quiz == null ? null : quiz.quizId(),
					item.maxScore(), item.weight(), live.released(item, now), item.releaseAt(), assignment != null,
					quiz != null, item.revision(), gradedCount);
		}

	}

	/**
	 * @param scores 성적 행이 있는 항목만. 없는 칸의 수정 번호는 0이다
	 * @param total 점수를 받은 항목이 없으면 없다
	 */
	record StudentRow(long studentId, String name, List<ScoreCell> scores,
			@JsonInclude(JsonInclude.Include.NON_NULL) GradeTotal total) {
	}

	record ScoreCell(long itemId, BigDecimal score, String feedback, LocalDateTime gradedAt, long version) {

		static ScoreCell of(Score score) {
			return new ScoreCell(score.gradeItemId(), score.score(), score.feedback(), score.gradedAt(), score.version());
		}

	}

	record ScoreSheet(long itemId, List<StudentScore> rows) {
	}

	record StudentScore(long studentId, BigDecimal score, String feedback, LocalDateTime gradedAt, long version) {

		static StudentScore of(Score score) {
			return new StudentScore(score.userId(), score.score(), score.feedback(), score.gradedAt(), score.version());
		}

	}

	/**
	 * 성적부의 항목 하나와 그 항목의 학생 쪽.
	 *
	 * @param item 성적부 {@code items}의 한 칸과 같은 모양
	 * @param students 수강생 명단 쪽. 성적 행이 없는 학생도 있다
	 */
	record ItemSheet(long courseId, LocalDateTime serverTime, ItemView item, List<ItemStudent> students,
			long totalStudents, int page, int size) {
	}

	/**
	 * 항목 하나에서 학생 한 명의 칸.
	 *
	 * @param score 점수. 주지 않았으면 null
	 * @param feedback 의견. 없으면 null
	 * @param gradedAt 채점 일시. 성적 행이 없으면 null
	 * @param version 점수 일괄 저장에 그대로 보낼 수정 번호. 성적 행이 없으면 0
	 */
	record ItemStudent(long studentId, String name, BigDecimal score, String feedback, LocalDateTime gradedAt,
			long version) {

		static ItemStudent of(long studentId, String name, Score score) {
			return score == null ? new ItemStudent(studentId, name, null, null, null, 0)
					: new ItemStudent(studentId, name, score.score(), score.feedback(), score.gradedAt(), score.version());
		}

	}

	/**
	 * @param items 평가 활동을 비공개로 둔 과목이면 없다
	 * @param total 공개한 항목 가운데 점수를 받은 것의 합계. 그런 항목이 없거나 평가 활동이 비공개면 없다
	 */
	record MyGrades(long courseId, LocalDateTime serverTime, boolean activitiesVisible,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<MyGradeItem> items,
			@JsonInclude(JsonInclude.Include.NON_NULL) GradeTotal total) {
	}

	/**
	 * 학생 성적 한 줄. 원본의 "획득 / 배점", "미공개 / 배점", "- / 배점"에 해당한다.
	 *
	 * @param assignmentId 과제 항목만
	 * @param quizId 퀴즈 항목만
	 * @param closeAt 과제 항목의 마감, 퀴즈 항목의 응시 마감
	 * @param submitted 과제 항목에 최종 제출했는지
	 * @param released 점수를 공개했는지. false면 score, feedback, gradedAt 속성이 없다
	 * @param score 공개했고 점수를 받았을 때만 있다
	 */
	record MyGradeItem(long itemId, String name, String type,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long assignmentId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Long quizId,
			@JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime closeAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean submitted, BigDecimal maxScore,
			@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal weight, boolean released,
			@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal score,
			@JsonInclude(JsonInclude.Include.NON_NULL) String feedback,
			@JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime gradedAt) {

		private static MyGradeItem of(GradeItem item, Live live, boolean released, Score score,
				Map<Long, Submission> submissions) {
			Score shown = released && score != null && score.score() != null ? score : null;
			Assignment assignment = live.assignmentOf(item);
			Quiz quiz = live.quizOf(item);
			Long assignmentId = assignment == null ? null : assignment.assignmentId();
			Submission submission = assignmentId == null ? null : submissions.get(assignmentId);
			LocalDateTime closeAt = assignment != null ? assignment.activity().closeAt()
					: quiz != null ? quiz.activity().closeAt() : null;
			return new MyGradeItem(item.gradeItemId(), item.name(), item.type(), assignmentId,
					quiz == null ? null : quiz.quizId(), closeAt,
					assignment == null ? null : submission != null && submission.submitted(), item.maxScore(),
					item.weight(), released, shown == null ? null : shown.score(), shown == null ? null : shown.feedback(),
					shown == null ? null : shown.gradedAt());
		}

	}

}
