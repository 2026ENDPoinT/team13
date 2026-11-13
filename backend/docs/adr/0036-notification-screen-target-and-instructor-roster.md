# ADR-0036 알림의 연결 대상은 화면이 그 대상을 여는 ID로 적고, 과목 없는 전체 알림은 사건 하나를 worker가 나눠 보내며, 담당자 수강생 명단은 화면 항목만 내보낸다

## 상태
채택 (2026-11-12)

## 맥락
프론트엔드 연결과 알림마당 공지에서 세 가지 빈틈이 나왔다.

- 과제 알림(`ASSIGNMENT_OPENED`, `ASSIGNMENT_GRADED`)은 대상 ID로 학습활동 ID를 적었다(ADR-0027). 과제 화면은 `/api/courses/{courseId}/assignments/{assignmentId}`로 열고, 학습활동 ID로 과제를 찾는 API는 없다. 알림을 눌러도 과제 화면으로 갈 수 없었다. 퀴즈와 설문도 화면이 퀴즈 ID, 설문 ID로 연다. 알림 목록에는 전체 수도 없었다.
- 교수자 수강생 화면(learner_list_form, 프론트엔드 `pf_monitor`)이 쓸 명단 API가 없었다. 출석 명단과 학습 현황은 다른 기능의 화면이다. 명단은 학번을 담으므로 누구에게 어디까지 보이는지 정해야 한다.
- 알림마당 전체 공지는 원본에서 모든 사용자에게 알림을 남긴다("[전체공지사항] 새로운 공지사항이 있습니다."). `catalog.api.Notifications`는 과목 단위(`publish`)나 1000명까지의 정한 사람(`send`)에게만 보낼 수 있었다. 기관 전체는 수천 명이고, 예약 게시도 있다.

## 결정
1. 알림의 대상 ID(`target_ref_id`)는 화면이 그 대상을 여는 경로의 ID다. 대상 종류(`NotificationKind.Target`)가 경로를 정한다. `ACTIVITY`는 학습활동(자료), `ASSIGNMENT`는 과제, `ARTICLE`은 게시글, `MESSAGE`는 쪽지다. 과제 알림의 대상은 과제 ID다. 알림을 만드는 쪽(`assessment.AssignmentNotifications`)이 같은 transaction에서 과제 ID를 적는다. `activity_id`는 `ACTIVITY` 대상에만 채운다. 이미 저장한 과제 알림과 알림 사건은 V36이 과제 ID로 옮긴다. 퀴즈와 설문이 알림을 만들게 되면 `QUIZ`, `SURVEY` 대상 종류를 더하고 퀴즈 ID, 설문 ID를 적는다.
2. 알림 목록은 `total`을 함께 돌려준다. 목록과 같은 보이는 조건(본인, 보존 기간, 지금도 구성원)과 같은 필터(과목, 유형, 안 읽음)이고, 커서와 관계없다. 목록 쿼리는 넷(시계, 쪽, 수, 안 읽은 수)이다.
3. 담당자 수강생 명단은 `GET /api/courses/{courseId}/students`다. 그 과목의 `INSTRUCTOR`(교수, 조교, 비정규 담당자)만 보고 그 밖은 404다. 명단은 `MembershipRule`이 STUDENT로 판정하는 사람이고, 항목은 화면이 보이는 이름, 학번, 수강 근거(학사 원장·비정규·청강)와 역할(학생·청강생)뿐이다. 학과, 학년, 연락처는 내보내지 않는다. 정렬 key는 `name`, `studentNumber` 두 값만 받는다.
4. 명단은 과목마다 수백 명이라 명단과 표시 정보(`identity.api.UserDirectory`)를 한 번씩 읽고 `catalog`에서 거르고 정렬해 쪽을 자른다. 쿼리는 명단 크기와 관계없이 셋이다.
   - (2026-11-12 갱신) 3번과 4번의 항목과 쿼리 수를 넓혔다. 항목마다 학습 열 두 개를 더한다. `lastAccessAt`은 그 과목에서 마지막으로 영상 학습을 시작한 일시(이어보기 포인터 `course_continue_pointers.recorded_at`, 없으면 null)이고, 강의실 입장이나 자료·과제 화면은 기록이 없어 치지 않는다. `progressPercent`는 학생 진도 화면과 같은 기준으로 학생에게 열린(공개됐고 시작한) 활동의 지우지 않은 영상 콘텐츠 가운데 출석인정을 채운 비율(0~100, 버림)이고 열린 콘텐츠가 없으면 null이다. 학습 기록은 `learning`이 가지고 `catalog`는 `learning`에 의존하지 않으므로 `catalog`가 `catalog.api.RosterLearning` port를 정의하고 `learning`이 구현한다(`learning.api.ActivityTargets`와 같은 의존 역전). `catalog`는 거르고 정렬해 자른 쪽의 학생 ID만 한 번 넘긴다. 명단 쿼리 셋(역할, 명단, 표시 정보)에 port 쪽 넷까지(DB 시각, 열린 콘텐츠 수, 사용자별 충족 수, 최근 학습. 열린 콘텐츠가 없으면 충족 수는 읽지 않는다)가 더해져 쪽 크기와 관계없이 일곱 이하다.
