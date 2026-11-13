# ADR-0043 학교 강의실 내용을 학생의 학교 session으로 필요할 때 읽고, 연 파일은 과목 파일로 받아 둔다

## 상태
채택 (2026-11-14). 1단계(자료와 그 파일), 2단계(강의실 공지와 그 첨부)까지 구현. 과제 상세·영상 상세·강의계획서·출석·성적은 같은 틀로 이어 간다.

## 맥락
ADR-0042는 학교 강의실의 주차·활동 목록만 옮기고 내용은 학교 e-Class로 보냈다. 소유자는 학생이 학교 강의실에서 보는 내용(자료, 영상, 과제, 공지 등)을 STack 안에서 보이라고 했다. 정해진 것:

- 학교 session(쿠키만)은 학생의 STack session이 사는 동안 서버에 둘 수 있다. 메모리 밖에 두면 암호화한다. 로그아웃·만료 때 버리고 로그에 남기지 않는다. 학교 session이 끝난 학생에게는 빈 화면 대신 다시 로그인하라고 한다.
- 학생이 연 파일은 STack 저장소에 받아 두고 서비스 파일 경로(미리보기 등)로 준다. 같은 과목 수강생이 함께 쓴다.
- 한 학교 session의 요청은 차례로, 시간을 정해 보낸다.
- 학교 쪽 상태(조회수, 읽음)를 바꾸는 요청(상세, 파일 받기)은 그 학생이 STack에서 그 항목을 열었을 때만 그 학생의 학교 session으로 보낸다. 로그인 때, 뒤에서 도는 작업, 미리 읽기로 보내지 않는다. 목록은 상태를 바꾸지 않는 목록 화면만 쓴다.
- 영상: STack은 학교에 시청·출석을 알리지 않고 학교의 확인 절차(2차 인증)를 건너뛰지 않는다. 영상 파일 주소는 학교 학습창이 2차 인증 관문 뒤에 부르는 `online_view_navi.acl`로만 얻을 수 있어 부르지 않는다. 영상은 학교 학습창 링크로 둔다.

