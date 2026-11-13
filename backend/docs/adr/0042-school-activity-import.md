# ADR-0042 학번·학교 비밀번호 로그인 때 학교 강의실의 주차·학습활동 목록을 읽어 두고 강의실 주차 목록에 붙인다

## 상태
채택 (2026-11-14)

## 맥락
학번·학교 e-Class 비밀번호 로그인(`POST /api/auth/eclass-login`)은 학교 메인 화면의 수강과목 카드로 과목과 수강을 만든다(`catalog.SchoolCourseSync`). 과목 안의 내용은 옮기지 않아, 운영 서비스에서 학생이 과목을 열면 "등록된 활동이 없습니다"만 보인다. 소유자는 강의실에 학교의 실제 내용을 보이라고 했다.

제약:

- 학교에는 공개 API가 없다. 학생 본인의 학교 session으로 브라우저와 같은 화면을 읽는 수밖에 없다.
- 비밀번호와 학교 session 쿠키는 저장하지 않는다(SC-07). 학교 session은 로그인 요청 안에만 있다.
- 학교 강의실은 session에 과목 하나를 묶는 구조다(`POST /ilos/cls/st/co/eclass_room2.acl` 뒤 `GET /ilos/cls/st/submain/submain_form.acl`이 그 과목을 그린다). 과목마다 차례로 읽어야 한다.
- 로그인은 가져오기 때문에 실패하거나 오래 걸리면 안 된다. 학교 서버에 부담을 주지 않는다.

## 결정
1. **언제**: 로그인 transaction이 commit되고 `catalog`가 과목과 수강을 맞춘 뒤(같은 사건 `SchoolLoginSucceeded`, listener 순서 `CATALOG_ORDER` → `LEARNING_ORDER`), `learning.SchoolActivityImport`가 그 학생이 학생으로 수강하는 과목 가운데 학교 메인 화면에 있던 과목을 화면 순서대로 읽는다. 로그인 응답은 `astra.school-activities.wait`(기본 3초)까지만 기다리고, 나머지는 같은 component의 thread(동시 `concurrency`, 기본 4개. 넘치면 그 로그인은 가져오지 않는다)가 이어 간다. 전체는 `budget`(기본 60초), 요청 하나는 `request-timeout`(기본 8초), 과목은 `max-courses`(기본 20개)까지다. 같은 학생이 `interval`(기본 10분) 안에 다시 로그인하면 읽지 않는다(`school_activity_imports`, 실패한 가져오기도 센다). 과목 하나가 실패하면 과목 ID와 이유 코드만 로그에 남기고 다음 과목으로 간다.
2. **학교 session의 수명**: `identity.api.SchoolCourseRooms`가 확인(`SchoolPasswordVerifier.verify`)으로 열린 학교 session의 손잡이다. cookie는 그 객체(`integration.eclass.EclassPasswordVerifier`의 `Rooms`/`Session`) 메모리에만 있다. 쓰려는 쪽이 `claim()`으로 가져가고 다 쓰면 `close()`로 cookie를 버린다. 아무도 가져가지 않았으면(로그인 거절, 가져오기 꺼짐, 과목 없음, 동시 실행 초과) 로그인 컨트롤러가 응답 전에 버린다. 가져오기는 끝나면(실패해도) 버린다. session, cookie, 비밀번호는 행, 로그, 예외 message, 사건의 `toString`에 들어가지 않는다. 다음 단계(과목 내용을 필요할 때 읽기)는 이 손잡이를 버리지 않고 이어 받는 자리다.
3. **무엇을 읽나**: 과목마다 강의실 첫 화면 하나. 주차(`#week_list_wrap_<n>.week_list_container`, 비정규는 회차)와 기간(`.week_period`, 연도가 없어 과목 키의 학년도로 정하고 해를 넘기면 다음 해), 활동 행(`a.week_activity`의 `activity_form.acl?m=<메뉴>&n=<번호>`, 제목, 세부 유형 class `report1`·`test2` 등, 정보 칸 출석·학습시간·시작·마감, 오프라인 표시, 완료 체크). 링크·번호·제목이 없거나 이상한 행은 버리고, 알아보지 못한 정보 칸은 비워 둔다. 화면을 알아보지 못하거나 다른 과목의 화면(`#KJ_KEY`가 다름)이면 그 과목만 실패다. 학교는 같은 session에서도 과목에 따라 화면을 영어로 그린다(2026-11 관찰: Attendance, Studying Time, Start, Deadline, Expired, "Wed, November 18 11:59 PM", "September 9 ~ September 15"). 두 언어의 칸 이름과 날짜 모양을 모두 읽는다. 해석은 `integration.eclass.CourseRoomPage`다.
4. **무엇을 두나**(V42): `school_course_weeks`(과목, 주차, 기간), `school_activities`(과목, 학교 메뉴 ID, 학교 활동 번호로 유일. 세부 유형, 주차, 화면 순서, 제목, 시작·마감, 만료 표시, 출석 표시, 학습시간, 오프라인 표시, 학교에서 여는 주소), `school_activity_states`(활동과 학생마다 완료 표시), `school_activity_imports`(학생마다 마지막 가져오기). 과목 하나를 읽으면 그 과목만 한 transaction으로 맞춘다. 행은 학교 활동 번호로 고치거나 넣는다. 화면에 활동이 하나라도 있을 때만 화면에서 사라진 주차와 활동(그 활동의 모든 학생 상태 포함)을 지운다. 빈 화면은 학교 쪽 장애일 수 있어 아무것도 지우지 않는다. 학교 화면이 원천인 사본이라 삭제 표시로 남기지 않는다.
5. **무엇을 두지 않나**: 파일 바이트, 영상, 과제 제출물, 활동 본문·설명, 학교 공지·게시판·출석부·성적, 안읽음 표시, 강의계획서, 오른쪽의 할 일·최근 글. 활동을 열면 학교 e-Class로 간다. 학교가 홈의 할 일에서 쓰는 연결 주소 `/ilos/mp/todo_list_connect.acl?SEQ=<번호>&gubun=<메뉴>&KJKEY=<과목 키>`가 과목을 묶고 그 활동으로 넘긴다. 브라우저에 학교 로그인이 없으면 학교가 "과목을 선택하세요."를 띄우고 학교 첫 화면으로 보낸다.
6. **어떻게 보이나**: 강의실 주차 목록(`GET /api/courses/{courseId}/weeks`)에만 붙인다. 같은 주차의 서비스 활동 뒤에 학교 화면 순서로, 학교에만 있는 주차는 주차 번호 순서로 끼운다. 두 쪽에 다 있는 주차는 서비스 주차의 제목·기간을 쓰고 비어 있으면 학교 기간을 쓴다. 학교 행은 `activityId: "school-<번호>"`(서비스 활동의 숫자 ID와 겹치지 않는다), `origin: "school"`, 화면 유형 `type`(`video`·`material`·`report`·`test`·`survey`·`zoom`·`post`), `schoolType`, `schoolKind`, `title`, `openAt`, `closeAt`, `published: true`, `phase`(만료 표시나 마감이 지나면 `closed`, 시작 전이면 `not_open`, 아니면 `open`), `done`(요청한 학생 본인의 학교 완료 표시. 그 학생의 가져오기가 본 적 없으면 null), `attendance`, `learningTimeSec`, `offline`, `schoolUrl`이다. `type`을 학교 메뉴 ID가 아니라 화면 유형으로 둔 것은 지금 프론트엔드가 메뉴 ID(`lecture_weeks` 등)를 받으면 서비스 상세로 가는 깨진 링크를 만들거나 행을 버리기 때문이다. 화면 유형이면 고치지 않은 프론트엔드도 "열 수 없음" 행으로 이름·색·유형 칩을 맞게 보인다. 학교 행은 학생에게만 붙인다. 담당자의 주차 목록에는 없다(공개·삭제할 수 있는 서비스 활동이 아니다). 과목 권한(구성원이 아니면 404)은 그대로다. 메인 화면 과목 카드의 영상 진도율(`progressPercent`)은 학교 영상 활동을 하나씩 세고 학교 완료 표시를 채운 것으로 본다. 할 일, 캘린더, 유형별 목록, 활동 상세, 파일모음에는 붙이지 않는다.