5. 과목 없는 전체 알림은 `Notifications.publishSite(SiteNotification(kind, audience, targetId, subject, visibleAt))`이고 유형은 `SITE_NOTICE`(대상 종류 `ARTICLE`, 과목 없음) 하나다. 대상은 `identity.api.UserAudiences.UserAudience`(`ALL`, `STUDENT`, `STAFF`)이고 저장 값은 알림마당 설문 대상(`survey_targets.target_type`)과 같은 `all`, `student`, `staff`다. 학생은 `user_role = STUDENT`, 교직원은 그 밖의 역할이며, 활동 중인 계정만 받고 break-glass 계정은 받지 않는다.
6. `publishSite`는 ADR-0027의 사건 모델을 쓴다. 만든 쪽 transaction(`Propagation.MANDATORY`)에는 `notification_events` 행 하나만 남는다(`course_id`, `audience_role` 없이 `site_audience`). 알림 행은 그 transaction에서 만들지 않는다. worker(`NotificationSweeper`)가 보일 시각 뒤에 사건을 `FOR UPDATE SKIP LOCKED`로 잡고 사용자 ID 순의 한 묶음(`astra.notification.site-batch`, 기본 500명)을 READ COMMITTED transaction 하나로 넣은 뒤 마지막 사용자 ID(`delivered_user_id`)를 남긴다. 묶음 사이에는 잠금을 놓아 다른 worker가 이어받을 수 있다. 묶음이 다 차지 않으면 보낸 것으로 표시한다. 받는 사람은 묶음을 읽는 때의 대상 사용자다.
7. 같은 (유형, 대상)으로 다시 부르면 이미 받은 사람에게 다시 만들지 않고 문장만 바꾼다. 대상이 바뀌거나 보이는 시각이 미래로 바뀌면 보낸 알림을 지우고 위치를 처음으로 돌린다. 거두기는 `withdraw(SITE_NOTICE, targetId)`이고 사건과 알림을 모두 지운다. 읽을 때의 조건은 다른 알림과 같다(본인, 보존 기간 60일). 과목이 없으므로 구성원 조건은 없다.

## 결과와 트레이드오프
- 학습활동 ID로 과제 ID를 찾는 조회 API를 두는 방식은 알림 행을 그대로 두지만, 화면이 알림을 누를 때마다 요청이 하나 늘고, 그 조회가 학습활동 ID만으로 과제의 존재를 묻는 새 경로가 된다. 알림 행을 만드는 쪽이 이미 두 ID를 모두 알고 있어 그쪽에 적었다.
- 과제 알림의 `activity_id`를 비우므로 알림에서 학습활동으로 가는 FK 연결은 자료 알림에만 남는다. 활동을 지우면 만든 쪽이 `withdraw`로 거두는 규칙(ADR-0027 5번)은 그대로다.
- 명단 검색과 정렬을 `identity`의 SQL로 내리면 수천 명 과목에서도 쪽만 읽지만, `identity.api`에 과목 관계를 모르는 범위 검색과 정렬 계약을 새로 더해야 한다. 과목 하나가 수백 명인 동안은 서버에서 거르는 쪽이 단순하다. 과목이 수천 명이 되면 `UserSearch`처럼 범위를 받는 정렬 검색으로 옮긴다.
- 학생은 어떤 경로로도 다른 학생의 학번을 받지 못한다. 학생이 보는 함께 듣는 사람(`CourseMates`), 그룹 구성원은 이름만이다.
- 전체 알림을 만든 쪽 transaction에서 바로 보내는 방식은 수천 행을 한 transaction에 넣어 공지 작성이 느려지고 잠금을 오래 쥔다. 사건만 남기면 공지 작성은 행 하나로 끝나고, 대신 알림이 다음 worker 주기(30초) 안에 나타난다.
- 묶음 위치를 사건 행에 두므로 worker가 묶음 중간에 멈춰도 다음 주기에 남은 위치부터 이어 보낸다. 이미 넣은 사람에게 다시 넣어도 (유형, 대상, 사용자) 유일 키가 막는다. 보내는 사이에 생긴 사용자는 ID가 위치보다 크면 받는다.
- 거두기는 그 대상의 알림 행 전체를 한 문장으로 지운다. 수천 행이면 공지 삭제 transaction이 그만큼 길어진다. 대상이 수만 명이 되면 거두기도 worker가 나눠 지우게 바꾼다.
- 학년 대상(`survey_targets`의 `grade`)은 알림마당 공지에 쓰임이 없어 두지 않았다. 필요하면 `UserAudience`에 값을 더하고 `identity`가 학년으로 거른다.
