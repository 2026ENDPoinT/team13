# ADR-0014 온라인 강의 출석 인정은 서버 시각의 적립 원장으로 하고, 사용자당 활성 학습 session은 하나다

## 상태
채택 (2026-11-11)

## 맥락
원본 ASTRA의 온라인 강의 출석은 client가 보내는 값에 기대고 있다. 하트비트(EP-0050)는 `INTERVAL_TIME`을 직접 보내고, 시작 즉시 한 번 보내며, 순번이 없다. 이력 번호(EP-0053)는 새로고침마다 새로 발급된다. 중복 시청 확인(accessone)은 500ms 시간 초과와 오류에서 통과한다. 재생 위치(EP-0058)는 이력과 묶이지 않는다([30_learning.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/30_learning.md) 3.3절, SEC-05, SEC-11).

서버 계약은 출석 시간을 서버 이벤트와 정책으로 인정하고 seek 위치, 하트비트 횟수, browser 경과 시간만으로 확정하지 않도록 정했다(SC-06, CHG-05). 중복, 역순, 긴 단절, 동시 재생을 처리하는 누적 규칙이 필요하다. 스키마(V1)에는 `learning_sessions`(사용자당 활성 1개 부분 유일 제약), `learning_heartbeats`(session·순번 유일), `learning_credit_ledger`, `online_lecture_item_progress`, `attendance_records`가 이미 있다.

## 결정
1. 학습 session은 `POST /api/courses/{courseId}/lecture-items/{itemId}/learning-sessions`로 시작한다. 권한은 활동을 지금 볼 수 있는지다(담당자는 언제나, 학생은 공개됐고 open_at이 지난 활동). 응답은 session ID, 서버가 정한 하트비트 주기, lease, 이어보기 위치, 적립이 끝나는 시각(close_at), 영상 cookie다. 기존 `POST …/playback`은 적립 없는 재생 허가로 남긴다.
2. 동시 session 정책은 **대체**다. 같은 사용자가 새 session을 시작하면 활성 session을 `superseded`로 닫는다. 콘텐츠가 달라도 그렇다. 닫힌 session의 하트비트는 409 `session_superseded`이고 아무것도 적립하지 않는다. 다른 사유로 닫힌 session의 하트비트도 409이고 저장한 종료 사유마다 code가 하나다: lease 만료는 `session_expired`, 종료 요청(button, unload)은 `session_ended`, 볼 수 없게 되어 서버가 닫은 것(forced)은 `session_withdrawn`이다. 대체는 어느 창이나 기기에서 이어 갔는지 알리지 않는다. `active_user_key` 유일 제약이 동시 시작을 직렬화하고, 유일 제약이나 교착으로 진 쪽은 transaction을 다시 실행해 이긴 쪽을 대체한다.
   - 기각: 새 session을 거부(409). 창이 죽거나 network가 끊기면 lease가 끝날 때까지 다시 시작하지 못한다. 원본의 `force=Y`(기존 시청 종료)와도 맞지 않는다.
   - 기각: (사용자, 콘텐츠)마다 하나. 두 강의를 동시에 틀어 같은 벽시계 시간을 두 번 적립할 수 있다.
3. 적립은 서버 시계로만 정한다. 시계는 open_at, close_at과 같은 DB 시계(`CURRENT_TIMESTAMP(3)`)이고 session 행을 `FOR UPDATE`로 잠근 뒤에 읽는다. 규칙 v1(`CreditRule`):
   - 구간은 session의 기준 시각부터 지금까지다. 기준 시각은 시작 시각, 이후로는 마지막으로 받아들인 하트비트가 정한다. 첫 하트비트는 거의 0초다.
   - 주기의 1/2이 지나기 전 하트비트는 아무것도 바꾸지 않는다(burst). 그 시간은 다음 하트비트가 적립한다.
   - 주기의 2배보다 긴 공백은 적립하지 않고 기준 시각만 옮긴다. 그동안 시청했다는 근거가 없다.
   - 순번(`seq`)이 이미 받은 값 이하면 재전송이나 역순이다. 기록도 적립도 하지 않는다.
   - 구간 시작은 이 사용자의 모든 session이 적립한 구간의 끝(frontier) 뒤로 민다. 적립은 사용자 단위 구간의 합집합이라 두 기기, 두 session이 같은 시간을 두 번 받지 못한다(SEC-05).
   - 구간은 [open_at, close_at)으로 자른다. close_at 뒤에는 재생은 계속할 수 있지만 적립하지 않는다(`window_closed`). 기간외 적립(`credited_out_sec`, `in_period_yn=0`)은 v1에서 만들지 않는다.
   - 원장은 초 단위이고 1초 미만은 기준 시각에 남겨 다음 구간이 적립한다.
   - client의 경과 시간, 주기는 받지 않는다(문서에 없는 필드라 400). 재생 위치는 `0 ≤ position ≤ duration_sec`만 받고 이어보기 힌트로 저장한다. `playing`은 기록만 한다.