## 결과
- 학교 로그인 학생은 로그인한 뒤 강의실에서 학교의 주차와 활동 목록, 본인 완료 표시를 본다. 내용은 학교 e-Class에서 연다.
- 목록은 그 학생이 로그인할 때만 새로 고쳐진다. 학교에서 바뀐 것은 다음 로그인(그리고 `interval` 뒤)까지 늦다. 같은 과목의 다른 학생이 로그인하면 공통 목록은 그때 고쳐지지만 본인 완료 표시는 본인 로그인 때만 바뀐다.
- 학교 화면 구조가 바뀌면 읽은 행이 줄거나 과목이 실패로 남는다. 로그인은 그대로 된다. 실제 계정으로 확인한 구조(2026-11)를 합성 fixture(`src/test/resources/com/stackcorp/astra/integration/eclass/room-*.html`)로 둔다.

## 기각한 대안
- **학교 session이나 비밀번호를 저장해 주기적으로 읽기**: SC-07과 맞지 않는다.
- **로그인이 가져오기를 끝까지 기다리기**: 과목이 많거나 학교가 느리면 로그인이 수십 초 걸린다.
- **`learning_activities`에 학교 행 넣기**: 담당자 편집, 공개, 진도, 평가 흐름이 모두 그 테이블을 보므로 학교 사본이 서비스 활동처럼 다뤄진다. 학교 활동 번호로 맞추고 지우는 규칙도 서비스 활동과 다르다.
- **활동 목록 화면(`activity_form.acl`)이나 할 일 목록 읽기**: 강의실 첫 화면 하나가 주차, 기간, 모든 유형, 완료 체크를 한 번에 준다.
