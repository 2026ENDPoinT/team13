const home = "/ilos/main/main_form.acl";

export const screens = [
  { id: "00_main_home", role: "student", title: "홈", path: home, nav: "home", kind: "home" },
  { id: "00_notice_for_me", role: "student", title: "초기 공지", path: home, query: { pop: "welcome" }, nav: "home", kind: "home", dialog: "welcome" },
  { id: "08_home_aside_collapsed", role: "student", title: "홈", path: home, query: { aside: "0" }, nav: "home", kind: "home", aside: "0" },
  { id: "10_home_calendar_next_month", role: "student", title: "홈", path: home, query: { month: "2026-12" }, nav: "home", kind: "home", month: "2026-12" },
  { id: "13_home_nav_regular_hover", role: "student", title: "홈", path: home, query: { hover: "regular" }, nav: "home", kind: "home", hoverNav: "regular" },
  { id: "17_pop_calendar_day_detail", role: "student", title: "일정", path: home, query: { pop: "day" }, nav: "home", kind: "home", dialog: "day" },
  { id: "01_pop_todo", role: "student", title: "할 일", path: home, query: { pop: "todo" }, nav: "home", kind: "home", dialog: "todo" },
  { id: "09_home_todo_more", role: "student", title: "할 일", path: home, query: { pop: "todo-more" }, nav: "home", kind: "home", dialog: "todo-more" },
  { id: "03_pop_notification", role: "student", title: "알림", path: home, query: { pop: "notify" }, nav: "home", kind: "home", dialog: "notify" },
  { id: "03b_pop_notification_unread", role: "student", title: "알림", path: home, query: { pop: "notify-unread" }, nav: "home", kind: "home", dialog: "notify-unread" },
  { id: "06_pop_notice_view", role: "student", title: "공지", path: "/ilos/main/community/notice_list_form.acl", query: { pop: "notice" }, nav: "info", kind: "board", dialog: "notice", board: "global" },
  { id: "04_pop_mymenu", role: "student", title: "내 메뉴", path: home, query: { pop: "profile" }, nav: "home", kind: "home", dialog: "profile" },
  { id: "11_pop_setup_theme", role: "student", title: "설정", path: home, query: { pop: "settings" }, nav: "home", kind: "home", dialog: "settings" },
  { id: "14_pop_myinfo", role: "student", title: "내 정보", path: home, query: { pop: "myinfo" }, nav: "home", kind: "home", dialog: "myinfo" },
  { id: "14b_pop_avatar_set", role: "student", title: "아바타", path: home, query: { pop: "avatar" }, nav: "home", kind: "home", dialog: "avatar" },
  { id: "16_pop_file", role: "student", title: "파일함", path: home, query: { pop: "files" }, nav: "home", kind: "home", dialog: "files" },
  { id: "15_pop_history", role: "student", title: "접속 기기", path: home, query: { pop: "history" }, nav: "home", kind: "home", dialog: "history" },
  { id: "15b_pop_history_login_tab", role: "student", title: "접속 이력", path: home, query: { pop: "history-login" }, nav: "home", kind: "home", dialog: "history-login" },
  { id: "21_regular_register_list", role: "student", title: "수강 과목", path: "/ilos/main/rg/regular_register_list_form.acl", nav: "regular", kind: "course-list", term: "2026-2" },
  { id: "21b_regular_register_prev_semester", role: "student", title: "지난 학기", path: "/ilos/main/rg/regular_register_list_form.acl", query: { term: "2026-1" }, nav: "regular", kind: "course-list", term: "2026-1" },
  { id: "23_nonregular_register_list", role: "student", title: "비정규 수강", path: "/ilos/main/nr/non_regular_register_list_form.acl", nav: "special", kind: "course-list", term: "special-enrolled" },
  { id: "22_nonregular_list", role: "student", title: "비정규 전체", path: "/ilos/main/nr/non_regular_list_form.acl", nav: "special", kind: "catalog", catalog: "special" },
  { id: "22b_nonregular_list_filter", role: "student", title: "비정규 필터", path: "/ilos/main/nr/non_regular_list_form.acl", query: { open: "1" }, nav: "special", kind: "catalog", catalog: "special", openOnly: true },
  { id: "20_regular_list", role: "student", title: "정규 전체", path: "/ilos/main/rg/regular_list_form.acl", nav: "regular", kind: "catalog", catalog: "regular" },
  { id: "20_regular_list_filter", role: "student", title: "정규 필터", path: "/ilos/main/rg/regular_list_form.acl", query: { dept: "산업정보" }, nav: "regular", kind: "catalog", catalog: "regular", dept: "산업정보" },
  { id: "24_nonregular_view", role: "student", title: "비정규 상세", path: "/ilos/main/nr/non_regular_view_form.acl", nav: "special", kind: "detail", course: "lit" },
  { id: "24b_nonregular_view_open", role: "student", title: "모집 중", path: "/ilos/main/nr/non_regular_view_form.acl", query: { open: "1" }, nav: "special", kind: "detail", course: "lit" },
  { id: "24c_nonregular_apply_pop", role: "student", title: "수강 신청", path: "/ilos/main/nr/non_regular_view_form.acl", query: { pop: "apply" }, nav: "special", kind: "detail", course: "lit", dialog: "apply" },
  { id: "24d_nonregular_view_enrolled", role: "student", title: "수강 중", path: "/ilos/main/nr/non_regular_view_form.acl", query: { enrolled: "1" }, nav: "special", kind: "detail", course: "sec" },
  { id: "25_regular_view", role: "student", title: "과목 상세", path: "/ilos/main/rg/regular_view_form.acl", nav: "regular", kind: "detail", course: "ux" },
  { id: "30_community_notice_list", role: "student", title: "공지사항", path: "/ilos/main/community/notice_list_form.acl", nav: "info", kind: "board", board: "global" },
  { id: "30b_notice_filter_student", role: "student", title: "학생 공지", path: "/ilos/main/community/notice_list_form.acl", query: { audience: "student" }, nav: "info", kind: "board", board: "global", audience: "student" },
  { id: "31_community_qna_list", role: "student", title: "질의응답", path: "/ilos/main/community/qna_list_form.acl", nav: "info", kind: "board", board: "qna" },
  { id: "31b_qna_insert_pop", role: "student", title: "질문 작성", path: "/ilos/main/community/qna_list_form.acl", query: { pop: "qna-write" }, nav: "info", kind: "board", board: "qna", dialog: "qna-write" },
  { id: "31c_supporters_pop", role: "student", title: "서포터즈", path: "/ilos/main/community/qna_list_form.acl", query: { pop: "chat" }, nav: "info", kind: "board", board: "qna", dialog: "chat" },
  { id: "32_community_material_list", role: "student", title: "자료실", path: "/ilos/main/community/material_list_form.acl", nav: "info", kind: "board", board: "material" },
  { id: "33_community_survey_list", role: "student", title: "설문", path: "/ilos/main/community/survey_list_form.acl", nav: "info", kind: "board", board: "survey" },
  { id: "33b_survey_view_pop", role: "student", title: "설문", path: "/ilos/main/community/survey_list_form.acl", query: { pop: "survey" }, nav: "info", kind: "board", board: "survey", dialog: "survey" },
  { id: "study_home", role: "student", title: "학습실", path: "/ilos/main/study/study_form.acl", nav: "study", kind: "study" },
  { id: "study_summary", role: "student", title: "자료 요약", path: "/ilos/main/study/summary_form.acl", nav: "study", kind: "study", mode: "summary" },
  { id: "study_lecture", role: "student", title: "녹음", path: "/ilos/main/study/lecture_form.acl", nav: "study", kind: "lecture" },
  { id: "sitemap", role: "any", title: "전체 화면", path: "/ilos/main/sitemap_form.acl", nav: "info", kind: "sitemap", extra: true },
  { id: "logout", role: "any", title: "로그아웃", path: "/ilos/lo/logout.acl", nav: "home", kind: "logout" },
];

export const homeNav = [
  { nav: "home", labelKey: "home", screenId: "00_main_home" },
  { nav: "regular", labelKey: "regular", screenId: "21_regular_register_list" },
  { nav: "study", labelKey: "study", screenId: "study_home" },
];

export const infoNav = [
  { screenId: "30_community_notice_list", label: "공지사항" },
  { screenId: "31_community_qna_list", label: "질의응답" },
  { screenId: "32_community_material_list", label: "자료실" },
  { screenId: "33_community_survey_list", label: "설문" },
];

export const studentRail = [
  ["home", "00_main_home", "홈"],
  ["book", "21_regular_register_list", "정규"],
  ["layers", "22_nonregular_list", "비정규"],
  ["info", "30_community_notice_list", "알림마당"],
  ["chat", "31c_supporters_pop", "서포터즈"],
];
