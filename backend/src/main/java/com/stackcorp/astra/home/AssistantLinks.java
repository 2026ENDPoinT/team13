package com.stackcorp.astra.home;

import java.net.URI;
import java.util.Map;

import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;

/**
 * 도우미 답의 행동 주소. Frontend-final(origin/main f096656)의 주소 규칙을 옮겼다. 과목은 화면에서 {@code kj=c<courseId>}로 부른다
 * ({@code shared/backend.ts} {@code kjOf}).
 * <ul>
 * <li>강의실: 정규 {@code /ilos/cls/st/submain/submain_form.acl?kj=}, 비정규 {@code /ilos/cls/st/nr/submain_form.acl?kj=}
 * ({@code areas/course/screens.ts}의 40_room_submain, 70_nr_room_submain)</li>
 * <li>서비스 활동: {@code shared/activity-links.ts}의 {@code serverActivityHref}. 영상과 자료는 {@code n=srv-<활동 ID>}, 과제·시험·설문은
 * 과제·퀴즈·설문 ID가 있으면 {@code n=srv-<그 ID>}, 없으면 연결 화면 {@code act=<활동 ID>}. 그 밖의 유형은 볼 화면이 없어 강의실로 간다.</li>
 * <li>강의실 공지: {@code /ilos/cls/st/notice/notice_list_form.acl?pop=notice&kj=&id=<공지 ID>}(44d_room_notice_view_pop). 학교 공지는
 * {@code id=school-<n>}({@code areas/course/notices-server.tsx}의 {@code isSchoolNotice})</li>
 * <li>학교에서 가져온 활동: 자료는 {@code m=lecture_material&n=school-<n>}({@code materialHref}), 영상은
 * {@code m=lecture_weeks&n=school-<n>}({@code schoolVideoHref}), 과제는 {@code m=report&n=school-<n>}({@code schoolAssignmentHref},
 * {@code studentAssignmentHref})로 STack 상세를 같은 창에서 연다. 시험, 설문, 그 밖의 유형은 STack 화면이 없어 학교 e-Class 주소를 새
 * 창(external)으로 연다({@code home-remote.ts}의 {@code todoHref}·{@code todoSchoolHref}, {@code course-activities-remote.ts}의 학교
 * 행). 학교 주소는 https만 쓴다.</li>
 * </ul>
 */
final class AssistantLinks {

	static final String ACTIVITY = "/ilos/cls/st/activity/activity_form.acl";

	private static final int MAX_LABEL = 80;

	/** learning_activities.activity_type → 화면 유형. home-remote.ts의 ACTIVITY 표와 같다. */
	private static final Map<String, String> KINDS = Map.of("lecture_weeks", "video", "lecture_material", "material",
			"report", "report", "test", "test", "survey", "survey", "zoom", "zoom");

	private AssistantLinks() {
	}

	/** @param external true면 학교 e-Class 주소다. 화면은 새 창으로 연다 */
	record Action(String label, String href, boolean external) {

		Map<String, Object> body() {
			return Map.of("label", this.label, "href", this.href, "external", this.external);
		}

	}

	static String kindOf(String type) {
		return KINDS.getOrDefault(type, "other");
	}

	static Action room(MemberCourse course) {
		String path = course.nonRegular() ? "/ilos/cls/st/nr/submain_form.acl" : "/ilos/cls/st/submain/submain_form.acl";
		return new Action(label(name(course) + " 강의실"), path + "?kj=" + kj(course), false);
	}

	static Action activity(MemberCourse course, String type, long activityId, Long targetId, String title) {
		String kj = kj(course);
		String query = switch (type) {
			case "lecture_weeks", "lecture_material" -> "m=" + type + "&n=srv-" + activityId + "&kj=" + kj;
			case "report", "test", "survey" -> targetId != null ? "m=" + type + "&n=srv-" + targetId + "&kj=" + kj
					: "m=" + type + "&act=" + activityId + "&kj=" + kj;
			default -> null;
		};
		if (query == null) {
			Action room = room(course);
			return new Action(itemLabel(course, title), room.href(), false);
		}
		return new Action(itemLabel(course, title), ACTIVITY + "?" + query, false);
	}

	/**
	 * 학교에서 가져온 활동.
	 * @param schoolType 학교 메뉴 ID(lecture_weeks, lecture_material, report, test, survey …)
	 * @param schoolUrl 학교 e-Class 주소. STack 화면이 없는 유형에만 쓴다. https가 아니거나 없으면 강의실로 간다
	 */
	static Action school(MemberCourse course, String schoolType, long schoolActivityId, String title,
			String schoolUrl) {
		String ref = "school-" + schoolActivityId;
		String query = switch (schoolType) {
			case "lecture_weeks", "lecture_material", "report" -> "m=" + schoolType + "&n=" + ref + "&kj=" + kj(course);
			default -> null;
		};
		if (query != null) {
			return new Action(itemLabel(course, title), ACTIVITY + "?" + query, false);
		}
		if (schoolUrl != null && isHttps(schoolUrl)) {
			return new Action(itemLabel(course, title), schoolUrl, true);
		}
		return new Action(itemLabel(course, title), room(course).href(), false);
	}

	/** 강의실 공지. 학교 공지는 {@code school}이 true이고 공지 ID가 STack의 학교 공지 ID다. */
	static Action notice(MemberCourse course, long noticeId, boolean school, String title) {
		return new Action(itemLabel(course, title), "/ilos/cls/st/notice/notice_list_form.acl?pop=notice&kj=" + kj(course)
				+ "&id=" + (school ? "school-" : "") + noticeId, false);
	}

	static String name(MemberCourse course) {
		if (course.name() != null && !course.name().isBlank()) {
			return course.name();
		}
		return course.code() != null && !course.code().isBlank() ? course.code() : "이름 없는 과목";
	}

	private static String kj(MemberCourse course) {
		return "c" + course.courseId();
	}

	private static String itemLabel(MemberCourse course, String title) {
		return label(name(course) + " · " + (title == null || title.isBlank() ? "제목 없음" : title));
	}

	private static String label(String text) {
		String flat = text.replaceAll("\\s+", " ").strip();
		return flat.codePointCount(0, flat.length()) <= MAX_LABEL ? flat
				: flat.substring(0, flat.offsetByCodePoints(0, MAX_LABEL - 1)) + "…";
	}

	private static boolean isHttps(String url) {
		try {
			URI uri = URI.create(url);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
