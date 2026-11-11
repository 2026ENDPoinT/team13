package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stackcorp.astra.assessment.GradebookRecords.ReleasedCandidate;
import com.stackcorp.astra.assessment.api.ReleasedGrades;

import org.springframework.stereotype.Service;

/**
 * {@link ReleasedGrades} 구현. 공개 판정은 {@link GradeItem#released(String, LocalDateTime, LocalDateTime)}, 과제 항목의 학생 열람은
 * {@link AssignmentAccess#all}로 성적 화면(GradebookService.mine)과 같은 규칙을 쓴다.
 */
@Service
class ReleasedGradeQueries implements ReleasedGrades {

	/** 거르기 전에 읽는 후보 수의 상한. */
	private static final int CANDIDATES = 100;

	private final GradebookRecords records;

	private final AssignmentAccess access;

	ReleasedGradeQueries(GradebookRecords records, AssignmentAccess access) {
		this.records = records;
		this.access = access;
	}

	@Override
	public List<ReleasedGrade> releasedSince(long studentId, Collection<Long> courseIds, LocalDateTime since,
			LocalDateTime now, int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
		}
		Set<Long> courses = new HashSet<>(courseIds);
		courses.removeAll(this.records.hiddenActivityCourses(courses));
		List<ReleasedCandidate> candidates = this.records
			.releasedCandidates(studentId, courses, since, now, CANDIDATES)
			.stream()
			.filter((c) -> GradeItem.released(c.scoreVisibility(), c.releaseAt(), now))
			.toList();
		Map<Long, Set<Long>> visibleItems = new HashMap<>();
		List<ReleasedGrade> result = new ArrayList<>();
		for (ReleasedCandidate c : candidates) {
			if (c.activityId() != null && !visibleItems.computeIfAbsent(c.courseId(), (course) -> visibleAssignmentItems(course, now))
				.contains(c.gradeItemId())) {
				continue;
			}
			result.add(new ReleasedGrade(c.courseId(), c.gradeItemId(), c.activityId(), c.name(), c.releaseAt()));
			if (result.size() == limit) {
				break;
			}
		}
		return result;
	}

	private Set<Long> visibleAssignmentItems(long courseId, LocalDateTime now) {
		Set<Long> items = new HashSet<>();
		this.access.all(courseId)
			.stream()
			.filter((a) -> a.activity().openToStudents(now))
			.forEach((a) -> items.add(a.settings().gradeItemId()));
		return items;
	}

}
