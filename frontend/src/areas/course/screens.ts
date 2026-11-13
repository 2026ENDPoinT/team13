import type { Screen, NavItem } from "../../types.ts";
const room = "/ilos/cls/st/submain/submain_form.acl";
const list = "/ilos/cls/st/activity/activity_list_form.acl";
const nr = "/ilos/cls/st/nr/submain_form.acl";

export const screens: Screen[] = [
  { id: "40_room_submain", role: "student", title: "강의실", path: room, nav: "classroom", kind: "room", course: "bi", layout: "course" },
  { id: "40b_room_lecplan_pop", role: "student", title: "강의계획서", path: room, query: { pop: "syllabus" }, nav: "plan", kind: "room", course: "bi", layout: "course", dialog: "syllabus" },
  { id: "40c_room_lecture_change_pop", role: "student", title: "과목 변경", path: room, query: { pop: "switch" }, nav: "classroom", kind: "room", course: "bi", layout: "course", dialog: "switch" },
  { id: "42_room_group_list", role: "student", title: "그룹", path: "/ilos/cls/st/group/group_list_form.acl", nav: "groups", kind: "groups", course: "bi", layout: "course" },
  { id: "43_room_eval_result", role: "student", title: "성적", path: "/ilos/cls/st/eval/eval3_result_view_form.acl", nav: "grades", kind: "grades", course: "bi", layout: "course", gradesMode: "partial" },
  { id: "43b_room_eval_public", role: "student", title: "성적 공개", path: "/ilos/cls/st/eval/eval3_result_view_form.acl", query: { open: "1" }, nav: "grades", kind: "grades", course: "bi", layout: "course", gradesMode: "public" },
  { id: "43c_room_eval_private", role: "student", title: "성적 비공개", path: "/ilos/cls/st/eval/eval3_result_view_form.acl", query: { open: "0" }, nav: "grades", kind: "grades", course: "bi", layout: "course", gradesMode: "private" },
  { id: "44_room_notice_list", role: "student", title: "공지", path: "/ilos/cls/st/notice/notice_list_form.acl", nav: "announce", kind: "board", board: "course", course: "bi", layout: "course" },
  { id: "44c_room_notice_list_items", role: "student", title: "공지", path: "/ilos/cls/st/notice/notice_list_form.acl", query: { expand: "1" }, nav: "announce", kind: "board", board: "course", course: "bi", layout: "course", expand: true },
  { id: "44d_room_notice_view_pop", role: "student", title: "공지", path: "/ilos/cls/st/notice/notice_list_form.acl", query: { pop: "notice" }, nav: "announce", kind: "board", board: "course", course: "bi", layout: "course", dialog: "notice" },
  { id: "45_room_qna_list", role: "student", title: "Q & A", path: "/ilos/cls/st/qna/qna_list_form.acl", nav: "qna", kind: "board", board: "course-qna", course: "bi", layout: "course" },
  { id: "45b_room_qna_insert_pop", role: "student", title: "질문 작성", path: "/ilos/cls/st/qna/qna_list_form.acl", query: { pop: "qna-write" }, nav: "qna", kind: "board", board: "course-qna", course: "bi", layout: "course", dialog: "qna-write" },
  { id: "45c_room_qna_list_items", role: "student", title: "Q & A", path: "/ilos/cls/st/qna/qna_list_form.acl", query: { expand: "1" }, nav: "qna", kind: "board", board: "course-qna", course: "bi", layout: "course", expand: true },
  { id: "45d_room_qna_view_pop", role: "student", title: "질문", path: "/ilos/cls/st/qna/qna_list_form.acl", query: { pop: "qna" }, nav: "qna", kind: "board", board: "course-qna", course: "bi", layout: "course", dialog: "qna" },
  { id: "45e_room_qna_reply_pop", role: "student", title: "답글", path: "/ilos/cls/st/qna/qna_list_form.acl", query: { pop: "qna-reply" }, nav: "qna", kind: "board", board: "course-qna", course: "bi", layout: "course", dialog: "qna-reply" },
  { id: "46_room_files_list", role: "student", title: "파일", path: "/ilos/cls/st/files/files_list_form.acl", nav: "files", kind: "files", course: "bi", layout: "course" },
  { id: "47_room_open_board_list", role: "student", title: "열린게시판", path: "/ilos/cls/st/board/open_material_list_form.acl", nav: "board", kind: "board", board: "open", course: "bi", layout: "course" },
  { id: "47b_room_open_board_insert_pop", role: "student", title: "글쓰기", path: "/ilos/cls/st/board/open_material_list_form.acl", query: { pop: "board-write" }, nav: "board", kind: "board", board: "open", course: "bi", layout: "course", dialog: "board-write" },
  { id: "50_activity_all", role: "student", title: "학습활동", path: list, nav: "activity", kind: "activity-list", filter: "all", course: "bi", layout: "course" },
  { id: "51_activity_filter_video", role: "student", title: "영상", path: list, query: { filter: "video" }, nav: "activity", kind: "activity-list", filter: "video", course: "bi", layout: "course" },
  { id: "52_activity_filter_material", role: "student", title: "자료", path: list, query: { filter: "material" }, nav: "activity", kind: "activity-list", filter: "material", course: "bi", layout: "course" },
  { id: "53_activity_filter_report", role: "student", title: "과제", path: list, query: { filter: "report" }, nav: "activity", kind: "activity-list", filter: "report", course: "bi", layout: "course" },
  { id: "54_activity_filter_test", role: "student", title: "시험", path: list, query: { filter: "test" }, nav: "activity", kind: "activity-list", filter: "test", course: "bi", layout: "course" },
  { id: "55_activity_filter_ongoing", role: "student", title: "진행중", path: list, query: { filter: "ongoing" }, nav: "activity", kind: "activity-list", filter: "ongoing", course: "bi", layout: "course" },
  { id: "56_activity_filter_not_submit", role: "student", title: "미제출", path: list, query: { filter: "missing" }, nav: "activity", kind: "activity-list", filter: "missing", course: "bi", layout: "course" },
  { id: "70_nr_room_submain", role: "student", title: "비정규 강의실", path: nr, nav: "classroom", kind: "room", course: "sec", layout: "course" },
  { id: "72_nr_room_submain_video", role: "student", title: "비정규 강의실", path: nr, query: { focus: "video" }, nav: "classroom", kind: "room", course: "sec", layout: "course", focus: "video" },
];

export const courseNav: NavItem[] = [
  { nav: "classroom", labelKey: "classroom", screenId: "40_room_submain", count: "14" },
  { nav: "attendance", labelKey: "attendance", screenId: "41_room_attendance" },
  { nav: "groups", labelKey: "groups", screenId: "42_room_group_list" },
  { nav: "grades", labelKey: "grades", screenId: "43_room_eval_result" },
  { nav: "announce", labelKey: "announce", screenId: "44_room_notice_list", count: "2" },
  { nav: "qna", labelKey: "qna", screenId: "45_room_qna_list" },
  { nav: "files", labelKey: "files", screenId: "46_room_files_list" },
  { nav: "board", labelKey: "board", screenId: "47_room_open_board_list" },
];

export const specialCourseNav: NavItem[] = [
  { nav: "classroom", labelKey: "classroom", screenId: "70_nr_room_submain" },
  { nav: "attendance", labelKey: "attendance", screenId: "74_nr_room_attendance" },
  { nav: "activity", labelKey: "activity", screenId: "50_activity_all", courseId: "sec" },
];