## 결정
1. **학교 session의 자리**: 로그인에 성공하면 `SchoolPasswordController`가 학교 session 손잡이(`SchoolCourseRooms`)를 새 STack session ID에 붙인다(`identity.session.SchoolSessionRegistry`, 공개 API `identity.api.SchoolSessions`). 메모리에만 두므로 암호화할 저장본이 없고, 서버를 다시 띄우면 없어진다(학생은 다시 로그인하라는 답을 받는다). `SessionStore.revoke`(로그아웃, 만료 폐기)는 바로 닫고, 그 밖의 폐기와 요청 없이 지난 유휴·절대 만료는 5분마다 `user_sessions`를 보고 닫는다. 강의실 목록 가져오기(ADR-0042)는 이 손잡이로 읽기만 하고 닫지 않는다.
2. **차례와 시간**: 손잡이의 읽기는 하나씩 차례로 처리한다(과목이 학교 session에 묶이는 구조). 과목 묶기(`eclass_room2.acl`)는 묶인 과목이 다를 때만 보낸다. 요청 하나 `astra.school-content.request-timeout`(8초), 화면 하나 `read-budget`(20초), 파일 하나 `download-budget`(60초).
3. **학교 session이 끝났을 때**: 학교가 로그인 화면이나 `E_NOSESSION`·`E_USER_NOT_SAME`을 주면 `SchoolLoginException.Kind.EXPIRED`다. 그 학교 session을 버리고 409 `school_signin_required`로 답한다. 학교가 항목을 보여 주지 않으면(`NOT_FOUND`) 404, 학교 장애는 502 `school_unavailable`.
4. **자료 목록**: 학생의 `GET /api/courses/{courseId}/materials`에 학교 자료(학교 강의실 목록의 `lecture_material` 행)를 서비스 자료 뒤에 붙인다. 파일은 학교 파일모음(`POST /ilos/cls/st/files/files_list.acl`, 목록 화면, 과목의 모든 자료·과제 파일, 한 번에 온다)을 과목마다 `list-interval`(10분)에 한 번, 목록을 연 학생의 학교 session으로 읽어 `school_files`(V43)에 둔다. 학교 session이 없으면 둔 목록을 그대로 쓴다. 학교 목록에서 사라진 파일은 지우고 받아 둔 사본의 첨부를 끊는다.
5. **자료 상세**: `GET /api/courses/{courseId}/activities/school-<id>`. 자료는 학생의 학교 session으로 학교 상세(`lecture_material_view_form.acl`)와 첨부 목록(`efile_list.acl`)을 읽는다(학교에서 그 자료의 조회수가 하나 는다). 본문은 HTML이 아니라 글자로 바꿔 `description`에 준다(SC-08, 서비스 자료도 글자 본문이다). 본문은 저장하지 않는다. 이어 보기 기록(`activity_continue_update.acl`)은 보내지 않는다.
6. **파일**: 아직 받지 않은 파일은 `fileId: "school-file-<id>"`다. 학생이 내려받기(`GET …/files/school-file-<id>/content`)나 미리보기(`POST …/files/school-file-<id>/preview-sessions`)로 열면 그 학생의 학교 session으로 받아(`efile_download.acl`, 정책 상한 50 MiB) `files.api.ImportedFiles`로 과목 파일(용도 `school_file`, 업로드와 같은 이름·형식·악성코드 검사와 중복 제거)로 저장하고 첨부 그룹 하나에 붙인 뒤 서비스 파일 경로로 넘긴다(303/307). 그다음 목록은 서비스 파일 ID(숫자)를 준다. 권한은 `learning.SchoolFileAttachmentAccess`(그 과목 구성원 읽기만). 같은 과목의 다른 학생은 학교에 요청하지 않고 사본을 연다. 그 학생의 학교 쪽 읽음·조회수는 바뀌지 않는다. 썸네일·요약 같은 목록이 부르는 경로는 받지 않는다.
7. **부르지 않는 학교 요청**: `online_view_navi`·`hisno`·`at`·`time`·`status`·`check`·`replace_*`, `online_learning*`, `accessone.hellolms.com`, `secondauth/*`, `activity_continue_update`, `notice_confirm`, 글·댓글·제출 쓰기와 지우기.
8. **공지**: 학교 공지 목록(`POST /ilos/cls/st/notice/notice_list.acl`, 50개씩 쪽을 이어 읽는다)은 목록 화면이라 학교 쪽 읽음을 바꾸지 않는다(목록을 다시 읽어도 안 읽음 표시 `.is_read.unread`는 그대로이고, 읽음은 상세 `notice_view_pop.acl`에서만 바뀐다). 로그인 때 가져오기(ADR-0042)가 과목마다 강의실 화면 다음에 읽고, 학생이 공지 목록을 열 때 `list-interval`이 지났으면 그 학생의 학교 session으로 다시 읽는다. 공지는 과목마다 `school_notices`(V44)에, 학생 본인의 안 읽음 표시는 `school_notice_states`에 둔다. 학교 목록에서 사라진 공지는 그 첨부와 함께 지운다. 학생의 `GET /api/courses/{courseId}/notices`에 서비스 공지 뒤로 `noticeId: "school-<id>"`로 붙는다(쪽과 total은 두 목록을 이은 것). 상세 `GET /api/courses/{courseId}/notices/school-<id>`는 그 학생의 학교 session으로 학교 상세와 첨부 목록을 읽는다(학교에서 그 학생이 읽은 것으로 바뀐다). "공지를 확인했습니다"(`notice_confirm.acl`)와 댓글은 보내지 않는다. 본문은 글자로 바꿔 주고 저장하지 않는다. 첨부는 자료 파일과 같은 학교 파일(유형 `notice`)이고 같은 경로로 받아 둔다. 파일모음 목록에 공지 첨부가 없으므로 그 목록으로 지우지 않는다. 안 읽은 공지 수(`unreadNotices`)에는 학생 본인의 학교 목록에 안 읽음으로 나오고 STack에서 열지 않은 공지 수를 더한다. 이 수를 위해 공지를 열지 않는다. `communication`은 `learning`에 의존하지 않으므로 `catalog.api.SchoolCourseNotices` port를 `learning`이 구현한다. 학교 화면의 게시일에는 연도가 없어 과목 키의 학년도로 정하고, 표의 칸은 이름이 아니라 날짜로 읽히는지와 위치로 읽는다(같은 과목도 영어로 그려진다).

## 결과
- 학교 로그인 학생은 STack 자료 목록에서 학교 자료와 파일을 보고, 자료 본문을 STack에서 읽고, 파일을 STack 미리보기·내려받기로 연다. 학교 서버는 처음 연 학생 한 명에게서만 파일 요청을 받는다.
- 서버를 다시 띄우거나 학교 session이 끝나면 학생은 다시 로그인해야 자료 본문과 아직 받지 않은 파일을 연다. 목록과 받아 둔 파일은 그대로 열린다.
- 받아 둔 파일은 저장소(SeaweedFS)를 쓴다. 과목마다 학교 파일모음 크기(관찰: 과목당 수십 개, 파일 27 KB~5 MB)만큼이다. 학교 목록에서 사라지면 지운다.
- `ppt`·`doc`·`xls`는 받아 두고 내려받을 수 있지만 미리보기 변환 대상이 아니다.

## 기각한 대안
- **학교 session을 DB에 암호화해 두기**: API가 한 대라 메모리로 충분하고, 키 관리와 유출면이 는다.
- **로그인 때 모든 파일을 받아 두기**: 학생이 열지 않은 항목의 학교 상태를 바꾸고 학교와 저장소에 부담이 된다.
- **학교 영상 주소로 STack에서 재생**: 주소를 얻는 요청이 학교의 2차 인증 관문 뒤에 있다. 재생해도 학교 출석·학습시간에 반영되지 않는다.
