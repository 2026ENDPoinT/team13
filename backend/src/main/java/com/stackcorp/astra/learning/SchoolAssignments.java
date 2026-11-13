package com.stackcorp.astra.learning;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.SchoolCourseContent;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolAssignment;
import com.stackcorp.astra.learning.api.SchoolAssignmentRows;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 학교 e-Class 과제(ADR-0043). 목록은 로그인 때 가져온 학교 강의실 행으로만 만들고 학교에 요청하지 않는다. 상세는 학생이 그 과제를
 * 열었을 때만 그 학생의 학교 session으로 학교 상세를 읽는다(학교에서 조회 기록이 바뀔 수 있다).
 * <ul>
 * <li>과제 첨부는 과목 공용 학교 파일로 두어 같은 과목 수강생이 함께 연다(자료 첨부와 같은 사본 경로).</li>
 * <li>학생 본인이 낸 파일은 주인 있는 학교 파일({@link SchoolCourseContent#SUBMISSION})로 두어 그 학생에게만 보이고 열린다.</li>
 * <li>제출 상태, 제출 일시, 낸 글은 저장하지 않고 열 때마다 학교 화면에서 읽는다.</li>
 * <li>STack은 학교 과제를 제출하지 않는다. 제출·임시저장·다시 제출 경로는 409 {@code school_submit_at_school}로 거절하고 학교 주소
 * ({@code schoolUrl})로 안내한다.</li>
 * </ul>
 */
@Component
class SchoolAssignments implements SchoolAssignmentRows {

	static final String REPORT = "report";

	private final JdbcClient jdbc;

	private final SchoolContent content;

	private final SchoolFileStore files;

	private final SchoolContentProperties properties;

	SchoolAssignments(JdbcClient jdbc, SchoolContent content, SchoolFileStore files,
			SchoolContentProperties properties) {
		this.jdbc = jdbc;
		this.content = content;
		this.files = files;
		this.properties = properties;
	}

	@Override
	public List<SchoolAssignmentRow> of(long userId, long courseId, LocalDateTime now) {
		return this.jdbc.sql("""
				SELECT a.school_activity_id, a.week_no, a.title, a.open_at, a.close_at, a.expired_yn, a.school_url,
				       s.done_yn
				  FROM school_activities a
				  LEFT JOIN school_activity_states s ON s.school_activity_id = a.school_activity_id AND s.user_id = :user
				 WHERE a.course_id = :course AND a.school_type = :type
				 ORDER BY a.week_no, a.sort_order
				""")
			.param("user", userId)
			.param("course", courseId)
			.param("type", REPORT)
			.query((rs, n) -> {
				LocalDateTime openAt = rs.getObject("open_at", LocalDateTime.class);
				LocalDateTime closeAt = rs.getObject("close_at", LocalDateTime.class);
				return new SchoolAssignmentRow(SchoolContent.ACTIVITY_PREFIX + rs.getLong("school_activity_id"),
						rs.getInt("week_no"), rs.getString("title"), openAt, closeAt,
						phase(rs.getBoolean("expired_yn"), openAt, closeAt, now), rs.getBoolean("done_yn"),
						rs.getString("school_url"));
			})
			.list();
	}

	/**
	 * 학교 과제 하나를 그 학생의 학교 session으로 읽는다. 수강생이 아니거나(담당자 포함) 학교 과제가 아니면 404, 학교 session이 없거나
	 * 끝났으면 409 {@code school_signin_required}다.
	 */
	SchoolAssignmentView open(Actor actor, long courseId, long schoolActivityId, LocalDateTime now) {
		SchoolContent.ActivityRow row = report(actor, courseId, schoolActivityId);
		SchoolAssignment read = this.content.read(actor, courseId,
				(rooms, key) -> rooms.assignment(key, row.itemId(), this.properties.requestTimeout(),
						this.properties.readBudget()));
		this.files.replaceFor(courseId, REPORT, row.itemId(), read.files());
		this.files.replaceOwned(courseId, SchoolCourseContent.SUBMISSION, row.itemId(), actor.userId(),
				read.submittedFiles());
		List<SchoolFileStore.Row> attached = this.files.of(courseId, REPORT, List.of(row.itemId()));
		List<SchoolFileStore.Row> mine = this.files.owned(courseId, SchoolCourseContent.SUBMISSION, row.itemId(),
				actor.userId());
		List<SchoolFileStore.Row> both = new ArrayList<>(attached);
		both.addAll(mine);
		var summarized = this.content.summarized(both);
		LocalDateTime openAt = read.openAt() != null ? read.openAt() : row.openAt();
		LocalDateTime closeAt = read.closeAt() != null ? read.closeAt() : row.closeAt();
		String id = SchoolContent.ACTIVITY_PREFIX + row.id();
		Info info = new Info(id, id, SchoolContent.ORIGIN, row.weekNo(), read.title() != null ? read.title() : row.title(),
				read.body(), openAt, closeAt, true, null, null, null, null, null, false,
				SchoolContent.fileViews(attached, summarized), row.schoolUrl());
		String status = read.submittedAt() == null ? "not_submitted"
				: closeAt != null && read.submittedAt().isAfter(closeAt) ? "late" : "submitted";
		Submission submission = new Submission(status, null, null, read.submittedAt(), read.submissionBody(),
				SchoolContent.fileViews(mine, summarized), null, read.submissionLabel());
		return new SchoolAssignmentView(info, phase(row.expired(), openAt, closeAt, now), now, submission,
				SchoolContent.ORIGIN, row.schoolUrl());
	}

	/**
	 * 학교 과제의 제출·임시저장 요청을 거절한다. 볼 수 없는 과제면 404로 숨긴다.
	 */
	ApiException refuseSubmit(Actor actor, long courseId, long schoolActivityId, String schoolType) {
		this.content.requireMember(actor, courseId);
		SchoolContent.ActivityRow row = this.content.activities(courseId, schoolType, schoolActivityId)
			.stream()
			.findFirst()
			.orElseThrow(ApiException::notFound);
		return new ApiException(HttpStatus.CONFLICT, "school_submit_at_school",
				"학교 e-Class의 " + (REPORT.equals(row.type()) ? "과제는" : "시험·설문은")
						+ " STack에서 제출하지 않습니다. 학교 e-Class(schoolUrl)에서 제출해 주세요.");
	}

	private SchoolContent.ActivityRow report(Actor actor, long courseId, long schoolActivityId) {
		this.content.requireMember(actor, courseId);
		return this.content.activities(courseId, REPORT, schoolActivityId)
			.stream()
			.findFirst()
			.orElseThrow(ApiException::notFound);
	}

	private static String phase(boolean expired, LocalDateTime openAt, LocalDateTime closeAt, LocalDateTime now) {
		return expired ? "closed" : ActivityPhase.of(openAt, closeAt, null, now);
	}

	/**
	 * 학교 과제 상세. 서비스 학생 과제 상세({@code StudentAssignment})와 같은 이름의 값에 {@code origin}, {@code schoolUrl}을 더한다.
	 */
	record SchoolAssignmentView(Info assignment, String phase, LocalDateTime serverTime, Submission submission,
			String origin, String schoolUrl) {
	}

	/**
	 * 서비스 과제 정보({@code Info})와 같은 이름. 학교 화면에서 읽지 않는 값(지각 정책, 제출 방식, 배점)은 null이다.
	 * @param assignmentId {@code "school-<id>"}. {@code activityId}와 같다
	 * @param files 과제 첨부. 자료 파일과 같은 모양이다
	 */
	record Info(String assignmentId, String activityId, String origin, int weekNo, String title, String description,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published, String latePolicy,
			LocalDateTime lateUntil, Boolean allowText, Boolean allowFile, BigDecimal maxScore,
			boolean scoresReleased, List<SchoolContent.SchoolFileView> files, String schoolUrl) {
	}

	/**
	 * 학생 본인의 학교 제출 정보. 서비스 {@code MySubmission}과 같은 이름이다.
	 * @param status not_submitted, submitted, late(제출 일시가 마감 뒤)
	 * @param version 학교 과제에는 없다(null). STack으로 제출하지 않는다
	 * @param files 본인이 낸 파일. 그 학생에게만 보인다
	 * @param statusLabel 학교 화면의 제출 상태 표시(예 정상제출). 없으면 null
	 */
	record Submission(String status, Integer version, LocalDateTime firstSubmittedAt, LocalDateTime submittedAt,
			String body, List<SchoolContent.SchoolFileView> files, Object draft, String statusLabel) {
	}

}