4. 하트비트마다 과목 관계, 공개, open_at을 다시 판정한다. 볼 수 없게 되면(비공개, 수강 취소) session을 `ended`(`forced`)로 닫고 404로 응답한다. 이미 적립한 시간은 그대로 둔다. 로그인 session이 폐기되면 401이다. 볼 수 있는 동안에만 응답마다 영상 cookie를 다시 발급한다.
5. 기준 시각에서 lease(주기의 3배) 안에 하트비트가 없으면 session은 `lease_expired`다. worker가 후보를 잠그지 않고 읽은 뒤 session마다 `state = 'active'` 조건부 UPDATE로 따로 닫는다. worker가 둘이어도 session마다 한 번만 닫히고, 한 번에 행 하나만 잠가 교착하지 않는다. 하트비트가 먼저 오면 그 자리에서 닫는다. 종료 시각은 마지막 기준 시각이고 그 뒤는 적립하지 않는다.
6. `POST …/learning-sessions/{id}/end`는 멱등이다. 마지막 기준 시각 뒤 구간을 같은 규칙(최소 간격만 빼고)으로 적립하고 닫는다. 이미 닫힌 session이면 그 상태를 그대로 돌려준다. 페이지를 떠날 때는 CSRF header를 실을 수 있는 `fetch(…, {keepalive: true})`로 보낸다. 도착하지 않으면 lease가 닫는다.
7. 출석 인정: 출석인정 필요 시간은 `required_sec`, 없으면 `duration_sec`(콘텐츠 길이의 100%)다. 둘 다 없으면 `unknown`이고 판정하지 않는다. 기간 안 적립(`credited_in_sec`)이 필요 시간에 닿으면 `online_lecture_item_progress.met_at`(V5)에 서버 시각을 `met_at IS NULL` 조건부 UPDATE로 한 번 기록한다. 활동의 모든 콘텐츠가 충족되면 `activity_progress.completed_at`을 한 번 쓰고, 활동의 대상 출석 차시(직접 연결한 `attendance_session_id`, 없으면 주차의 유일한 온라인 차시, [ADR-0030](0030-attendance-and-smart-rounds.md) 8번)에 걸린 활동을 모두 충족했으면 `attendance_records`에 `출석`(source `online`, 그 session의 규칙 버전)을 넣는다. 행이 이미 있으면 `미처리`일 때만 바꾼다. 담당자가 정한 상태와 이미 기록한 충족은 이후 어떤 사건으로도 되돌리지 않는다.
8. 진도 조회는 `GET /api/courses/{courseId}/lecture-progress`(본인, 주차별)와 `GET …/lecture-progress/students`(담당자, 수강생 전체)다. 조회는 아무것도 바꾸지 않는다. 학습 시작이 열람 기록과 이어보기 포인터를 남긴다.

## 결과와 트레이드오프
- 적립은 "활성 session이 열려 있고 하트비트가 제때 온 벽시계 시간"이다. 사람이 실제로 봤는지는 증명하지 못한다. 재생을 멈춘 채 창을 열어 두면 원본처럼 적립된다. 배속 재생은 벽시계 기준이라 시간을 줄여 주지 않는다.
- 배경 탭의 timer 지연으로 하트비트가 주기의 2배를 넘기면 그 구간을 잃는다. 기본 주기 60초에서 한 번 놓치면 최대 2분이다. 허용 계수와 주기는 기관 승인 정책으로 고정해야 한다(CHG-05).
- 사용자당 하나라 강의 두 개를 동시에 틀 수 없다. 다른 창에서 시작하면 앞 창의 적립이 끝난다는 것을 화면이 알려야 한다(`session_superseded`).
- 학습 시계는 DB 시계라 api가 여러 대여도 같다. 테스트는 시계 bean을 바꿔 시간을 움직인다.
- 미결: 지각·결석 판정(기간 안 일부 학습을 지각으로 볼지, close_at 뒤 결석 확정)은 정하지 않았다. 출석 기록은 충족했을 때만 생긴다.
- SC-06-A(2차 인증 만료와 적립)는 [ADR-0029](0029-step-up-and-learning-auth.md)에서 정했다. 학습 인증을 요구하는 배포에서 시작한 session은 `credit_authorized_until`을 저장하고 grant 경계에서 구간을 자르며 `reauth_required`를 거쳐 소급 없이 이어 간다. 그 session의 규칙 버전은 v2다.
