# Handoff

## 2026-11-14 활동 상세 왼쪽에 학습활동 목록 패널(원본 e-Class처럼 아이콘과 함께)

### 작업 내용
- 요청: 자료·영상 등 활동 상세 화면에서 원본 e-Class처럼 왼쪽 패널에 그 과목의 활동 목록이 종류 아이콘과 함께 보이게.
- 새 `src/shared/activity-rail.tsx`(`ActivityShell`, `ActivityRail`)와 `activity-rail.css`. 상세 옆에 붙박이 패널(1024px 이상, 그 아래는 "학습활동 목록" 접는 줄): 종류 칩(영상·자료·과제·시험·설문)과 진행중·미제출 칩, 왼쪽 주차 번호(누르면 그 주로 이동), 주차별 행(종류 아이콘·종류 이름·완료 ✓·제목·상태), 지금 보는 활동 강조와 처음 그릴 때 그 위치로 스크롤. 학교 e-Class 행은 새 탭 링크로 남는다. 거른 상태는 같은 과목에서 다른 활동으로 옮겨도 유지한다.
- 상세 5곳(자료·학교 자료, 영상 상세, 과제, 시험, 설문)의 바깥틀을 `ActivityShell`로 바꿨다(`learning/activity-server.tsx`·`lectures-server.tsx`·`assignments-server.tsx`·`quizzes-server.tsx`·`surveys-server.tsx`).
- 시험 상세 "내 응시" 행(`AttemptRow`)이 아이콘 칸 없는 2칸인데 `.file-row`가 3칸 그리드라 글이 24px 칸에 눌려 글자가 세로로 깨지던 것을 `backend-ui.css`의 `.srv-quiz-attempts .file-row` 칸 수로 고쳤다.
- 강의실·목록이 쓰던 상태 글·기간·주 범위·구독 훅을 `src/shared/activity-state.ts`로 옮겨 두 곳이 같이 쓴다(`course/activities-server.tsx`는 거기서 가져온다).

### 변경 파일
- `src/shared/activity-rail.tsx`(새), `src/shared/activity-rail.css`(새), `src/shared/activity-state.ts`(새), `src/app.css`
- `src/areas/course/activities-server.tsx`, `src/areas/learning/{activity,lectures,assignments,quizzes,surveys}-server.tsx`

### 검증
- `npm run typecheck` 통과, `npm test` 통과.
- 백엔드(8080) + 개발자 로그인(Origin을 4190으로 바꿔 주는 임시 프록시 47196 경유, `npm run dev -- --port 47193`)으로 확인: 영상·과제 상세에 패널, 종류 칩으로 거르기, 다른 활동 눌러 이동·강조 이동, 760px에서 접는 줄.
- 확인 못 함: 샘플(목업) 모드 상세(`learning.tsx`)는 이 패널이 없다. 시험 응시 화면·학교 e-Class 자료 상세·어두운 테마는 화면으로 보지 않았다.

### 남은 작업
- 샘플 모드 활동 상세에도 같은 패널을 넣을지 결정.

## 2026-11-14 학교 e-Class 공지: 목록·상세(연 공지만 학교에서 읽기), 첨부, 읽음·확인 안 보냄

### 작업 내용
- 백엔드(`STAC-000-school-content` 440aee5, §5b)는 학생의 `GET …/notices`에 학교 공지를 `noticeId: "school-<n>"`, `origin: "school"`, `expired`, `schoolUrl`로 붙이고, `GET …/notices/school-<n>`이 열 때만 학교에서 읽는다(학교에서 읽음 처리). 학교 공지에는 `read`·`confirm`이 없다.
- 목록: 학교 공지에 "학교 e-Class", 기간 지난 공지에 "기간 지남". 조회 수가 없으면 칸을 그리지 않는다. 링크는 `id=school-<n>` 그대로.
- 상세 창: 열 때 한 번만 읽고(포커스 새로고침 없음), 텍스트 본문·작성자·게시일·첨부(미리보기·내려받기, `shared/school-files.tsx`의 `SchoolFileRow`, 자료 상세와 같이 씀), "e-Class에서 열기"와 닫기. 확인 버튼 없음. 연 뒤 받아 둔 목록에서도 읽음으로 바꾼다(요청 없음).
- 오류: 409 `school_signin_required` 다시 로그인, 502 다시 시도, 404는 지금처럼 "찾을 수 없음".
- `CourseNotice.noticeId`는 `number | string`. `markNoticeRead`·`confirmNotice`는 학교 ID에 요청을 만들지 않는다. 교수 공지 목록은 `isNativeNotice`로 STack 공지만 쓴다. #25의 `followNoticeRead`는 숫자 ID일 때만 부른다.

### 변경 파일
- `src/shared/notices-remote.ts`, `src/areas/course/notices-server.tsx`, `src/areas/professor/notices-server.tsx`, `src/shared/school-files.tsx`(새), `src/shared/school-content.tsx`(`schoolFilesOf` 인자), `src/areas/learning/activity-server.tsx`(`SchoolFileRow` 옮김), `tests/integration/school-notices.test.ts`(새)

### 검증
- `origin/main`(8da31b8) 위에서 `npm run typecheck` 오류 없음, `npm test` 33파일 1452개 통과(새 5개, 기존 테스트 수정 없음), `npm run build` 성공.
- 실제 실행(로컬 백엔드 440aee5 `bootTestRun` 18480/18481, `course_code` NULL 처리, 프론트 `http://localhost:4193`, 본인 계정 학교 로그인 1번): 과목 10개(안 읽은 공지 0). 한 과목 공지 목록 1행이 학교 공지(표시·`id=school-<n>` 링크, "undefined" 없음). 이미 읽은 그 공지 하나를 열어 작성자·게시일·조회·첨부 1개, 본문 2문단, e-Class 링크(`_blank`, `noopener noreferrer`), 닫기, 확인 버튼 없음. 백엔드 요청 기록: 목록 GET 3번, 상세 GET 1번(연 것), `POST …/read`·`…/confirm` 0번.

### 남은 작업
- 학교 공지 첨부의 미리보기·내려받기는 실제로 눌러 보지 않았다(자료와 같은 줄, #24에서 확인). 409 `school_signin_required`는 Vitest로만 확인.

## 2026-11-14 공지를 읽으면 왼쪽 메뉴 "공지사항" 수가 새로 고침 없이 줄어든다

### 작업 내용
- 원인: 왼쪽 메뉴 수(`shell.tsx:62`)는 `GET /api/me/courses`의 `unreadNotices`를 읽고, 이 목록은 접속할 때(`backend.ts` `load`) 한 번만 받는다. 공지를 열어 `markNoticeRead`(`notices-remote.ts:90-102`)가 `POST …/read`를 보내도 공지 목록·상세 캐시만 고치고 과목 목록은 그대로였다.
- `backend.ts` `refreshCourses()`: `GET /api/me/courses`를 다시 받아 과목 기록을 바꾸고 구독자를 다시 그린다(`ready`일 때만, 그 사이 세션이 바뀌었으면 버린다). 폴링은 없다. 읽음 한 번에 한 번 받는다.
- `home-remote.ts` `followNoticeRead()`: 읽은 공지를 홈 집계 `notices`에서 읽음으로 바꾸고, 다시 받은 과목 기록의 `unreadNotices`를 홈 집계 `courses`에 옮긴다(`applyCourseCounts`, 교수 공지 화면의 과목 카드가 읽는다). `markNoticeRead`가 읽음 응답 뒤에 부른다.
- `notices-remote.ts`: 읽음 한 번 보내기 기록(`readSent`)은 이제 세션(상태·사용자)이 바뀔 때만 지운다. 이전에는 백엔드 상태가 바뀔 때마다 지워 과목 기록을 다시 받는 것만으로도 지워졌다.
- 알림마당 공지(홈 `communityNotices`): 수를 보이는 곳이 없고, 읽으면 `site-notices-remote.ts` `rememberRead`가 이미 홈 집계의 행을 읽음으로 바꾼다. 고칠 것이 없었다.

### 변경 파일
- `src/shared/backend.ts`, `src/shared/home-remote.ts`, `src/shared/notices-remote.ts`
- 테스트 `tests/integration/notice-read-counts.test.ts`(새)

### 검증
- `npm run typecheck` 통과, `npm test` 통과, `npm run build` 통과(청크 크기 경고만).
- 백엔드 origin/main 5f1dff0 run-only + 프런트 4193: 교수 P-1042로 `bi`에 공지 2개 등록, 학생 21101234의 공지사항 화면 왼쪽 메뉴 "공지사항 5" → "감사 공지 1" 열기 → `POST …/notices/22/read` 뒤 `GET /api/me/courses` 한 번 → "공지사항 4", 두 번째 공지 → "공지사항 3"(새로 고침 없음).
- 학생 홈의 과목 카드에는 안 읽은 공지 수가 없다. 시드에 알림마당 공지가 없어 홈 알림마당 줄은 브라우저로 보지 못했다.

### 남은 작업
- 없음.

## 2026-11-14 목업: "오늘 할 일 전부 해줘" → 기계학습·고급웹 Plan 자동 시작

### 작업 내용
- 요청: 목업 상태에서 "오늘 할 일 전부 해줘"를 입력하면 기계학습 → 고급웹프로그래밍 순으로 Plan 스택에 쌓고 자동으로 진행.
- 전에는 `/api/assistant`(AI)를 거쳐 키가 없으면 실패했고, 계획이 나와도 Accept를 눌러야 했다. 이제 `command-bar.tsx`의 `submitCommand`(백엔드 미연결 분기)에서 `isTodayAllRequest(text)`(`aliases.ts`)가 맞으면 AI를 부르지 않고 `todayDeadlinePlan()`(`plan.ts`)으로 `createRun` → `startRun`을 바로 호출한다. 열린 계획 확인(Accept) 단계는 없고 트레이가 열린다.
- "오늘 할 일" = 홈 히어로가 세는 사흘 안 마감 활동(`myDayGroups().soon`, 시험·완료 제외). 샘플에서는 `lec-11-1`(기계학습 11주 1차시) → `rep-web`(고급웹 과제). 영상 → 과제 정렬(`orderPlan`)이라 이 순서가 나온다. 영상은 끝나면 다음 단계로 자동 이동하고, 과제는 "확인 필요"(제출은 직접).
- 과목·유형이 붙은 문장("데베 강의 전부 해줘" 등), "전부 해줘"만 있는 문장은 이 지름길에 걸리지 않고 기존 AI/해석 경로를 탄다. 처리할 일이 없으면 "오늘 처리할 일이 없어요."를 보인다.

### 변경 파일
- `src/shared/assistant/aliases.ts`(`isTodayAllRequest`), `src/shared/assistant/plan.ts`(`todayDeadlinePlan`), `src/areas/home/command-bar.tsx`
- 테스트 `tests/unit/today-all-request.test.ts`(새)

### 검증
- `npm run typecheck` 통과. 관련 테스트 4파일 87건 통과.
- AI 키·게이트웨이 없이 `node server.mjs --dev --port 4391`로 브라우저 확인: 입력 → `/api/assistant` 호출 0회, 기계학습 플레이어로 이동, 트레이 `Plan 0/2 재생 중`(영상 기계학습 → 과제 고급웹프로그래밍). 2x로 영상을 끝내자 고급웹 과제 화면으로 자동 이동, `Plan 1/2`·과제 "확인 필요", 콘솔 오류 없음.

### 남은 작업
- 백엔드 연결(로그인) 홈은 이 지름길을 쓰지 않는다(자동 계획 미지원 정책 유지).
- 다른 날짜로 샘플 데이터(`TODAY`)가 바뀌면 "오늘 할 일" 구성도 바뀌므로 `today-all-request.test.ts`의 기대값(`lec-11-1`, `rep-web`)을 같이 봐야 한다.

## 2026-11-14 녹음 중 화면 하단 실시간 전사

### 작업 내용
- 요청: 녹음 중 화면(`LiveView`) 하단에 전사가 쭉 이어져 보이게.
- 백엔드에 실시간 전사 API가 없어(README 계약은 저장 후 결과만), 브라우저 음성 인식(Web Speech API, `ko-KR`)으로 화면에만 표시한다. 새 `src/shared/live-transcript.ts`가 인식기를 켜고(침묵으로 끝나면 300ms 뒤 다시 켬), 확정 문장은 녹음 시각과 함께 줄로 쌓고 말하는 중인 문장은 흐리게 보인다. 일시정지에서 끄고 다시 시작에서 켜며, 저장·중지에서 줄을 지운다(`course-recordings.ts`의 `startRecording`·`pauseRecording`·`resumeRecording`·`finish`).
- 화면(최종): 녹음을 누르는 순간부터 저장된 녹음 화면과 같은 배치(`.rs`)로 열린다. 머리에 녹음 제목(`녹음 MM.DD HH:mm`)·`마이크 · 시간 · 날짜`·(저장 전이라 꺼진) 다운로드 버튼, 왼쪽 위 녹음 카드(플레이어 카드와 같은 모양: 상태, 꺼진 재생 막대와 현재 길이, 큰 일시정지/다시 시작 버튼, `중지하고 저장`), 그 아래 전사 목록(`rs-lines`; 맨 아래면 새 줄을 따라가고 위로 올려 읽으면 "최신 전사" 버튼, 말하는 중인 문장은 강조 줄), 오른쪽은 요약 자리(저장 뒤 서버가 만든 경우에만 표시)와 안내 문구. 로그인 전(샘플)·로그인 후(서버) 같은 `LiveView`를 쓴다. 탭 소리 녹음·미지원 브라우저·권한 거부/네트워크 오류는 전사 자리에 이유를 안내하고 녹음은 계속된다. `ActiveRecording`에 `title`·`startedAt` 추가.
- 한계: 저장되지 않는다(저장된 녹음의 전사는 여전히 서버가 만든 결과). Chrome·Edge는 음성을 브라우저 제공사 서버로 보내 인식하며 이를 화면에 안내했다. Firefox·Safari 일부는 미지원.

### 변경 파일
- `src/shared/live-transcript.ts`(새), `src/shared/course-recordings.ts`, `src/areas/home/recording.tsx`, `src/areas/home/home.css`
- 테스트 `tests/unit/live-transcript.test.ts`(새), `vitest.config.ts`(coverage include)

### 검증
- `npm run typecheck` 통과, `npm test` 29파일 1421건 통과.
- 로그인 후(서버 모드): 백엔드(8080, Origin 허용이 4190뿐이라 Origin을 바꿔 주는 임시 프록시 경유) + 개발자 로그인으로 같은 화면, 녹음 → 중지하고 저장 → 서버 저장 뒤 녹음 파일 화면 이동 확인(임시 프록시·서버는 지움, 개발 DB에 시험 녹음 1건 남음).
- Chromium에서 마이크 스트림과 인식기를 가짜로 바꿔 확인: 녹음 시작 시 상자 표시, 확정 줄·진행 중 문장 표시, 15줄 뒤 맨 아래로 자동 스크롤, 중지·저장 뒤 상자 사라짐, 콘솔 오류 없음.

### 남은 작업
- 실제 마이크·실제 Chrome 음성 인식 정확도와 일시정지·다시 시작은 브라우저에서 확인하지 못했다(일시정지·재시작은 단위 테스트만).
- 서버 저장용 전사가 필요하면 백엔드가 스트리밍/업로드 후 전사 API를 제공해야 한다.

## 2026-11-14 학교 e-Class 자료를 STack 자료 상세로: 목록·상세·미리보기·내려받기와 학교 오류 상태

### 작업 내용
- 백엔드(`STAC-000-school-content`, 1c81144)는 학생의 `GET …/materials`에 학교 자료를 `origin: "school"`, 문자열 `activityId`(`school-<n>`), 복사 전 문자열 `fileId`(`school-file-<n>`)로 붙이고, `GET …/activities/school-<n>`이 열 때만 학교 상세를 읽는다. 파일 내용·미리보기 주소는 문자열 ID를 받아 303/307로 네이티브 파일 주소로 보낸다.
- 한 자료 한 행: 주차 목록의 학교 행(본인 `done`·`phase`, 자리)이 이기고, 자료 목록은 그 행에 파일을 붙인다. 주차 목록에 없는 학교 자료만 자료 목록에서 행이 된다. 링크는 어느 쪽이든 STack 자료 상세 `m=lecture_material&n=school-<n>`(같은 창). 다른 학교 행(과제·시험 등)은 #19처럼 e-Class 새 탭.
- 자료 상세(`n=school-<n>`): 본문은 텍스트(`Prose`), 작성자·게시일, 파일마다 미리보기(`preview_form.acl?n=school-file-<n>`, 복사 뒤 숫자면 `srv-<id>`)·내려받기, "e-Class에서 열기". 학습실·요약·학습실에 저장은 학교 파일에 두지 않는다. 상세는 주소에 들어올 때만 한 번 읽고(포커스 새로고침 없음), 목록·파일모음은 상세·썸네일·미리보기를 부르지 않는다.
- 오류: 409 `school_signin_required` → "학번·e-Class 비밀번호로 다시 로그인"이 로그인 모달을 이 화면 위에 열고(닫기 가능), 로그인되면 같은 자료를 다시 읽는다(`requestSchoolSignin`). 409 `file_scanning` → `Retry-After`(1~30초)만큼 기다렸다 다시, 최대 12번(`whileScanning`, 내려받기·미리보기). 422 → 사유와 e-Class 링크. 502 → 다시 시도.
- 파일모음(46): 서버 파일 아래에 "학교 e-Class 자료" 묶음(같은 검색어로 거름, 이미 서버 목록에 나온 복사본은 빼고, 크기는 "약").
- 문자열 ID 경로: 화면 필드는 `serverSchoolMaterial`(문자열)로 따로 두고 `serverMaterial`(숫자)은 그대로. `materialsOf`는 네이티브만 준다(교수 강의실·학습실 기록·`listRemotePdfs`는 학교 자료를 보지 않는다). `schoolHref`·`SCHOOL_ORIGIN`은 `shared/school-content.tsx`로 옮겼다.

### 변경 파일
- 새 파일: `src/shared/school-content.tsx`, `tests/integration/school-materials.test.ts`
- `src/shared/course-activities-remote.ts`, `materials-remote.ts`, `activity-links.ts`, `course-pdfs-remote.ts`, `uploads-remote.tsx`, `preview-remote.tsx`, `entry-login.tsx`, `backend-prompt.tsx`, `src/areas/course/activities-server.tsx`, `src/areas/learning/activity-server.tsx`, `preview-server.tsx`, `learning.tsx`, `learning.css`, `src/router.tsx`, `src/types.ts`, `tests/integration/school-activities.test.ts`(import 경로)

### 검증
- `origin/main`(ace7e4a) 위로 rebase한 뒤 `npm run typecheck` 오류 없음, `npm test` 31파일 1443개 통과(새 13개, 기존 테스트는 #19 테스트의 import 경로만 바꿈), `npm run build` 성공.
- 실제 실행(로컬 백엔드 1c81144 `bootTestRun` 18480/18481, 프론트 `http://localhost:4193`, 본인 계정 학교 로그인 2번): 과목 하나에서 강의실 12행 중 학교 자료 8행이 한 자료 한 행·같은 창 링크, 다른 학교 행 4개는 새 탭. 학습활동 목록 자료 필터 8행. 파일모음 학교 파일 10개(썸네일 없음). 이미 읽은 자료 하나: 작성자·게시일·첨부 1개(본문은 비어 있었다), e-Class 링크. PDF(약 200KB) 미리보기 11쪽, 복사 뒤 숫자 ID로 내려받기 202,564바이트(`%PDF-`). 로그아웃 뒤 같은 자료 주소 → 로그인 모달, 다시 로그인 → 같은 자료. 백엔드 요청 기록: 학교 상세 읽기는 직접 연 3번뿐, 학교 파일 목록 읽기 1번.

### 남은 작업
- 409 `school_signin_required`는 실제로 만들지 못했다(로컬 백엔드를 다시 띄우면 DB가 초기화되어 401이 된다). Vitest로만 확인.

## 2026-11-14 런타임 점검 5 결함 수정(F1·F2·F3·F5·F6·F7)

### 작업 내용
- F1 쪽 이미지 복구: 이미지가 실패하면(전달 쿠키 만료) 출처를 다시 받아 쿠키를 새로 받고 `<img>`를 key로 다시 만든다(주소가 같아도 다시 요청한다). 자동 재시도는 이미지마다 1번, 그다음은 "N쪽 이미지를 불러오지 못했습니다" + "다시 불러오기"(누를 때마다 한 번만 다시 받는다). 동시에 실패한 쪽은 요청 하나를 같이 쓰고, 끝난 뒤의 실패는 새로 받는다(`page-image-retry.ts`). 레슨 뷰어와 쪽별 요약(`page-summary.tsx`)이 같은 `PageImage`를 쓴다. 레슨 뷰어의 크게 보기 버튼은 이미지 안쪽(`.lv-zoom`)으로 옮겨, 실패 안내의 버튼이 버튼 안에 들어가지 않게 했다.
- F1 리뷰 후속: 다시 받기는 15초(`REFRESH_TIMEOUT_MS`)에 답이 없으면 실패로 끝나 안내와 버튼을 보이고, 다음 실패는 멈춘 요청을 기다리지 않는다. 다시 받은 뒤에도 실패한 주소는 뷰어가 기억해 나중에 불러오는 쪽은 요약을 다시 받지 않고 바로 안내로 간다(다시 불러오기는 한 번 받고, 이미지가 불러와지면 기억을 지운다). 다시 불러오기를 누르면 버튼이 같은 요소로 남아("불러오는 중…") 포커스가 그대로이고, 이미지가 돌아오면 크게 보기 버튼(쪽별 요약에서는 이미지)으로, 다시 실패하면 버튼으로 간다.
- F2 자료 등록: 등록에 성공하면 폼을 새 key로 다시 그려 자료명·파일을 비우고, 요청 중 두 번째 누름은 ref로 막는다(파일함 경로 포함). 같은 결함이 있던 교수 그룹 만들기 폼도 같은 방식으로 고쳤다. 공지·과제·설문·시험·차시·성적 항목·강의 등록 폼은 이미 비우거나 수정 화면으로 간다.
- F3 이어서 녹음: 원래 녹음의 폴더를 시작할 때 읽어 두고, 멈출 때 원래 녹음의 배치가 없으면 그 폴더에 저장한다. 원래 녹음이 이미 지워졌으면(DELETE 404) 그렇다고 알린다.
- F6 같은 흐름에서 원래 녹음이 바뀌어 409 `version_conflict`이면 "다시 불러왔습니다. 다시 시도하세요" 대신 무엇을 새로 저장했고 무엇을 남겼는지 말한다(`recording-join.ts`).
- F5 없어진 `?pop=` 창(avatar·files·history 등)은 열지 않고 주소에서 뺀다(`dialog-names.ts`, `applyRoute`). 이름을 모르는 창의 샘플 대화상자는 없앴다.
- F7 파일함 고르기 창이 비었을 때 없어진 파일함 화면을 가리키지 않고, 기기 파일은 첨부 칸에서 바로 올리라고 말한다. 이 창이 쓰이는 네 곳(과제 제출, 첨부 고르기, 자료 등록, 자료 수정)에는 모두 직접 올리기가 있다.

### 변경 파일
- `src/shared/page-image-retry.ts`(새), `src/shared/page-summary.tsx`, `src/shared/lesson-viewer.tsx`, `src/shared/lesson-viewer.css`, `src/shared/backend-ui.css`
- `src/areas/professor/professor.tsx`, `src/areas/professor/materials-server.tsx`, `src/areas/professor/groups-server.tsx`
- `src/shared/recording-join.ts`(새), `src/shared/course-recordings.ts`
- `src/shared/dialog-names.ts`(새), `src/router.tsx`, `src/areas/registry.tsx`
- `src/shared/filebox-attach.tsx`
- 테스트 `tests/unit/page-image-retry.test.ts`, `dialog-names.test.ts`, `recording-join.test.ts`, `vitest.config.ts`(coverage include)

### 검증
- `npm run typecheck` 통과, `npm test` 24파일 1390건 통과(리뷰 후속 포함), `npm run build` 통과(청크 크기 경고만).
- 백엔드 3626189 run-only + 프런트 4193에서 브라우저로 확인: F1 실제 403 `delivery_forbidden`→요약 재조회→같은 주소 200, 나중 실패도 재조회, 계속 실패하면 요청 2번 뒤 안내와 버튼(3초간 추가 요청 없음), 버튼 1번에 요청 1번. F2 한 번에 두 번 눌러도 자료 1개, 폼 비움. 그룹도 1개. F3 원래 녹음 삭제 뒤 새 녹음이 폴더 1에 배치되고 안내. F6 409 안내 문구. F5 일곱 이름 모두 창 없음·주소에서 빠짐, `pop=myinfo`는 열림.
- 확인 못 함: 쪽별 요약 화면(`course-study.tsx`의 `summaryView`) 이미지 복구는 같은 컴포넌트지만 브라우저로 열어 보지 않았다. F7 빈 파일함 문구는 화면으로 보지 않았다(학생 파일함에 시드 파일이 있다). 리뷰 후속(시간 제한, 실패 주소 기억, 포커스)은 단위 테스트와 typecheck만 했고 브라우저로 다시 보지 않았다.

### 남은 작업
- 결정 필요: 파일함에 파일을 넣을 화면이 없으므로 "파일함에서 첨부"를 남길지. 지금은 시드 파일과 다른 경로로 들어간 파일만 고를 수 있다.

## 2026-11-14 프론트 보안 점검(코드 변경 없음)

### 작업 내용
- 정적 점검(grep + 코드 읽기). XSS 싱크(`dangerouslySetInnerHTML`은 정적 SVG 1곳), `target=_blank`, `location.assign`, localStorage·IndexedDB, CSP·헤더, 서비스워커, 프록시 헤더, `npm audit --omit=dev`(0건), 벤더 pdf.js(5.4.394, `isEvalSupported:false`)를 확인했다.
- 발견: (1) `/api/assistant`가 무인증이고 속도 제한이 `socket.remoteAddress` 기준이라 터널 뒤에서는 전 사용자가 한 버킷을 공유할 수 있음, (2) 학교 비밀번호를 `POST /api/auth/eclass-login`으로 직접 받음(정책·신뢰 위험), (3) `meetingUrl` href 무검증(`learning.tsx`, `professor.tsx`; CSP가 막아 실질 위험 낮음), (4) `sanitizeRichSource`는 정규식 블랙리스트(렌더가 React 노드라 현재는 안전), (5) 개발 로그인 차단이 `cf-connecting-ip` 헤더 유무에 의존.

### 변경 파일
- 없음(handoff.md만)

### 남은 작업
- 백엔드는 점검하지 않았다: `returnTo` 서버측 검증, dev 로그인 운영 비활성, eclass-login의 학교 서버 접속 여부.

## 2026-11-14 로그인은 포커스된 모달 하나로만 (본문·내 메뉴의 로그인 폼 전부 제거)

### 작업 내용
- 요청: 내 메뉴 드롭다운·수강과목 등 화면 사이사이에 끼어 있는 로그인 폼을 없애고, 로그인은 포커스가 갇히는 모달 하나로만 한다.
- `backendPanel()`(본문에 끼는 로그인 줄)과 호출처 전부(학생·교수·공통 약 45곳, `homeLoginPanel`·`catalogLoginPanel`·`loginPanel` 포함)를 삭제했다. 로그인 폼은 `BackendLoginForm`(`shared/backend-prompt.tsx`)이 `entry-login.tsx` 모달 안에서만 그린다.
- 모달 동작 변경: 서버 세션이 `anon`인 동안 항상 뜬다(이전: 탭당 한 번, 닫을 수 있음). × 버튼·Escape·배경 클릭으로 닫히지 않고, 로그인 성공(`ready`)하면 사라지며 세션 만료 시 다시 뜬다. 포커스는 ID 입력에 가고 Tab은 모달 안을 순환하며 배경 클릭에도 포커스를 잃지 않는다. `sessionStorage` 표시 기록은 없앴다. `off`(샘플 모드)·`ready`에서는 뜨지 않는다.
- 닫을 수 없게 한 이유: 본문·메뉴의 로그인 폼을 없앴으므로 닫으면 다시 로그인할 방법이 없다.
- "로그인이 필요한 화면"을 판정하던 `blocked`/`gate` 반환값은 null(=진행)과 구분돼야 해서, 본문에 아무것도 그리지 않는 `SIGNED_OUT`(`backend-prompt.tsx`)을 대신 쓴다. 쓰이지 않게 된 `LOGIN*` 문구 상수·`gate`/`useGate`의 `loginText` 인자·`.backend-login.is-compact`·`.msg-gate` CSS·`QNA_LOGIN`도 삭제했다. 내 메뉴 팝업(`ProfileMenu`)의 로그인 폼도 제거.

### 변경 파일
- `src/shared/backend-prompt.tsx`, `entry-login.tsx`, `backend-ui.css`, `board-list.tsx`, `src/App.tsx`, `src/areas/{course,home,learning,professor}/*-server.tsx` 등 호출처, `home/dialogs.tsx`, `learning/learning.css`, `README.md`
- 테스트: `entry-login.test.ts`(새 동작으로 재작성), `backend-prompt-layout.test.ts`(`BackendLoginForm`), `home-layout.test.ts`(익명 홈에 로그인 폼 없음 추가), `course-room-layout.test.ts`(문구 대신 폼 없음 확인)

### 검증
- `npm run typecheck` 통과, `npm test` 1364개 통과.
- Chromium(1280×800, `127.0.0.1:4190`에 `fetch`를 익명 응답으로 바꿔 모달 유도): 첫 접속에 모달이 뜨고 ID에 포커스, 닫기 버튼 없음, Escape·배경 클릭 뒤에도 유지, Tab은 모달 안 순환. 홈·수강과목·알림 팝업(`pop=notify`)·플레이어 화면에서 모달 밖 `.backend-login` 0개. 콘솔 오류 없음.
- 실제 로그인 요청·학교 서버·교수 화면은 브라우저에서 확인하지 못했다(교수 화면은 타입 검사·기존 테스트까지만).

### 남은 작업
- 커밋·푸시하지 않았다. 이전 항목(샘플 플레이어 버튼 정리, 내 메뉴 로그인 폼 제거)도 같은 작업 트리에 있다.

## 2026-11-14 샘플 플레이어: 강의내용·질의하기·단원으로 삭제, 전체화면 버튼 우측 끝

### 작업 내용
- 샘플 플레이어(`SamplePlayer`) 하단 도구 줄에서 "강의내용"·"질의하기"·"단원으로" 버튼을 없앴다. 일반·전체화면 모두 해당(단원으로는 전체화면에서도 남아 있었음). 전체화면 버튼은 `.player-full`의 `margin-left: auto`로 줄 맨 오른쪽에 붙는다.
- 버튼과 함께 쓰이지 않게 된 코드 삭제: `changePanel`·`ask`·`openUnit`, `playerPanel` 타입, 하단 시트(`.player-sheet*` CSS 포함), 미사용 `currentRole`/`routeFor` import. `state.playerPanel`·`draft.playerNotes`는 읽는 곳이 없어졌다(브라우저에 저장돼 있던 값은 그대로 남는다).
- 서버 강의 플레이어(`lectures-server.tsx`)의 "강의 목록으로" 링크는 다른 화면이라 건드리지 않았다.

### 변경 파일
- `src/areas/learning/learning.tsx`, `learning.css`, `video-study.css`

### 검증
- `npm run typecheck` 통과, `npm test` 1365개 통과.
- Chromium 1200×800(`127.0.0.1:4190`, 샘플 플레이어): 일반·전체화면 모두 버튼이 1x·1.25x·1.5x·2x·자막·전체화면뿐이고 전체화면 버튼이 줄 오른쪽 끝(간격 0), 시트 없음, 콘솔 오류 없음.

### 남은 작업
- 커밋·푸시하지 않았다.

## 2026-11-14 로그인 홈 전체화면에서 로고 클릭 시 일반 모드 복귀

### 작업 내용
- 원인: `collapseHero()`의 `if (onHome && backendStatus() === "ready") return;`(901d8c6에서 추가, 이유 기록 없음) 때문에 로그인 상태 홈에서는 로고 클릭이 접기를 건너뛰고 같은 홈 주소로 이동만 했다. 이 줄을 삭제해 오프라인 목업과 같은 동작(이동 막고 View Transition으로 접기, 저장값 `normal`)을 하도록 했다.

### 변경 파일
- `src/areas/home/home.tsx`(`collapseHero`)
- `tests/integration/home-layout.test.ts`(로그인 홈 전체화면 → `collapseHero` → `preventDefault`·`is-full` 해제·저장 `normal` 회귀 테스트. 수정 전 실패, 수정 후 통과 확인)

### 검증
- `npx vitest run tests/integration/home-layout.test.ts` 5개 통과, `npm run typecheck` 통과.
- 가상 로그인 서버(`http://127.0.0.1:4195`) Chromium 1568×886: 전체화면 상태에서 좌상단 로고 클릭 → `is-full` 해제, 저장 `normal`, 주소 유지, 콘솔 오류 없음.

### 남은 작업
- 없음.

## 2026-11-14 프론트 구조 발표용 설명(코드 변경 없음)

### 작업 내용
- 발표에서 설명할 수 있도록 현재 프론트 구조를 읽고 정리해 답했다. 확인한 파일: `README.md`, `package.json`, `src/main.tsx`, `App.tsx`, `router.tsx`, `store.ts`, `types.ts`, `manifest.ts`, `areas/registry.tsx`, `shell/shell.tsx`, `shared/{api,backend,remote-cache,home-remote,routes}.ts`, `server.mjs`.
- 핵심: 라이브러리는 react·react-dom뿐(라우터·상태관리 라이브러리 없음). 주소→`Screen`(manifest 표)→영역(home/course/learning/professor/mobile)→화면. 상태는 `store.ts`의 단일 객체 + `useSyncExternalStore`. 서버 데이터는 `shared/*-remote.ts`(`remote-cache.ts` 캐시) + `areas/*/*-server.tsx`. `server.mjs`가 정적 서빙·`/api` 프록시·AI 비서 중계.

### 변경 파일
- 없음(handoff.md만)
- 저장소 밖: 바탕화면 `프론트_구조_발표용.md`(같은 내용, 기본 앱으로 열어 둠)

### 남은 작업
- 없음.

## 2026-11-14 강의실·학습활동 목록: 학교 e-Class에서 가져온 활동을 실제 행으로(새 탭 링크)

### 작업 내용
- 백엔드(`STAC-000-school-activities`, 아직 병합 전)가 `GET /api/courses/{id}/weeks`의 주차 안에 학교에서 가져온 활동을 `origin: "school"`, 문자열 `activityId`(`school-…`), 화면 유형 `type`(`video|material|report|test|survey|zoom|post|…`), `schoolType`, 본인 `done`, `phase`, `attendance`, `learningTimeSec`, `offline`, `schoolUrl`로 붙여 준다. 지금까지는 이 행이 "열 수 없음"이었다.
- `activitySnapshot`: `origin === "school"` 행은 그 화면 유형의 행이 된다. `done`은 `done === true`, 진행 중은 `phase === "open"`, 미제출은 진행 중이고 `done === false`이며 자료·오프라인 시험이 아닐 때. 주차 표시(영상·과제·시험·설문), 강의실 종류·진행중·미제출 필터, 학습활동 목록 필터(51–56)에 모두 들어간다.
- 링크 규칙: `schoolUrl`의 origin이 정확히 `https://eclass.seoultech.ac.kr`(기본 포트, 사용자 정보 없음)일 때만 링크다(`schoolHref`). 그 밖(`http:`, 다른·비슷한 호스트, `javascript:`, 상대 주소)은 지금처럼 누를 수 없는 행이고 상세에 "e-Class 주소 없음"이 붙는다.
- 링크 행은 라우터 `Link`가 아닌 일반 `<a target="_blank" rel="noopener noreferrer">`이다. 유형 옆에 "· e-Class ↗" 표시, 접근 가능한 이름은 "제목, 유형, 상태 — 학교 e-Class에서 열기(새 탭)". 상태는 완료·미완료·구간, 상세에 영상은 `출석 …`·`학습시간 …`, 시험은 "오프라인", 끝에 "e-Class에서 열기".
- "이어서 학습하기"는 이 서비스의 행이 먼저다. 학교 행만 있는 과목에서만 다음 학교 행을 새 탭 링크로 연다(`RoomLayout.nextExternal`).
- 다른 주차 목록 독자: `WeekActivity.activityId`를 `number | string`로 넓히고, `lectureRows`(학습실 영상 카드)는 숫자 ID의 네이티브 `lecture_weeks`만, 교수 강의실(`course-room-remote.ts#roomEntry`)은 학교 행을 빼서 공개·삭제 명령 대상이 되지 않게 했다. 할 일·캘린더·홈·파일모음·어시스턴트는 주차 목록을 읽지 않아 바뀌지 않는다. 학교 행이 없는 응답은 그대로다.

### 변경 파일
- `src/shared/course-activities-remote.ts`(`SCHOOL_ORIGIN`, `schoolHref`, 학교 행 매핑), `src/areas/course/activities-server.tsx`(`schoolState`, 기간, 이어서 학습하기), `src/shared/activity-row.tsx`(`external`), `src/areas/course/room-view.tsx`(`nextExternal`), `src/areas/course/course.css`(`.activity-ext`), `src/shared/lectures-remote.ts`(`WeekActivity` 필드, `lectureRows`), `src/shared/course-room-remote.ts`, `tests/integration/school-activities.test.ts`

### 검증
- `origin/main`(bf60783) 위로 rebase한 뒤: `npm run typecheck` 오류 없음, `npm test` 24파일 1373개 통과(새 9개, 기존 테스트 수정 없음), `npm run build` 성공.
- 브라우저(ego-browser, `server.mjs --port 4194` + `ASTRA_API_ORIGIN`로 띄운 계약 견본 stub): 1·2·9주(9주는 학교에만 있는 주) 행, 종류 칩(영상·과제·자료·시험·Zoom·설문·글), 영상·글·미제출·진행중 필터, 학습활동 목록 영상·과제·미제출 필터에 학교 행이 들어감. 링크 행 `target="_blank"`, `rel="noopener noreferrer"`, 다른 호스트 주소 행은 `div[aria-disabled]`. 학교 행을 누르면 새 탭에 학교 연결 주소가 열리고(`window.opener === null`) 원래 탭은 강의실에 남는다. 학교 로그인 없는 브라우저에서는 학교가 "과목을 선택하세요." 경고 뒤 `/ilos/index.jsp`로 보냈다(견본 SEQ).

### 남은 작업
- 백엔드 `dev`에 대한 실제 응답 확인은 통과했다(가져온 행이 모두 올바른 속성의 링크, 필터 수가 API와 일치). 백엔드에 전할 것: `phase`가 null이고 `done`이 null인 행(Zoom 등)은 상태가 "e-Class"로만 보인다. `done: null`(가져오기가 못 본 행)과 `false`를 화면에서 구분하지 않는다(미제출에서 빠질 뿐). 학교 연결 주소는 학교 로그인이 없으면 로그인 화면이 아니라 경고 뒤 첫 화면으로 간다.

## 2026-11-14 홈 추천 문장 버튼 4개: 한 줄 고정 + 세로 가운데 정렬

### 작업 내용
- `.hero-prompts`를 `flex-wrap: nowrap; align-items: center`로 바꿔 버튼 4개가 줄바꿈 없이 한 줄에 놓이게 했다. 버튼은 `inline-flex; align-items/justify-content: center; white-space: nowrap`이라 글자가 버튼 안 세로 가운데에 선다.
- 좁은 화면에서는 줄이 넘치는 대신 가로 스크롤(스크롤바 숨김)한다. 전체화면에서는 `justify-content: safe center`로 가운데 정렬하되 넘치면 왼쪽부터 보이게 한다.
- 버튼 줄을 아래로 내려, 위(다음 행동 카드)와 아래(입력창) 사이 빈 공간의 세로 가운데에 놓았다. `.hero-prompts`에 `--prompts-shift = (답 한 줄 + gap) / 2`만큼 위 margin을 주고 같은 값을 아래 margin에서 빼서, 입력창 위치와 히어로 높이는 그대로다.

### 변경 파일
- `src/areas/home/home.css`

### 검증
- 개발 서버(Chromium, 폭 1568·390)에서 확인: 버튼 4개 y 좌표 동일·높이 40px, 폭 390에서는 한 줄 가로 스크롤. 폭 1568에서 버튼 위 여백 30px = 아래 여백 30px. 어두운 테마는 확인하지 못했다.

### 남은 작업
- 없음. "빈공간" 기준을 다르게 의도했다면(인사말과 카드 사이 공간으로 이동 등) 알려주면 된다.

## 2026-11-14 녹음 요약의 "쉬운 설명" 제거

### 작업 내용
- 녹음 요약 각 구간 아래의 접이식 "쉬운 설명"(`<details class="rs-easy">`)을 없앴다. `StudySection.easy` 필드, 목업(`demoRecordingStudy`)의 `easy` 문구, `.rs-easy` CSS(`recording-study.css`)도 같이 지웠다. 저장소에서 "쉬운 설명"이 나오는 곳은 이 한 곳뿐이었다.

### 변경 파일
- `src/areas/home/recording-study.tsx`, `src/areas/home/recording-study.css`

### 검증
- `npx tsc` 오류 없음. 개발 서버가 내려주는 두 파일에 "쉬운 설명"·`rs-easy`가 없음을 확인했다. 녹음 화면을 직접 열어 보지는 않았다(녹음 파일을 올려야 열린다).

## 2026-11-14 챗봇 입력창(chat dock) 열고 닫을 때 애니메이션

### 작업 내용
- 마스코트 FAB 위의 챗봇 입력창(`.chat-dock-slot`)이 열릴 때 우하단(FAB 쪽)에서 `translateY(12px) scale(0.96) + opacity 0` → 제자리로 200ms, 닫힐 때 같은 값으로 140ms(닫기가 더 빠르다)로 움직인다. 이징은 `cubic-bezier(0.23, 1, 0.32, 1)`. 열기는 `@starting-style`, 닫기는 `data-state="leaving"`.
- 닫는 동안 요소를 DOM에 160ms 더 둔다(`assistant-tray.tsx` `usePresence`, `DOCK_EXIT_MS`). 그 사이 `inert`·`aria-hidden`이라 포커스·클릭이 닫히는 입력창에 가지 않는다. 닫는 도중 다시 열면 현재 값에서 되돌아간다(transition).
- 움직임 줄이기 설정(`prefers-reduced-motion`)에서는 위치·크기 변화 없이 120ms 페이드만 한다.
- 안 건드린 것: 홈 ↔ 다른 화면 이동으로 트레이 전체가 생기고 사라지는 경우(트레이가 `null`을 돌려줘 닫힘 애니메이션이 없다, 열림만 페이드). FAB 누를 때와 계획 패널 `tray-rise`는 그대로.

### 변경 파일
- `src/shared/assistant-tray.tsx`(`usePresence`, `dockShown` 위치 이동, 슬롯에 `data-state`·`inert`), `src/shell/shell.css`(`.chat-dock-slot`)

### 검증
- `npx tsc` 오류 없음. Chromium(샘플 모드, `sessionStorage stack.assistant.chat=1`)에서 FAB로 닫기: opacity 1→0 약 140ms 후 DOM 제거, 열기: 0→1 약 200ms, 열 때 포커스가 `chat-input`으로 감, 닫는 도중 다시 열기: 0.39에서 0.93으로 되돌아옴. 어두운 테마·모바일 폭·움직임 줄이기 설정은 눈으로 확인하지 못했다.

## 2026-11-14 점검: "학습실에 저장"이 GPAI 금지 사유(시스템 부하)에 걸리는가(코드 변경 없음)

### 작업 내용
- 충북대 공지(2026-11-09)가 든 연동 위험은 3가지다: 계정·개인정보 유출, 교수자 저작권·교육자료 관리, 학내 시스템 부하. 부하만이 사유가 아니다. 기연동 계정은 백그라운드 접속이 계속된다며 비밀번호 변경을 요구하고, 비정상 대량 접속 계정은 접속을 제한할 수 있다고 했다.
- 프런트에 `POST /api/auth/eclass-login { studentId, password }`(`backend.ts`)이 있다. 백엔드가 이 비밀번호로 학교 E-CAMPUS에 접속하는지는 저장소에서 확인하지 못했다. 접속한다면 공지가 금지한 방식과 같다.
- "학습실에 저장"을 폴더 배치(`PUT /api/me/study-room/placements/…`)로 연결하면 ASTRA DB 쓰기 한 번이고 학교 서버를 부르지 않는다. 파일 사본 저장은 저작권 항목에 걸리고 ASTRA 저장·전송 부하가 생긴다.

### 변경 파일
- 없음(handoff.md만)

### 남은 작업
- 백엔드 담당에게 확인: (1) eclass-login이 학교 서버를 호출하는가 (2) 자료가 ASTRA에 올린 파일인가, 학교에서 가져온 것인가 (3) 학교 호출이 있다면 캐시·호출 한도·백그라운드 동기화 유무.

## 2026-11-14 자료 "내려받기" 오른편에 "학습실에 저장" 버튼(목업)

### 작업 내용
- 이전 상태: 서버 자료의 내려받기 옆에는 "학습실에 저장" 버튼이 없었다. PDF 자료 상세에 "학습실에서 열기" 링크만 있었다.
- PDF 첨부에만 내려받기 오른편에 **학습실에 저장** 버튼을 넣었다. 누르면 창이 열리고 저장할 폴더(폴더 없이 / 이 과목 폴더들)를 고른 뒤 **저장**하면 버튼이 "학습실에 저장됨"으로 바뀌고 토스트가 뜬다. 창에 "목업 화면입니다. 실제로는 아직 저장되지 않습니다." 문구가 있다.
- 적용 위치: 과목 파일모음(`course/activities-server.tsx` 아이콘 줄) · 자료 상세(`learning/activity-server.tsx` `FileRow`). 과제 제출·쪽지·공지·게시판·교수 화면의 내려받기에는 넣지 않았다.
- 백엔드 `off`(샘플) 화면에도 같은 버튼을 달았다: 샘플 파일모음(`course/course.tsx` `renderFiles`), 샘플 자료 상세(`learning/learning.tsx` `fileRow`의 `save` 인자, 미리보기 옆). 샘플 파일은 모두 PDF다.
- **목업**: 서버·브라우저 어디에도 저장하지 않는다. "저장됨" 표시는 이 탭 메모리(`saved` Map)에만 있어 새로고침하면 사라진다.

### 변경 파일
- 신규: `src/shared/save-to-study.tsx`, `src/shared/save-to-study.css`
- 수정: `src/app.css`(import), `src/areas/course/activities-server.tsx`, `src/areas/learning/activity-server.tsx`, `src/areas/course/course.tsx`, `src/areas/learning/learning.tsx`

### 검증
- `npx tsc` 오류 없음. 임시 하니스 페이지(삭제함)에서 Chromium으로 버튼 표시 → 창 열림 → 저장 → "학습실에 저장됨"·토스트 확인. `node server.mjs --dev --port 4291`(백엔드 없음)로 실제 앱의 샘플 파일모음(`/ilos/cls/st/files/files_list_form.acl?kj=bi`)·샘플 자료 상세(`…/activity_form.acl?m=lecture_material&n=m-1&kj=bi`)에서 버튼 → 창 → 저장 → "저장됨"·토스트를 확인했다. 백엔드 연결 시의 서버 화면(`*-server.tsx`)은 열어 보지 못했다.

### 남은 작업
- 실제 저장: `SaveToStudy`의 `onSave`에서 `placeItem(\`srv-<fileId>\`, folderId)`(`study-folders.ts`)를 부르면 학습실 폴더 배치가 된다. 지금은 폴더 이름만 받고 부르지 않는다. 비PDF 자료는 학습실이 받지 않아 버튼을 숨겼다.

## 2026-11-14 테스트 체계 도입 (Vitest + Stryker)

### 작업 내용
- 저장소에 테스트가 없어 `vitest`(+`@vitest/coverage-v8`, `jsdom`)와 `@stryker-mutator/core`·`vitest-runner`를 devDependency로 추가했다. 스크립트: `npm test`, `npm run test:coverage`, `npm run test:mutation`.
- 화면(`.tsx`) 밖에서 판단·계산·보관을 맡는 모듈을 테스트했다: `format`, `routes`, `activity-links`, `remote-cache`(+React 훅), `session-drafts`, `webm-append`, `store`, `assistant/{course-match,aliases,resolve,plan}`. 1315개 테스트(14개 파일), 대상 모듈 커버리지 문장 98% / 분기 94% / 함수 98% / 줄 99%(임계값 95/90/95/95), 변이 점수 87.6%(임계값 75).
- 설계 근거·요구사항↔테스트 추적표·제외 항목은 `docs/TEST_STRATEGY.md`에 적었다. CI는 `.github/workflows/test.yml`(typecheck + 커버리지, 변이 테스트는 수동 실행).
- **테스트로 찾아 고친 결함 2건(동작이 바뀜)**:
  1. `src/shared/webm-append.ts` `children`: 크기를 모르는 클러스터의 마지막 블록이 잘린 파일을 거부하지 않아 깨진 합친 파일이 만들어졌다. "요소가 파일보다 김" 검사 한 줄 추가.
  2. `src/shared/format.ts` `boardRows`: 교수가 쓴 과목 공지(`noticesExtra`)가 전체 공지 게시판에도 보였다. 전체 범위(`scope === "global"`) 공지만 통과하도록 바꿨다. 의도한 동작이었다면 되돌리고 알려 달라(회귀 테스트 `format.test.ts` "전체 공지"도 함께).
- 고치지 않고 `docs/TEST_STRATEGY.md`에 남긴 것: 서버 ID 정규식(16자리)이 `Number` 안전 정수 범위를 넘을 수 있음, `routeFor("screen")`의 도달 불가 분기, `routes.ts`의 `ACTIVITY_SCREEN`·`POP` 표가 일반 규칙과 같은 주소를 만드는 중복 데이터.
- `tsconfig.json`의 `include`에 `tests`, `vitest.config.ts`를 추가했다(테스트도 `npm run typecheck`로 검사). `.gitignore`에 `coverage/`, `reports/`, `.stryker-tmp/` 추가.
- TypeScript 7에는 Stryker가 쓰는 JS API가 없어 `stryker.config.json`의 `tsconfigFile`을 존재하지 않는 파일로 지정해 우회한다.

### 변경 파일
- 신규: `tests/**`(`unit`, `integration`, `helpers`), `vitest.config.ts`, `stryker.config.json`, `docs/TEST_STRATEGY.md`, `.github/workflows/test.yml`
- 수정: `package.json`(scripts·devDependencies), `package-lock.json`, `tsconfig.json`, `.gitignore`, `README.md`(테스트 명령), `src/shared/webm-append.ts`, `src/shared/format.ts`

### 검증
- `npx tsc` 오류 없음. `npx vitest run --coverage` 1315개 통과, 임계값 통과. `npx stryker run` 약 10분, 변이 점수 87.55%.
- 결함 2건은 고친 코드를 잠시 되돌려 회귀 테스트가 실패하는 것을 확인한 뒤 복원했다.

### 남은 작업
- 파일별 변이 점수와 살아남은 변이의 성격은 `docs/TEST_STRATEGY.md` §5 참고.
- `.tsx` 화면 테스트와 자동화 E2E는 하지 않았다(이유는 전략 문서 §6). `.github/workflows/test.yml`은 GitHub에서 실행해 보지 못했다.
- 위 변경은 모두 미커밋이다.

## 2026-11-14 발표 대본 구조 검토(코드 변경 없음)

### 작업 내용
- 사용자 발표 대본 구조(인사 → 문제 정의 → 해결 → 시연 → 경쟁 서비스 대비 강점 → 맺음말)에 의견을 주었다.
- 근거 조사: 충북대 LMS 공지 "외부 미승인 AI 서비스(GPAI)의 E-CAMPUS 계정 연동 금지 및 보안 유의 안내"(2026-11-09, https://lms.chungbuk.ac.kr/mod/ubboard/article.php?bwid=140345&id=17), 한국교원대 국어교육과 공지 "학내 무단 설치 홍보물(GPAI) 철거"(2026-11-04), UnivAI 공식 사이트(강의 녹음·PDF·유튜브 요약·퀴즈, 2026년 10월 기준 누적 가입 62만).
- 코드에서 확인한 강점 근거: 교수용 외부 AI 처리 동의(`src/areas/professor/llm-consent-server.tsx`), 동의 없으면 요약 거부(`course-pdfs-remote.ts`의 `llm_consent_required`), 학습실 PDF·녹음 업로드(`study-new.tsx`).

### 변경 파일
- 없음(handoff.md만)

### 남은 작업
- 학생이 학습실에 올린 PDF가 교수 동의 없이 외부 AI로 가는지는 백엔드에서 확인하지 못했다. 발표 질의응답 대비로 확인 필요.
- 사용자 지시: 교수 UI는 잠시 보류. 발표에서 교수 동의(`llm-consent-server.tsx`)·교수 요약 게시를 강점으로 쓰지 않도록 의견을 고쳤다. 저작권 질문은 "개인 학습용 업로드 + B2B 계약 시 학교와 자료 이용 협의"로 답하는 방향.
- 최종 추천 구조: 인사+훅 → 문제(eclass 불편 + 기존 대안 GPAI·UnivAI의 한계) → 해결 원칙 3가지 → 시연(문제 순서대로) → 비교표+B2B 계획 → 한 줄 맺음말.

## 2026-11-14 학습실 자료 요약을 lessonviewer 강의 화면(LectureView)으로, 좌측 메뉴 숨김

### 작업 내용
- `LessonViewer`(`src/shared/lesson-viewer.tsx`)를 FMsongX2/lessonviewer `StudyApp/web/src/LectureView.tsx`·`SlideRow`·`BeatBlock`·`useSplit` 구조로 다시 만들었다. 이전의 "왼쪽 원본 뷰어(60/80/100%) + 오른쪽 탭(요약·쪽별 설명…)" 2단 화면은 없앴다.
  - 위에 붙는 도구줄: 자료 제목, 지금 흐름(누르면 흐름 목차), 보기 방식 `핵심만/기본/전체`, `쪽 / 전체`, **학습 도구** 버튼, 빨간 진행 막대.
  - 머리: 질문(`summary.question`, 없으면 파일 요약) 빨간 줄 인용, 파일 요약(질문이 따로 있을 때), 흐름 목차(storymap), 출처 안내.
  - 흐름: 왼쪽 빨간 실 위에 beat(번호 원 · 문제/해결/trade-off/보충/이어서)와 쪽 한 줄(쪽 번호 · 원본 · 너비 손잡이 · 설명 + 태그 · 확인 필요)을 꿴다. 보기 방식에 맞지 않는 쪽은 "접은 쪽 N장"으로 묶이고 눌러서 펼친다.
  - 원본을 누르면 크게 보기(←/→, Esc, 넘겨 본 쪽으로 닫힘). 손잡이를 끌거나 ←/→로 너비 조절, 더블클릭으로 54% 복귀. 키 `j`/`k` 쪽 이동, `1`/`2`/`3` 보기 방식(한글 자모도 받음).
  - 보기 방식·너비는 localStorage `stack-lv-mode`, `stack-lv-split`에 저장.
  - 핵심 개념·퀴즈·플래시카드(샘플만 있음)는 **학습 도구** 칸(넓은 화면은 오른쪽 고정, 1319px 이하는 덮는 창)으로 옮겼다. 위치 버튼은 그 쪽으로 스크롤한다.
  - 색은 앱 토큰 + lessonviewer 빨강/파랑(`--lv-red`, `--lv-blue`, 다크·라이트 각각). 글꼴은 앱 글꼴 그대로.
- 옮기지 않은 것: 책갈피·별표·필기·작년 자료·실습 셀·학습 에이전트·진도 저장(저장할 서버 API가 없음), lessonviewer 자체 사이드바(강의 목록).
- 학습실 자료 요약 화면(`study_summary`)은 좌측 메뉴(`.sidenav`) 없이 본문만 쓴다(`shell.tsx` `noNav`, 녹음 화면과 같은 방식). 머리(경로·제목·자료 카드)는 원본 쪽과 같은 왼쪽 선(gutter)에 맞췄다.

### 변경 파일
- `src/shared/lesson-viewer.tsx`, `src/shared/lesson-viewer.css`(전면 교체, 녹음 화면이 쓰는 `.lv-state`·`.lv-panel`·`.lv-note`·`.lv-beat-head`·`.lv-jump`·`.lv-tabs`는 유지), `src/shell/shell.tsx`(`noNav`)

### 검증
- `npm run typecheck` 통과.
- Chromium 1568×886, 백엔드 `off`, 샘플 `?m=sm-db`(다크·라이트): 좌측 메뉴 없음, 보기 방식 전환(전체 5쪽/핵심만 4쪽, 저장), 접은 쪽 펼치기, `j` 이동, 흐름 목차 이동, 학습 도구 칸 열기·퀴즈 풀이·위치 버튼 이동·닫기, 크게 보기 열기·→·Esc, 손잡이 키보드 조절·저장·더블클릭 복귀. 760px 폭: 가로 넘침 없음, 원본 위·설명 아래로 쌓임, 학습 도구가 dialog로 열리고 Esc로 닫혀 버튼에 초점 복귀. 콘솔 오류 없음.

### 남은 작업
- 서버 게시 요약(쪽 이미지 `kind: "images"`, 이미지 쿠키 만료 시 재요청)은 백엔드가 없어 화면으로 확인하지 못했다.
- 좌측 메뉴가 없어 좁은 화면에서도 메뉴 버튼이 숨는다(녹음 화면과 같음). 돌아가기는 상단 경로(학습실 · 과목) 링크로 한다.

## 2026-11-14 녹음 파일 화면 "이어서 녹음"

### 작업 내용
- 플레이어 카드의 "새로 녹음" 링크를 **이어서 녹음** 버튼으로 바꿨다. 누르면 원래 녹음과 같은 소스(탭 녹음은 탭, 그 밖은 마이크)로 녹음을 시작하고 녹음 화면(LiveView)으로 간다. 이때 "이어서 녹음 중", 원래 길이부터 이어지는 시계, `"제목" 뒤에 이어 녹음합니다` 안내가 보인다. 중지하면 한 파일로 합쳐 저장하고, 그 녹음의 재생 화면으로 돌아온다.
- 합치기(`src/shared/webm-append.ts`, `appendWebm`): MediaRecorder WebM/Opus 두 파일을 다시 인코딩하지 않고 이어 붙인다. 첫 파일의 EBML·Info(Duration 제거)·Tracks와 클러스터를 그대로 두고, 둘째 파일의 클러스터는 Timecode를 첫 파일 끝(마지막 블록 + 한 프레임)만큼 밀어 붙인다. SeekHead·Cues·Tags는 뺀다. 트랙 수, 코덱, 샘플레이트, 채널, TimecodeScale이 다르면 거부한다.
- 저장(`course-recordings.ts` `joinRecording`): 브라우저 녹음은 IndexedDB 레코드를 같은 id 그대로 바꾼다. 서버 녹음은 합친 파일을 같은 제목·시작 시각·폴더로 새로 올린다(키 `append-<id>-<version>-<시작ms>`). 201을 받은 뒤에만 원래 녹음을 지운다. 지우지 못하면 둘 다 남기고 토스트로 알린다(404는 무시). 합치기나 업로드가 실패하면 새로 녹음한 부분을 `"<제목> (이어서)"`로 원래 녹음과 같은 폴더에 따로 저장하므로 소리는 잃지 않는다.
- 막는 경우(`appendBlocked`, 버튼 아래 문장): 다른 녹음이 진행 중일 때, 지금 듣지 않는 과목일 때, 서버 검사 중이거나 거부된 녹음, WebM이 아닌 파일(올린 MP3·M4A·WAV, Safari MP4 녹음), 이 브라우저가 같은 형식으로 녹음하지 못할 때. 서버 파일 한도는 합친 크기 기준으로 경고하고 멈춘다.
- 이어서 녹음 중에는 `ActiveRecording.appendTo`가 채워지고, `elapsedMs`·`bytes`에 원래 녹음이 포함된다.

### 변경 파일
- 신규: `src/shared/webm-append.ts`
- 수정: `src/shared/course-recordings.ts`, `src/areas/home/recording.tsx`(LiveView 문구, `resume` 연결), `src/areas/home/recording-study.tsx`(`newHref` → `resume`, 사용하지 않는 `Link` import 삭제), `src/areas/home/recording-study.css`(`.rs-new` 버튼)

### 검증
- `npm run typecheck` 통과.
- Chromium에서 `appendWebm`: 440Hz·880Hz MediaRecorder 녹음 두 개를 합쳤다. 디코드 길이 4.14초(2.22 + 1.92), 앞부분 440Hz, 뒷부분 880Hz, `<audio>` 길이 4.08초.
- 백엔드 `off`, `kj=bi`에서 끝까지 확인했다. 가짜 마이크(init script로 `getUserMedia`가 오실레이터를 돌려줌)로 4초 녹음 → 이어서 녹음 → 저장했다. 결과는 같은 id, IndexedDB 레코드 1개, 앞부분 440Hz·뒷부분 880Hz였다. 한 번 더 이어서 녹음해도(이미 합친 파일에 다시 붙이기) 레코드 1개이고 길이가 늘었다. WAV 업로드 녹음은 버튼이 비활성이고 이유 문장이 나왔다. 콘솔 오류 없음.

### 남은 작업
- 서버 모드(합친 파일 업로드 → 원래 녹음 삭제, 폴더 유지, 실패 시 따로 저장)는 백엔드가 없어 실행하지 못했다.
- 표시 길이(`durationMs`)는 원래 길이 + 이번 녹음의 경과 시간이다. 가짜 마이크 테스트에서는 실제 소리 길이보다 길게 나왔다(AudioContext 시작 지연). 실제 마이크에서 차이가 나는지는 확인하지 못했다.

## 2026-11-14 점검: 학습실 샘플 요약 화면의 LessonViewer 적용 여부

### 작업 내용
- 코드 변경 없음. 스크린샷(`관계형 모델 요약 · 샘플`)은 `sampleSummary`(`study-files.tsx`) → `LessonViewer`로 그려진 화면이다. 1/5·60/80/100%·탭(요약·쪽별 설명·핵심 개념·퀴즈·플래시카드)이 모두 `src/shared/lesson-viewer.tsx`(STack-lessonviewer `ModuleDocumentView` 이식) 출력이다.
- 원본 쪽이 PDF 이미지가 아니라 글 쪽인 이유: 백엔드 `off`일 때 `sampleLesson()`이 `source.kind: "text"`로 샘플 `points`를 쪽처럼 그린다. PDF 이미지(`kind: "images"`)는 `study-server.tsx` `PublishedSection`에서 백엔드 `ready` + 서버 파일(`?doc=`)일 때만 쓴다.
- `lesson-viewer.tsx`·`lesson-viewer.css`·`recording-study.*`는 git 미추적(untracked) 상태다.

### 변경 파일
- 없음(handoff.md만)

### 남은 작업
- 실제 PDF로 보려면 `ASTRA_API_ORIGIN`을 설정해 서버를 띄우고 서버에 등록된 강의자료로 열어야 한다.
- 위 미추적 파일을 커밋해야 다른 브랜치·배포에서도 보인다.

## 2026-11-14 녹음 파일 화면: 상단 탭 제거(요약만), 다운로드 아이콘 메뉴

### 작업 내용
- 오른쪽 학습 도구 탭(퀴즈·플래시카드·마인드맵·암기·개념·메모·AI 튜터)을 모두 없애고 요약만 남겼다. 탭 줄도 없다. 요약이 없으면(백엔드 연결 시) "아직 요약이 없습니다" 안내만 보인다.
- 같이 지운 것: 마인드맵·암기·메모 컴포넌트와 CSS, 테크뇽 질문 탭(`tutor` prop), 메모 저장/삭제/이전(`forgetRecordingMemos`·`moveRecordingMemos`), `RecordingStudy`의 `concepts`·`quiz`와 목업 데이터. `lesson-viewer.tsx`의 `ConceptList`·`Quiz`·`Flashcards`는 다시 모듈 안에서만 쓴다(`export` 제거). 이전에 만든 메모(localStorage `astra-recording-memos:*`)는 읽는 곳이 없어 브라우저에 그대로 남는다.
- 머리의 "다운로드 | 삭제" 두 버튼을 다운로드 아이콘 버튼 하나로 바꿨다(`DownloadMenu`, `recording.tsx`). 누르면 메뉴: **MP3 음성**, **전사문 TXT**, (구분선) **삭제**. Esc·바깥 클릭으로 닫힌다. 삭제는 기능이 사라지지 않게 메뉴 맨 아래에 두었고, 기존처럼 한 번 더 눌러야 지운다. 필요 없으면 `DownloadMenu`에서 마지막 `<button>`과 구분선만 빼면 된다.
- 메뉴 모양은 `course-study.css`의 `.study-new-menu`/`.study-new-item`을 같이 쓰고, 아이콘 `download`를 `ui/icon.tsx`에 추가했다.

### 변경 파일
- `src/areas/home/recording.tsx`, `src/areas/home/recording-study.tsx`, `src/areas/home/recording-study.css`, `src/shared/lesson-viewer.tsx`(`export` 제거), `src/ui/icon.tsx`(`download`)

### 검증
- `npm run typecheck` 통과. 1568×886 Chromium, 백엔드 `off`에서 8초 WAV 업로드 후: `[role=tab]` 0개, 요약만 표시, 다운로드 버튼 → 메뉴 3항목, "삭제" 한 번 클릭 시 "한 번 더 누르면 삭제"로 바뀜, Esc로 닫힘. 실제 삭제 실행·다운로드 파일 저장·어두운 테마·좁은 화면은 확인하지 못했다.

### 남은 작업 (백엔드)
- **MP3 음성**: 지금은 저장된 녹음 파일을 그대로 내려받는다(`<a download>`, 파일명 `fileName`). 녹음은 WebM/M4A/WAV 등이라 MP3가 아닐 수 있다. 백엔드가 녹음을 MP3로 변환해 내려주는 엔드포인트(예: `GET …/recordings/{id}/download?format=mp3`, `Content-Disposition: attachment`, 바이러스 검사 `ready` 상태일 때만)를 만들면 프론트 해당 항목의 `href`만 그 주소로 바꾸면 된다.
- **전사문 TXT**: 전사가 아직 없어 항목이 비활성("준비 중")이다. 백엔드가 전사를 만든 뒤 시각(`m:ss`)과 문장을 줄마다 담은 UTF-8 TXT를 내려주는 엔드포인트(예: `…/download?format=transcript`)를 만들면, 전사가 있는 녹음에서만 항목을 활성화하고 그 주소를 `href`로 연결한다. 전사가 없는 녹음은 404/409로 응답하게 해서 프론트가 비활성 상태를 유지하도록 한다.
- 두 엔드포인트가 정해지면 `DownloadMenu`가 `url` 하나 대신 `{ mp3Href, transcriptHref }`를 받도록 `PlaybackView`를 바꾼다.

## 2026-11-14 일반 모드에서 입력창 누르면 전체화면

### 작업 내용
- 일반 모드에서 명령 입력창을 클릭·포커스(`/` 단축키 포함)하면 전체화면으로 전환하고 입력창에 포커스를 유지한다. 이미 전체화면이면 아무 일도 하지 않는다.
- `commandBar(onActivate)`가 콜백을 받아 입력창 `onFocus`/`onClick`에서 호출한다. 홈은 `expandHero()`(= 일반 모드일 때만 `toggleHeroMode()`)를 넘긴다. 전환·저장(`localStorage`)·애니메이션은 기존 토글과 동일.
- 전체화면 → 토글 버튼으로 접으면 포커스가 버튼으로 가므로 다시 펼쳐지지 않는다.

### 변경 파일
- `src/areas/home/command-bar.tsx`, `src/areas/home/home.tsx`

### 검증
- 브라우저: 일반 모드에서 입력창 클릭 → `is-full`, 포커스 `cmd-input`, 저장 `full`. 토글로 접기 → 일반 모드 유지, 재펼침 없음. 콘솔 오류 없음. `npm run typecheck` 통과.
- `/` 단축키 경로는 자동 입력(press)이 실패해 직접 확인하지 못했다(코드상 `focus()` → `onFocus` 경로).

### 남은 작업
- 입력창 클릭으로 펼친 뒤 일반 모드로 돌아가려면 토글 버튼을 눌러야 한다(Esc 등 다른 접기 수단 없음).

## 2026-11-14 입력 중 마스코트 정지 이미지

### 작업 내용
- 입력창에 글자가 있을 때(`value !== ""`)는 움직이는 GIF 대신 정지 이미지(`orange-mascot-still.png`)를 보인다. 비우면 GIF로 돌아간다.
- 정지 이미지는 사용자가 준 PNG(흰 배경 불투명)에서 배경을 투명하게 만들고(가장자리 un-premultiply), GIF와 같은 360px 기준 좌표(몸통 지름 162·중심 176,183, 2배 해상도 720px)로 맞춰 저장했다. 그래서 CSS 위치(`.cmd-ico img`)는 그대로.
- `prefers-reduced-motion`은 `<picture><source media=...>`로 처리해 항상 정지 이미지를 쓴다. 기존 CSS의 오렌지 원 대체 규칙은 삭제.

### 변경 파일
- `public/app/assets/mascot/orange-mascot-still.png` (신규)
- `src/areas/home/command-bar.tsx`, `src/areas/home/home.css`

### 검증
- 입력 전 `orange-mascot.gif`, "오늘" 입력 중 `orange-mascot-still.png`, 비운 뒤 다시 GIF. 다크 배경에서 흰 테두리 잔상 없음. `npm run typecheck` 통과.

### 남은 작업
- 전체화면 토글이 일반 모드로 돌아가지 않는다는 보고: 새로 연 페이지에서는 재현하지 못했다(다크·라이트, 마우스·키보드, 동작 줄이기, 무작위 클릭 10회 순서 모두 정상). 사용자 화면 캡처는 아이콘은 "펼치기"인데 레이아웃은 전체화면인 불일치 상태였다. 열어 둔 페이지는 자동 새로고침되지 않으므로(확인함) 강력 새로고침 후에도 재현되면 재현 순서가 필요하다.

## 2026-11-14 라이트 모드 과목 커버 눈부심 완화

### 작업 내용
- 처음에는 blur(2px)를 넣었으나 흐릿해 보여 철회하고, 라이트 테마의 과목 커버 이미지(`--course-cover-filter`)를 `none` → `saturate(0.9) brightness(0.84)`로 바꿨다(검은 반투명 15% 정도를 덮은 효과). 다크 테마 필터는 그대로.
- blur용으로 넣었던 `.asset-cover` scale·부모 overflow 규칙은 제거했다.

### 변경 파일
- `src/tokens.css`

### 검증
- 홈 과목 카드 라이트 화면 확인. 강도는 눈으로만 확인.

### 남은 작업
- 더 어둡게/밝게: `brightness(0.84)` 값 조정. 과목 상세 상단(`.detail-hero`)·모바일(`.m-cover`)에도 같은 필터가 적용된다.

## 2026-11-14 계획 요약(hero-plan) 단계 번호 → 유형 아이콘

### 작업 내용
- "이 순서로 진행할까요?" 요약의 `1. 2. 3.` 번호를 활동 유형 아이콘(영상=재생, 과제=연필 등, 다음 행동 줄과 같은 `hero-chip t-<type>` 색)으로 바꿨다. 목록은 `<ol>`을 유지하되 `list-style: none`, 항목은 `아이콘 | 제목·메타` 2열 그리드.
- 아이콘은 `role="img"` + 유형 이름 `aria-label`. 소제목(영상 N개/과제 N개) 앞 아이콘은 다른 편집으로 이미 들어가 있었고, 항목 아이콘과 글자 시작선이 맞도록 소제목 gap만 `space-3`으로 맞췄다.

### 변경 파일
- `src/areas/home/command-bar.tsx`, `src/areas/home/home.css`

### 검증
- `/api/assistant` 응답을 가짜로 바꿔 계획 요약을 띄우고(AI 키 없음) 라이트 테마 전체화면에서 아이콘 표시 확인. `npm run typecheck` 통과. 다크 테마는 확인하지 못함.

### 남은 작업
- 없음

## 2026-11-14 내 메뉴 팝업의 샘플 역할/데이터 되돌리기 제거

### 작업 내용
- 프로필 메뉴(`ProfileMenu`)에서 "샘플 역할" 라벨, 학생/교수/학생 화면으로 보기 버튼, "샘플 데이터 되돌리기" 항목을 제거했다.
- 호출처가 없어진 `switchRole`, `resetSample`과 미사용 import(`followBackendRole`, `HOME_PATH`, `resetState`)도 삭제했다.

### 변경 파일
- `src/areas/home/dialogs.tsx`

### 검증
- `tsc --noEmit` 오류 없음(출력 없음). 화면은 확인하지 못함.

### 남은 작업
- `src/App.tsx`의 `switchRole`/`banner-role`(교수·학생 전환 배너)은 별개 기능이라 그대로 뒀다. 필요하면 같이 제거.

## 2026-11-14 홈 히어로 마감 문구 한 줄로

### 작업 내용
- 문구를 "박민준님. 마감이 얼마 남지 않은 일이 2개 남아있습니다."로 바꾸고(끝맺음 "있습니다" → "남아있습니다"), 768px 이상에서는 한 줄로 고정(`white-space: nowrap`)했다. 문장이 인사말보다 길어서 글자 크기를 `clamp(22px, 2.1vw, 30px)`로 줄여 640px 열에 맞췄다. 767px 이하는 기존처럼 줄바꿈·`--text-display`.

### 변경 파일
- `src/areas/home/home.tsx` (`h1.hero-oneline`, 문구), `src/areas/home/home.css` (`.home-hero h1.hero-oneline`)

### 검증
- 1440px(30px, 640px 열 안에 한 줄), 1100px(23px, 한 줄), 390px(두 줄로 줄바꿈) 확인.

### 남은 작업
- 없음.

## 2026-11-14 홈 "나의 하루" 제목 제거, 요약 카드를 맨 위로

### 작업 내용
- 홈(샘플 모드)의 "나의 하루" 제목(`h2.section-label`)과 그 자리를 없애고, 세 요약 카드(`.summary-strip`)가 본문 맨 위에 바로 붙게 했다. 접근성용으로 `section`에 `aria-label="나의 하루"`만 남겼다.

### 변경 파일
- `src/areas/home/home.tsx`(`myDay`), `src/areas/home/home.css`(`.my-day > .summary-strip { margin-top: 0 }`)

### 남은 작업
- "벤토그램"을 요약 카드 3개(`summary-strip`)로 해석했다. 다른 영역을 뜻했다면 알려 달라. 화면으로는 확인하지 못했다(타입체크만).

## 2026-11-14 전체화면 모드 마감 문구 크기 키우기

### 작업 내용
- 전체화면(`.is-full`)에서는 열이 760px이라 한 줄 문구를 `clamp(22px, 2.5vw, 35px)`로 키웠다(일반 모드는 최대 30px 그대로). 767px 이하는 기존처럼 줄바꿈·`--text-display`.

### 변경 파일
- `src/areas/home/home.css` (`.home-hero.is-full h1.hero-oneline`, `min-width: 768px` 미디어쿼리 안)

### 검증
- 1440px 전체화면: 35px, 740px 폭으로 한 줄(열 760px 안), 가로 넘침 없음. 390px 전체화면: 28px 두 줄 줄바꿈.

### 남은 작업
- 없음.

## 2026-11-14 테크뇽 전부 제거 (마스코트 FAB만 남김)

### 작업 내용
- 우측 하단 테크뇽 FAB(`.fab`)과 학습 도우미 패널(`TechPanel`), 자료 요약 화면의 "이 자료로 테크뇽에게 질문하기" 버튼(`lv-ask`)을 없앴다. 우측 하단에는 마스코트 Plan/챗봇 FAB(`AssistantTray`)만 남는다.
- 테크뇽 대화 패널 본문 `shared/study-chat-panel.tsx`, 상태 `technyonOpen`, `toggleTechnyon`, `FabArt`, `public/app/assets/fab/technyon-*.png`와 manifest `fab.desktop`·`fab.mobile` 슬롯, 관련 CSS(`.fab`, `.tech-*`, `.chat-panel` 계열, `.asset-mascot`, `.lv-ask`)를 삭제했다.
- 문구: `course-study.tsx` "자료 요약과 테크뇽 대화" → "자료 요약", `learning.tsx` "테크뇽 요약" → "자료 요약", 서버 요약 화면의 "테크뇽은 … 답합니다" 안내·빈 상태 문장에서 테크뇽 언급 삭제.
- `handoff.md`는 "일반 모드에서 입력창 누르면 전체화면" 항목 위쪽 기록을 지웠다(요청).

### 변경 파일
- `src/shell/shell.tsx`, `src/shell/shell.css`, `src/shared/lesson-viewer.tsx`·`.css`, `src/shared/account-ui.css`, `src/areas/home/study-server.tsx`, `study-files.tsx`, `course-study.tsx`, `src/areas/learning/learning.tsx`, `src/data.ts`, `src/ui/assets.tsx`, `src/ui/components.css`, `public/app/assets/manifest.json`
- 삭제: `src/shared/study-chat-panel.tsx`, `public/app/assets/fab/`

### 검증
- `npx tsc` 통과. 샘플 `?m=sm-db` 자료 요약: `.fab`·`.lv-ask`·"테크뇽" 문구 없음, 콘솔 오류 없음.

### 남은 작업
- `src/shared/study-chat-remote.ts`의 채팅 API 함수(`listChats`·`openChat`·`ask` 등)는 이제 어디서도 쓰지 않는다(`fileOverview`·`studyMessage`만 사용). 지울지 알려 달라.
- 서버 로그인(`ready`) 모드 화면은 확인하지 못했다.

## PR #5 머지 (audit3-fixes)
### 작업 내용
- PR #5 "런타임 감사 3 수정"을 확인(MERGEABLE/CLEAN, CI 체크 없음, diff 검토)하고 `main`에 merge commit으로 머지했다(e54a1a5). 기존 #3·#4와 같은 방식.
- 내용: 쓰기 뒤 목록 갱신(`remote-cache.ts` `drop`/`reload`/`watch`), 알림·과제·설문 생성 뒤 빈 폼, StrictMode `live` ref 복구, "출석 인정" → "인정 기준 충족" 문구, `llm_disabled` 문장 중복 제거.

### 변경 파일
- 로컬 파일 변경 없음(원격 머지). 로컬 `main`은 아직 `git pull` 하지 않았고 작업 트리에 미커밋 변경이 있다.

### 남은 작업
- 로컬 `main` 동기화(`git pull`)는 미커밋 변경 정리 후 진행.
- 머지 후 `npm run typecheck`/`npm run build`는 돌리지 않았다(PR 작성자 검증에 의존).

## 챗봇 FAB 누르면 입력창 즉시 포커스
### 작업 내용
- 계획·요청함 패널이 없을 때 마스코트 FAB(`toggleChat`)으로 챗봇 입력창을 펼치면 입력창에 바로 포커스가 가서 오렌지 테두리(`.chat-box:focus-within`)가 보인다. 접을 때는 기존대로 FAB에 포커스.
- 입력창에 `data-key="chat-input"`를 달아 `useTrayFocus`의 `pendingFocus`로 포커스를 옮긴다.

### 변경 파일
- `src/shared/assistant-tray.tsx` (`toggleChat`)
- `src/areas/home/chat-dock.tsx` (input `data-key`)

### 남은 작업
- 브라우저에서 직접 눌러 보는 확인은 하지 않았다(`tsc --noEmit`만 통과).

## 우측 상단 사용자 메뉴: 아바타·파일함·접속 이력 삭제
### 작업 내용
- 내 메뉴(`ProfileMenu`)에서 "아바타", "파일함", "접속 이력" 항목과 그 대화상자(`avatar`·`files`·`history`·`history-login`)를 모두 없앴다. 메뉴는 내 정보 / 설정 / 로그아웃만 남는다. 내 정보의 "아바타 바꾸기" 링크도 삭제(이니셜 표시는 유지).
- 서버 모드 쪽도 삭제: `account-server.tsx`의 AvatarForm·사진 크롭(PhotoPart)·접속 기기/세션/로그인 기록 목록, `filebox-server.tsx` 파일 전체.
- 죽은 코드 정리: `account-remote.ts`의 saveAvatar·사진 업로드/삭제·세션/기기/로그인 기록 API, `routes.ts`·`screens.ts`의 해당 pop 항목, `data.ts`의 `devices`·`fileBox` 상태(과제 제출 시 fileBox 추가도 삭제), `account-ui.css`의 `.fb-*`·`.acct-swatch*`·`.acct-photo*`·`.acct-crop`·`.acct-section`·`.acct-subhead`·`.acct-right`·`.is-xl`.

### 변경 파일
- `src/areas/home/dialogs.tsx`, `account-server.tsx`, `screens.ts`, `src/shared/account-remote.ts`, `routes.ts`, `account-ui.css`, `src/data.ts`, `src/areas/learning/dialogs.tsx`
- 삭제: `src/areas/home/filebox-server.tsx`

### 검증
- `npx tsc --noEmit` 통과(`--noUnusedLocals`의 기존 경고 2건은 이번 변경과 무관). 브라우저에서 메뉴가 내 정보/설정/로그아웃만 보이고, `pop=avatar`는 대화상자 없이 열리지 않으며, 내 정보는 정상 표시됨. 콘솔 오류 없음.

### 남은 작업
- 서버 로그인(`ready`) 모드 화면은 확인하지 못했다.
- `filebox-remote.ts`·`filebox-attach.tsx`("파일함에서 첨부")는 첨부 기능이라 남겼다. 이것도 지울지 알려 달라.

## 녹음 화면 좌측 사이드바 숨김
### 작업 내용
- `screen.kind === "lecture"`(녹음 화면, `study_lecture`)에서 `SideNav`·scrim을 그리지 않고 본문을 한 칸으로 넓혔다. 모바일 메뉴 버튼(`.menu-toggle`)도 이 화면에서는 숨긴다(`.chrome.no-nav`).

### 변경 파일
- `src/shell/shell.tsx`, `src/shell/shell.css`

### 검증
- `tsc --noEmit` 통과. 브라우저에서 녹음 화면 `.sidenav` 없음·본문 한 칸, 홈 화면은 사이드바 그대로.

### 남은 작업
- 재생 중인 녹음 상세(스크린샷 화면)는 선택된 녹음이 있어야 열려 직접 보지 못했다(같은 `lecture` 화면이라 동일 적용).

## PR #6 머지 (audit3-followup)
### 작업 내용
- PR #6(#5 회귀 수정: 저장 뒤 편집기가 쓰기 전 캐시로 되돌아가는 문제)을 확인하고 `main`에 머지했다(머지 커밋 d1a1fb6). #5는 이미 머지된 상태였다.
- 확인: 변경 1파일(`src/shared/remote-cache.ts`, +42/-10), mergeable CLEAN, CI 설정 없음. 작성자가 본문에 남긴 브라우저 검증(자료·강의·퀴즈 연속 저장, 목록 재요청 실패)을 근거로 삼았고 이 세션에서 다시 실행하지는 않았다.

### 변경 파일
- `src/shared/remote-cache.ts` (PR #6 내용)

### 남은 작업
- `Resource.seed()`가 추가됐지만 이 PR에서는 호출하는 곳이 없다. 이후 사용처가 없으면 제거 검토.
- 로컬 `main`은 아직 pull하지 않았고, 작업 트리에 커밋되지 않은 다른 변경이 있다.

## PR #7 머지 (audit3-followup-2), PR #8 확인
### 작업 내용
- PR #8(Jenkins CI/CD)은 확인 시점에 이미 `main`에 머지돼 있었다(9a1dea3, yoobinkim541).
- PR #7(#5·#6 후속: 쓰기 뒤 지난 행 표시, seed 다시 받기, 인정 기준 시간 용어, 학습실 요약 중복)을 리뷰하고 `main`에 머지했다(머지 커밋 bee1df9). PR에 리뷰 코멘트를 남겼다.
- 리뷰에서 본 것: `remote-cache.ts` `Entry.previous`(성공 시 비움, 401/403/404 실패 시 비움)와 `shownRows`, 교수 목록들의 지난 행 표시·행 동작 끔, `room-server.tsx` 지난 행 공개 전환·삭제 끔, `community-remote.ts` create 뒤 `seed()` 사용(#6의 `seed()` 미사용 항목 해소), `course-recordings.ts` 같은 사유 한 번만. 막는 문제 없음.

### 변경 파일
- PR #7 내용(15파일). 이 세션에서 로컬 파일은 handoff.md만 고쳤다.

### 검증
- 임시 worktree에서 origin/main(9a1dea3)에 PR #7을 병합한 뒤 `npm ci`, `npm run typecheck`, `npx vite build` 통과(청크 크기 경고만). 브라우저 검증은 작성자 본문 근거, 이 세션에서 다시 하지 않았다.

### 남은 작업
- 로컬 `main`은 여전히 pull하지 않았다(커밋되지 않은 로컬 변경 41개 파일과 겹칠 수 있음).

## 홈 과목 카드 진행 주 문구 제거
### 작업 내용
- 샘플·서버 과목 카드에서 `진행 주` 라벨만 제거했다. 주차 수치와 진행 막대는 유지한다.

### 변경 파일
- `src/areas/home/course-cards.tsx`
- `handoff.md`

### 검증
- 실행 중인 개발 서버의 홈 화면을 브라우저로 확인했다. 과목 카드 7개에 `10 / 16 주`와 진행 막대(63%)가 유지되고, 카드 스크린샷에서 라벨 제거를 확인했다.

### 남은 작업
- 없음.

## 녹음 페이지 백엔드 연동 준비 상태 점검
### 작업 내용
- 사용자 질문에 따라 녹음 상세·서버 클라이언트·저장 데이터 변환을 정적으로 확인했다. 기능 코드는 변경하지 않았다.
- 녹음 업로드·단건 조회·원본 재생·삭제는 `/api/me/study-room` 클라이언트에 연결돼 있다. 실제 백엔드 요청 성공 여부는 이번 점검에서 확인하지 않았다.
- 스크린샷의 전사·요약은 `recording.tsx:438`에서 백엔드 `off`일 때만 넣는 `demoRecordingStudy()`다. 백엔드 모드에서는 `study=null`을 전달하며, `ServerRecording`에 전사·요약 필드도 없다. `RecordingStudy`는 화면용 타입이지 연결된 서버 응답 계약이 아니다.
- 다운로드의 `MP3 음성` 표시는 실제 변환과 일치하지 않는다. 현재는 원본 녹음 주소와 원본 확장자로 내려받으며, `전사문 TXT`는 비활성 상태다.

### 변경 파일
- `handoff.md`

### 검증
- `recording.tsx`의 상세 조회·다운로드·`study` 전달 분기, `recording-study.tsx`의 타입·빈 상태, `study-room-remote.ts`의 응답 타입·업로드·콘텐츠 URL, `course-recordings.ts`의 서버 응답 변환을 읽어 확인했다. 조사 작업이므로 테스트·브라우저 실행은 하지 않았다.

### 남은 작업
- 전사·요약 기능까지 백엔드 연동 완료 상태로 만들려면 실제 서버 계약 확인 후 생성/조회 API, 처리 상태·실패 표시, 결과 매핑, TXT 다운로드를 연결해야 한다. MP3 변환 제공 여부에 맞춰 다운로드 구현 또는 명칭도 맞춰야 한다. 이번 요청은 상태 확인이므로 구현 변경은 하지 않았다.

## 2026-11-14 히어로 배경 사진 느린 이동 애니메이션

### 작업 내용
- 홈 히어로 배경 캠퍼스 사진(`.home-hero::before`)에 `hero-drift` 애니메이션을 넣었다. 32초 동안 1.00 → 1.12배 확대 + 왼쪽 위로 약간 이동하고(`alternate infinite`), 글자 쪽 어두운 그라데이션(`::after`)은 고정이라 가독성은 그대로.
- 확대된 사진이 히어로 밖으로 나가지 않도록 `.home-hero`에 `overflow: clip`을 추가했다.
- `prefers-reduced-motion: reduce`에서는 기존 등장 애니메이션과 같이 꺼진다.

### 변경 파일
- `src/areas/home/home.css`

### 검증
- 1568px 일반 화면에서 `::before` transform이 시간에 따라 변하는 것(scale 1.0006 → 1.0049) 확인. 일반·전체화면 스크린샷에서 사진이 히어로 안에 잘려 보임.

### 남은 작업
- `overflow: clip`이 명령 입력창 추천 목록(`bottom: 100%`로 위로 열림)을 자르지 않는지 일반 화면에서 목록을 직접 열어 보지 못했다(전체화면에서는 여유 있음).
- 속도·확대율은 `32s`, `scale(1.12)` 값으로 조정.

## 2026-11-14 명령 입력창 "/" 배지 → 주황 원 위쪽 화살표 버튼

### 작업 내용
- 입력창 오른쪽의 `/` 키 배지를 없앴다. 자동완성 제안이 있을 때의 `Tab` 배지는 그대로 둔다(`ghost`일 때만 렌더).
- 실행 버튼(`.cmd-go`)을 주황(`--brand-orange`) 원 + 흰색 위쪽 화살표(SVG)로 바꿨고, 입력이 비어 있어도 항상 보이게 했다(기존엔 비면 숨김). 빈 입력으로 누르면 `submitCommand`가 바로 반환해 아무 일도 하지 않는다.

### 변경 파일
- `src/areas/home/command-bar.tsx`, `src/areas/home/home.css`

### 검증
- `npm run typecheck` 통과. 빈 입력·"오늘" 입력 두 상태 스크린샷에서 버튼 44px, 배경 rgb(255,110,5), 아이콘 흰색, `/` 배지 없음 확인.

### 남은 작업
- 흰색 아이콘 대비는 주황 위 약 2.8:1(`.hero-plan-accept`가 어두운 글자를 쓰는 이유와 같다). 대비를 올리려면 아이콘 색을 `--on-brand-orange`로.
- 하단 도크 입력창(`.chat-go`, 파란 → 버튼)은 이번에 건드리지 않았다.

## 2026-11-14 히어로 입력창 위쪽 화살표 버튼 제거

### 작업 내용
- 바로 앞에 넣은 주황 원 위쪽 화살표 실행 버튼(`.cmd-go`)을 히어로 입력창에서 완전히 없앴다. 마크업·CSS(`.cmd-go` 규칙, `is-busy` 숨김 규칙의 `.cmd-go`)도 함께 삭제. `/` 배지는 이미 없고 `Tab` 배지만 남는다.
- 버튼이 사라진 오른쪽 여백은 `.cmd-box` 오른쪽 padding을 `space-2` → `space-5`로 넓혀 왼쪽과 맞췄다.
- 제출은 Enter(기존 `submitCommand` 호출)로만 한다.

### 변경 파일
- `src/areas/home/command-bar.tsx`, `src/areas/home/home.css`

### 검증
- `npm run typecheck` 통과. 스크린샷·DOM에서 `.cmd-go` 없음 확인. Enter 후 입력값이 비워지는 것까지만 확인(계획 요약이 뜨는지는 확인 못 함: "오늘" 한 단어라 계획으로 해석되지 않았을 수 있음).

### 남은 작업
- 터치 기기에서 가상 키보드의 전송/Enter 키에 의존한다. 화면 버튼이 필요하면 다시 넣어야 한다.

## 녹음 화면: 백엔드 생성 전사·요약 수신 준비
### 작업 내용
- 사용자 지시대로 **백엔드 코드는 구현·수정하지 않았다.** 프론트 파일과 프론트 연동 명세만 변경했다. 실제 서버 지원 여부와 구분해 `README.md`에 수신 계약을 기록했다.
- 기존 녹음 상세 응답의 선택 필드 `transcription`·`summary`를 화면까지 전달한다. 공통 타입은 `src/shared/recording-study.ts`에 두고 UI 내부의 기존 전사·요약 타입을 대체했다.
- 각 작업의 `queued`·`processing`·`ready`·`failed` 상태를 독립적으로 표시한다. 검사 중이거나 생성 작업 진행 중일 때만 4초 간격으로 상세를 조회하고, 완료·실패·미제공이면 멈춘다. 조회 실패는 재생기를 유지하면서 명시적으로 표시하고 수동 조회 재개를 제공한다.
- 전사 완료 시 서버가 준 `result.text` 그대로 TXT를 내려받는다. 아직 전사가 없거나 음성 검사 중·거부이면 TXT를 비활성화한다. 서버 결과가 없어도 샘플로 대체하지 않으며, 기존 오프라인 샘플은 샘플로 유지한다.
- 원본 파일 다운로드의 잘못된 `MP3 음성` 명칭을 `원본 음성`으로 고쳤다. 사용자 변경 시 녹음 상세를 remount해 이전 결과·재생 상태를 비운다.

### 변경 파일
- `src/shared/recording-study.ts` (신규 수신 타입)
- `src/shared/study-room-remote.ts`, `src/shared/course-recordings.ts`
- `src/areas/home/recording.tsx`, `src/areas/home/recording-study.tsx`
- `tests/integration/recording-study.test.ts`
- `README.md`, `handoff.md`

### 검증
- `npm run typecheck` 통과.
- `npx vitest run tests/integration/recording-study.test.ts`: 5개 통과. 대기→처리→완료와 조회 중단, 재생 위치 유지·전사 시각 이동, 요약과 독립적인 TXT 원문 저장, 미제공 서버의 샘플 차단, 검사 중·거부 결과 차단, 갱신 실패 후 수동 조회를 확인했다.
- 실행 중인 Vite 페이지에서 검증용 API 응답을 브라우저 네트워크 계층에 공급해 전사·요약 완료 화면과 다운로드 메뉴를 확인했다. TXT 실제 다운로드(115바이트)와 두 줄 원문을 확인했다. 이 검증은 실제 백엔드 연동 검증이 아니다.
- 브라우저 자동 검증의 초기 대기는 StrictMode 중복 조회와 도구의 짧은 선택자 대기로 실패했다. 최종 완료 화면·다운로드는 별도로 확인했다. 검증용 WAV의 seekable 범위가 `[0,0]`이라 브라우저에서 실제 음원 시각 이동까지 증명하지 못했다(동작 단위 테스트는 통과). 앱 코드를 이 검증 fixture에 맞춰 바꾸지는 않았다.

### 남은 작업
- 백엔드 담당자가 `README.md`의 선택 필드 계약을 지원해야 실제 생성 결과가 표시된다. 처리 예정이면 첫 상세 응답부터 `queued`/`processing`을 보내야 자동 조회가 이어진다. 이번 작업에는 백엔드 생성·저장·API 구현이 포함되지 않는다.

## 2026-11-14 커밋·푸시 (origin/main 병합)

### 작업 내용
- 세 터미널(BENTO·SCRIPT·LESSON)이 모두 idle인 것을 확인하고 작업 트리 전체를 한 커밋으로 묶었다. 로컬 main이 origin/main보다 21커밋 뒤처져 있어 `git merge origin/main`(충돌 없음) 후 푸시.
- 병합 후 `tests/unit/remote-cache.test.ts` 2건이 실패: 원격에서 `Entry`에 `previous` 필드가 추가돼 `toEqual`이 맞지 않았다. 기대값에 `previous: null`을 추가해 수정.

### 검증
- `npm run typecheck` 통과, `npm test` 1320건 통과(병합 후).

## 2026-11-14 e-Class 로그인 홈 UI를 목업과 통일

### 작업 내용
- 로그인 `ready`에서만 홈을 별도 달력 화면으로 교체하고 히어로 입력창·전체화면 전환을 제거하던 분기를 없앴다. 로그인 전후 같은 히어로, 명령 입력, 전체화면 전환, 나의 하루, 수강과목 카드 구성과 CSS를 사용한다. `cal=month`가 남은 주소도 홈 달력을 렌더하거나 달력 API를 요청하지 않는다.
- 요약 카드 표시 컴포넌트를 공용화했다. 로그인 요약·인사·필터 팝업은 서버 할 일 캐시와 기관 시간대의 서버 시각을 사용한다. 마감·과제·주간 마감 자료를 분류하고, 다음 페이지가 있으면 `N+`와 부분 집계 안내를 표시하며 기존 더 보기로 이어서 조회한다. 데이터나 서버 시각이 없을 때 0건이나 목업으로 채우지 않는다.
- 서버 수강과목은 과거 학기까지 유지하고 기존 학습실 입장 링크의 서버 `kj`를 보존했다. 홈에만 추가되던 최근 공지 목록은 제거했으며 공지 화면과 서버 공지 처리 경로는 유지했다. 시간표와 우측 할 일도 서버 데이터를 유지했다.
- 로그인 히어로의 다음 행동·추천·자동완성·명령을 실제 서버 과목과 활동 URL로 연결했다. 샘플 계획 대신 서버 할 일 목록을 제공한다. 자동 실행·제출을 지원하는 것처럼 표시하지 않으며 사용자 전환 시 입력과 안내·목록 상태를 초기화한다.
- 학습실·업로드·녹음·서버 구현은 변경하지 않았다.

### 변경 파일
- `src/areas/home/home.tsx`, `src/areas/home/home-server.tsx`
- `src/areas/home/summary-card.tsx` (공용 카드)
- `src/areas/home/calendar-server.tsx`, `src/areas/home/dialogs.tsx`
- `src/areas/home/command-bar.tsx`
- `src/shared/assistant/classrooms.ts`, `src/shared/assistant/server-home.ts`
- `src/shared/backend-ui.css` (제거한 홈 달력의 사용하지 않는 선택자 정리)
- `tests/integration/home-layout.test.ts`, `tests/integration/server-hero-commands.test.ts`
- `README.md`, `handoff.md`

### 검증
- `npm run typecheck` 통과.
- `npx vitest run tests/integration/home-layout.test.ts tests/integration/server-hero-commands.test.ts tests/integration/assistant-resolve.test.ts`: 3개 파일, 59개 테스트 통과. 서버 시각·주 경계, 부분 집계·실패·시각 미제공, 실제 서버 과목/이전 학기/학습실 링크, 목업 유지, 명령과 사용자 전환을 확인했다.
- 별도 개발 서버(4194)와 브라우저 API fixture로 e-Class 로그인 폼 제출→서버 계정 홈 전환을 확인했다. 월간 주소에서도 요약 카드 3개·입력창이 유지되고 달력 DOM 및 `/api/me/calendar` 요청은 0건이었다. 1440px 일반·전체화면과 390px 화면 스크린샷을 확인했고 모바일 가로 넘침이 없었다. 요약 카드의 과제 목록에는 서버 과제만 표시됐다.
- 홈 카드에서 `study_form.acl?kj=c901`로 이동하고 `/api/me/study-room/courses/901`을 요청하는 것을 확인했다. 학습실 전체 폴더 표시 검증은 완료하지 못했다. 초기 fixture가 자료·강의 API를 404로 응답했고, 보완 후에도 지정한 폴더 문구 대기가 실패했다. 이 별도 학습실 경로를 맞추려고 제품 코드를 변경하지 않았다.
- 초기 개발 중 JSX 닫는 태그 오류로 빈 화면이 발생해 수정했다. 최종 타입 검사·관련 테스트·홈 화면 검증은 수정 후 결과다. 전체화면 직후 두 번째 클릭은 View Transition 중 차단되어 동작 줄이기 설정에서 일반 화면 복귀를 별도로 확인했다.

### 남은 작업
- 실제 학교 계정과 운영 백엔드로 로그인·서버 자료를 재확인해야 한다. 이번 브라우저 검증은 네트워크 fixture 기반이며 실제 학교 인증 성공이나 운영 학습실 데이터 수신을 증명하지 않는다.

## 2026-11-14 녹음 학습 화면 디자인 방향 상담

### 작업 내용
- 첨부 화면을 기준으로 univAI를 연상시키는 구성에 대한 개선 방향을 검토했다. 별도 univAI 원본과의 대조는 하지 않았다.
- 사용자 확정 조건: **좌측 상단 재생 플레이어·하단 전사문, 우측 요약** 구성을 유지한다.
- 앞서 제안한 중앙 노트 중심 재배치·전사문 접이식 전환·하단 재생바 이동은 적용하지 않는다.
- 이 구조 안에서 플레이어 표면·컨트롤, 전사문 활성 표시, 요약의 문서형 위계·시간 링크, 브랜드 강조색과 여백으로 시각적 차별화를 제안한다. 아직 UI 구현 요청이나 변경은 없다.

### 변경 파일
- `handoff.md`만 변경. UI 코드 변경 없음.

### 검증
- 첨부 화면과 README의 제품 맥락을 확인했다. 동작 변경이 없어 실행·테스트하지 않았다.

### 남은 작업
- 사용자가 실제 구현을 요청하면 기존 녹음 상세 컴포넌트와 반응형 동작을 확인한 뒤 적용한다.

## 가상 로그인 상태 미리보기 실행
- 요청에 따라 `http://127.0.0.1:4195/`에 별도 가상 백엔드·개발 서버(`virtual-login-preview`)를 실행하고, 보이는 브라우저 탭 `가상 로그인 홈`을 열어 유지했다. 실제 학교나 운영 백엔드는 호출하지 않는다.
- 가상 학생 로그인 상태, 수강과목 6개, 할 일 4개, 홈 달력 없음과 정상 화면을 브라우저에서 확인했다. 새로고침도 별도 서버의 가상 로그인 응답을 사용한다.
- 변경 파일: `handoff.md`만. 앱 코드·인증 설정은 변경하지 않았으며 미리보기 서버 코드는 프로세스 메모리에만 있다.
- 남은 작업: 미리보기 사용을 마치면 `virtual-login-preview` 서비스를 종료한다. 제공하지 않는 API는 가상 성공 대신 `501 preview_only`를 반환한다.

## 2026-11-14 다운로드 메뉴에 "요약 PDF" 추가

### 작업 내용
- 다운로드 메뉴(`DownloadMenu`, `recording.tsx`)에 **요약 PDF** 항목을 전사문 TXT 아래에 추가했다. 서버 요약이 `ready`인 녹음에서만 활성, 그 외(없음·처리 중·실패)는 "준비 중" 비활성 표시.
- `src/areas/home/recording-summary-pdf.ts` 신규: `summaryDocument()`가 제목·개요·구간(시각·핵심어·설명·표)·메모를 담은 HTML을 만들고(서버 텍스트는 전부 이스케이프), `printSummaryPdf()`가 숨은 iframe에 그려 브라우저 인쇄 창을 연다. 사용자가 "PDF로 저장"을 고르면 파일이 만들어진다.
- 라이브러리를 추가하지 않은 이유: 한글 글꼴 임베딩이 필요한 PDF 라이브러리는 글꼴 파일·번들 크기 부담이 크고, 인쇄 방식은 한글·텍스트 선택이 그대로 유지된다. 대신 파일이 바로 내려가지 않고 인쇄 창을 한 번 거친다.

### 변경 파일
- `src/areas/home/recording.tsx`, `src/areas/home/recording-summary-pdf.ts`(신규), `tests/unit/recording-summary-pdf.test.ts`(신규), `tests/integration/recording-study.test.ts`(비활성 항목 개수·이름 갱신), `handoff.md`

### 남은 작업
- 실제 브라우저에서 인쇄 창→PDF 저장 결과(페이지 나눔, 글꼴)는 확인하지 못했다(jsdom은 print 미지원). 바로 내려받는 방식이 필요하면 백엔드 PDF 엔드포인트나 PDF 라이브러리+글꼴 임베딩이 필요하다.

## 서버 과목 카드 커버 배경 복원
- 원인: `coverTemplate`이 없는 서버 과목에서 `courseCover()`가 이미지 없이 기본 그라데이션만 반환해 목업과 다른 배경이 표시됐다.
- 수정: 템플릿이 없으면 서버 과목 ID로 기존 목업 로컬 이미지 세트를 안정적으로 선택한다. 명시된 서버 템플릿은 그대로 존중하며 서버 과목·학습실·활동 데이터는 변경하지 않았다.
- 변경 파일: `src/shared/course-view.ts`, `tests/unit/course-cover.test.ts`, `handoff.md`.
- 검증: 커버·홈 회귀 테스트 8개 및 타입 검사 통과. 가상 로그인 브라우저에서 과목 6개의 이미지 로딩과 원형 기본 장식 제거를 확인했고 카드 스크린샷을 확인했다.
- 남은 작업: 사용자가 추가로 요청한 강의실 본문·공통 셸의 목업 레이아웃 통일은 이어서 진행한다.

## 2026-11-14 녹음 학습 화면 시각 개선

### 작업 내용
- 사용자 확정 배치(좌측 상단 플레이어·하단 전사문 / 우측 요약)와 기존 1100px 반응형 분기, 재생·배속·시간 이동·다운로드 계약을 유지했다.
- 플레이어의 둥근 카드 배경·외곽선을 없애고 전사와 연결되는 도구 영역으로 정리했다. 재생 버튼은 48px의 둥근 사각형, 진행 색은 기존 브랜드 주황을 쓴다.
- 전사 제목·구간 이동 안내를 추가하고 전체 행 색칠을 주황 활성선·시간 밑줄·본문 대비로 바꿨다. 스크롤·자동 따라가기 로직은 변경하지 않았다.
- 우측은 하나의 문서 면에 `강의 요약`, 16px 본문·1.75 줄간격, 절 사이 여백, 작은 밑줄 시간 링크로 정리했다. 표 머리글을 구분하고 모바일에서는 표 내부만 가로 스크롤되도록 최소 너비 520px을 지정했다.
- 기존 Pretendard와 테마 토큰을 재사용했고 공유 lesson-viewer 스타일이나 다른 화면은 수정하지 않았다.

### 변경 파일
- `src/areas/home/recording-study.tsx`
- `src/areas/home/recording-study.css`
- `handoff.md`

### 검증
- `npm run typecheck` 통과.
- `npx vitest run tests/integration/recording-study.test.ts`: 5개 통과.
- 실행 중인 4190 개발 서버의 실제 녹음 상세에서 라이트·다크 데스크톱과 다크 모바일 스크린샷을 확인했다. 오프라인 모드의 기존 샘플 전사·요약을 사용했으며 실제 서버 생성 결과 검증은 아니다.
- 브라우저에서 배속 1.25, 실제 오디오 재생(`paused: false`), 전사·요약 시간 클릭, 방향키로 재생 위치 0.1초 이동·포커스 표시, 다운로드 메뉴를 확인했다. 사용한 기존 음원은 1초여서 긴 구간 클릭은 정상적으로 끝(1초)으로 제한됐다.
- 320·375·414·768·1440px에서 페이지 가로 넘침 없음, 플레이어→전사 순서 유지, 1440px 좌우 배치 유지 확인. 모바일 표는 520px을 유지하면서 내부 `overflow-x: auto`로 수용한다. 마지막 브라우저 오류 목록은 비어 있었다.

### 남은 작업
- 요청 범위의 미구현 항목 없음. 장시간 실제 강의 음원의 재생·시간 동기화는 이번 짧은 음원 브라우저 검증 범위 밖이다.

## 2026-11-14 녹음 화면 원복 — 주황 포인트만 유지
- 사용자 요청에 따라 바로 앞 시각 개선을 원복했다. 기존 플레이어 카드·56px 원형 재생 버튼·전사 행 강조·간격·요약 제목·구분선·시간 배지·표·반응형 스타일로 되돌렸다.
- 추가했던 전사 제목/안내, 요약 문서 배경, 좌측 세로 구분선, 모바일 표 최소 너비를 제거했다. 주황 진행바와 주황 계열 활성/호버 색만 유지한다. 글자용 주황은 기존 텍스트 토큰과 혼합해 라이트·다크에 맞춘다.
- 변경 파일: `src/areas/home/recording-study.tsx`, `src/areas/home/recording-study.css`, `handoff.md`. 다른 작업의 변경은 건드리지 않았다.
- 검증: 녹음 통합 테스트 5개 통과. 브라우저 다크 1440px 스크린샷과 실제 스타일에서 원형 버튼·카드 테두리·투명 요약 배경·14px 본문·절 구분선을 확인했다. 라이트 390px은 단일 열·가로 넘침 없음 확인.
- `npm run typecheck`는 `tests/integration/server-chrome.test.ts`의 TS2322(`TimetableSlot`에 `startTime`, `endTime` 누락)로 실패했다. 녹음 원복 범위 밖 파일이므로 수정하지 않았다. 앞 명령의 `&&`로 건너뛴 녹음 테스트는 별도 실행해 통과했다.
- 남은 작업: 녹음 화면 원복 요청은 완료. 위 다른 파일의 타입 오류는 남아 있다.

## 2026-11-14 로그인 강의실·공통 셸 목업 레이아웃 통일
- 요청: e-Class에서 파싱한 데이터는 유지하고 로그인 후에도 목업과 같은 레이아웃을 사용한다.
- 강의실: `RoomLayout`, `CourseActivitySection`, `CourseEmpty`, `CompactActivityRow`를 목업·서버가 공유한다. 서버 전용 사진/그라데이션 헤더·공지/할 일 strips·별도 활동 행/빈 화면을 제거했다. 과목 정보·활동 종류·실제 주차 수·본인 완료/진도/제출 상태·서버 상세 링크는 서버 어댑터가 전달한다.
- 공통 셸: 좌측 고정 숫자 14/2를 실제 주차 수·미확인 공지 수로 바꿨다. 우측 수업/할 일은 목업과 같은 직접 자식 카드 구조이며 서버 전체 건수와 미리보기 4개를 표시한다. 시간 미제공 시 `시간 미정`을 표시한다.
- 로그인 후 본문마다 추가되던 계정/로그아웃 안내 행을 제거했다. 익명 로그인 폼과 상단 계정 메뉴는 유지한다. 학습실·폴더·자료·녹음 API는 변경하지 않았다.
- 변경 파일: `src/areas/course/course.tsx`, `src/areas/course/activities-server.tsx`, `src/areas/course/room-view.tsx`(신규), `src/areas/course/course.css`, `src/shared/activity-row.tsx`, `src/shell/shell.tsx`, `src/areas/home/home-server.tsx`, `src/areas/home/calendar-server.tsx`, `src/areas/home/home.css`, `src/shared/backend-prompt.tsx`, `src/shared/backend-ui.css`, `tests/integration/course-room-layout.test.ts`(신규), `tests/integration/server-chrome.test.ts`(신규), `tests/integration/backend-prompt-layout.test.ts`(신규), `README.md`, `handoff.md`.
- 검증: 최종 `npm run typecheck` 통과. 강의실 회귀 10개·공통 셸 회귀 8개·인증 폼 회귀 3개 통과. 같은 검증 실행의 기존 홈·서버 명령·과목 커버 테스트 파일도 통과했다. 초기 테스트의 누락 시간표 필드/불완전한 모킹/날짜 연도 문구 의존을 수정했다. 위 녹음 원복 기록의 `server-chrome.test.ts` 타입 오류도 해결됐다.
- 실제 브라우저: 가상 API를 기존 `server.mjs` 프록시로 연결해 라이트 데스크톱·다크 모바일/데스크톱을 확인했다. 16주/8주·긴 제목·과제 필터·주차 선택·강의계획서 팝업·서버 과제 상세 이동·빈 활동·자료 API 503 시 다른 활동 유지 및 재시도 복구를 확인했다. 390px에서 페이지 가로 넘침 없이 주차 막대 내부만 스크롤된다.
- 학습실 검증: 홈의 학습실 입장을 실제 클릭해 `kj=c901`이 유지되고 `/api/me/study-room/courses/901` 요청으로 가상 서버 폴더가 표시되는 것을 확인했다. 학교 서버나 실제 사용자 데이터로 검증한 것은 아니다.
- 실행 상태: `virtual-layout-rich`가 `http://127.0.0.1:4196`에서 동작한다. 사용자에게 보이는 `가상 로그인 홈` Chromium 탭을 해당 서버의 데이터베이스 강의실(`kj=c901`)로 이동해 두었다. 가상 백엔드·데이터는 실행 프로세스 메모리에만 있으며 임시 실행 파일은 삭제했다. 기존 단순 미리보기 4195와 다른 작업의 개발 서버 4190은 종료하지 않았다.
- 남은 작업/한계: 요청한 홈·과목 커버·강의실·공통 셸 변경은 완료. 실제 학교 e-Class 로그인 세션과 운영 파싱 응답 검증은 하지 않았으며, 가상 서버의 쓰기/실제 영상 재생을 운영 기능 검증으로 간주하지 않는다.

## 로그인 UI 수정 커밋·푸시
- 사용자 요청에 따라 홈·과목 커버·강의실·공통 셸 수정과 관련 테스트·문서를 커밋해 `origin/main`에 푸시한다.
- 별도 녹음 화면·요약 PDF 구현 및 해당 테스트 파일은 이번 커밋에서 제외하고 작업 트리에 유지한다. 공용 README/handoff에는 기존 다른 작업 기록도 보존되어 있다.
- 앞 절의 타입·회귀·브라우저 검증 결과를 사용하며 커밋 요청으로 동일 검사를 반복하지 않는다.

## 수강과목 카드의 안 읽은 공지 표시 제거
- 사용자 스크린샷의 빨간 `안 읽은 공지 N` 표시를 수강과목 카드에서 제거했다. 전용 옵션·공지 맵 생성·미사용 CSS도 삭제했다.
- 변경 파일: `src/areas/home/course-cards.tsx`, `src/areas/home/home-server.tsx`, `src/areas/home/home.css`, `handoff.md`.
- 서버 미확인 공지 데이터와 강의실 메뉴의 공지 개수는 변경하지 않았다. 진도·출석·학습실 링크는 유지한다.
- 검증: `npm run typecheck` 및 홈 회귀 테스트 4개 통과. 가상 로그인 브라우저에서 서버 응답의 과목별 미확인 공지 3건은 유지되지만 6개 카드의 공지 표시가 모두 사라졌고 진도·출석·과목별 학습실 링크가 유지됨을 확인했다.
- 남은 작업: 없음. 이번 추가 변경은 아직 커밋·푸시하지 않았다.

## 2026-11-14 강의실의 빈 활동 화면 원인 분석
- 요청: 첨부한 `등록된 활동이 없습니다` 화면을 프런트엔드와 `STack-Corp/STack-Backend` 코드까지 대조해 설명한다. 기능 수정 요청은 없어 앱 코드는 변경하지 않았다.
- 분석한 백엔드: `main` 커밋 `36261894b2dd86e161f393b7015652f343cc352f`. 공개 URL 읽기는 404였지만 인증된 GitHub CLI로 접근해 임시 디렉터리에 소스를 확보했다.
- 프런트 `src/areas/course/activities-server.tsx:214-255`: 주차·자료·과제·시험·설문·본인 영상 진도를 모은 결과의 활동 행이 0개이고 로딩 중이 아니면 해당 문구를 표시한다. 필터로만 항목이 사라진 경우에는 별도의 `해당하는 활동이 없습니다` 문구를 쓴다. 일부 API 실패는 별도 오류 안내를 함께 표시할 수 있으므로 이 문구 자체가 정상 빈 응답을 증명하지는 않는다.
- 백엔드 `integration/eclass/EclassPasswordVerifier.java:93-147`: 학교 로그인 후 메인 화면의 프로필·수강과목과 개인정보 화면만 읽는다. 학교 세션은 확인 요청 범위에서만 사용하고 보관하지 않는다. 과목별 강의실 활동 수집은 이 흐름에 없다.
- 백엔드 `catalog/SchoolCourseSync.java:60-157`: 과목·학기·시간표·수강 관계만 저장한다. `course_weeks`와 `learning_activities`는 생성하지 않는다. `learning/CourseWeeks.java:23-34`는 자체 활동 등록 시 필요한 주차를 생성하는 구성이다.
- 백엔드 `learning/LearningController.java:102-142`: `/api/courses/{courseId}/weeks`는 자체 DB의 `course_weeks LEFT JOIN learning_activities`만 조회한다. 학교 e-Class를 호출하지 않는다. 학생 활동은 `LearningAccess.open`에 따라 공개 상태이고 시작 시각이 없거나 현재 이하인 경우만 포함하며 삭제된 활동은 제외한다.
- 결론: 학교 수강과목이 보이는 것과 학교 활동이 동기화되는 것은 별개다. 현재 학교 연동에는 활동 동기화가 없어, 자체 등록 활동이 없는 동기화 과목은 이 빈 화면으로 이어질 수 있다. `교수님이 활동을 공개하면`은 프런트의 고정 안내문이지 교수의 학교 활동 미공개를 확인한 결과가 아니다. 다시 불러오기는 같은 자체 DB API를 재조회할 뿐 학교 활동을 가져오지 않는다.
- 검증: 양쪽 구현과 SQL·분기를 직접 대조했다. 분석 작업이며 동작 변경이 없어 빌드·테스트·UI 실행은 하지 않았다.
- 변경 파일: `handoff.md`만.
- 남은 확인/작업: 해당 사용자의 실제 네트워크 응답·운영 DB·배포 커밋은 확인하지 않았으므로 이 과목의 정확한 저장 상태는 단정하지 않는다. 학교의 실제 활동 표시가 목표라면 백엔드에서 학교 인증 세션 사용 범위·권한·첨부 접근을 설계하고 과목별 주차·활동을 수집해 기존 활동 API와 연결하는 구현이 필요하다.

## 2026-11-14 최초 방문 로그인 모달
- 서버 익명 세션 확인 후 탭당 한 번 `STack 로그인` 모달을 자동으로 연다. ID(학번)·PW 입력과 실제 요청은 기존 e-Class 로그인 폼/API를 재사용한다.
- 표시 여부만 `sessionStorage`에 저장한다. 새로고침·화면 이동에는 재표시하지 않고 로그인 복원 세션과 백엔드 없는 샘플 모드에서는 생략한다. 닫기·배경 클릭·Escape를 지원하고 기존 경로의 팝업 주소는 변경하지 않는다.
- 기존 모달·테마 토큰을 재사용한 420px 세로 폼과 모바일 여백을 적용했다. 최초 ID 포커스, 모달 내 Tab 순환, 배경 스크롤 잠금·닫은 뒤 포커스 복원을 제공한다.
- 비밀번호 로그인 실패 뒤 비활성 입력에 너무 일찍 포커스를 주던 기존 폼을 수정했다. 입력이 다시 활성화된 React 커밋 뒤 PW로 포커스를 옮긴다. 비밀번호 저장은 추가하지 않았다.
- 변경 파일: `src/App.tsx`, `src/shared/entry-login.tsx`(신규), `src/shared/backend-prompt.tsx`, `src/shared/backend-ui.css`, `tests/integration/entry-login.test.ts`(신규), `README.md`, `handoff.md`.
- 검증: 최종 `npm run typecheck` 통과. 진입 모달·기존 인증 폼 통합 테스트 7개 통과(StrictMode, 익명 전환, 같은 탭 재마운트, 로그인 복원, 실패·성공, Escape·배경·Tab·포커스).
- 실제 Chromium 개발 화면에서 가상 API로 최초 자동 표시·ID/PW 요청·실패 안내·비밀번호 비우기·성공 시 닫힘·익명 새로고침 후 미표시를 확인했다. 데스크톱·390px 모바일 스크린샷에서 폼 표시와 가로 넘침 없음을 확인했다. 마지막 브라우저 검증에서 ID 최초 포커스와 실패 뒤 PW 포커스·Escape 닫힘을 확인했다. 학교 계정으로 운영 로그인한 검증은 아니다.
- 남은 작업: 요청 범위 없음. 변경은 커밋·푸시하지 않았다.

## 2026-11-14 강의실·활동 이동 안내 제거
- 요청한 `… 강의실로 이동` 형태의 이동 후 안내를 제거했다. 서버 강의실·활동 명령과 목업/AI 이동 명령이 목적지에 도착한 뒤 챗봇 도크에 이동 문구를 남기지 않는다.
- 오류·지원하지 않는 명령·계획 확인 안내와 실제 이동·자동완성은 유지한다. 전역 저장/오류 토스트를 일괄 숨기지는 않았다.
- 변경 파일: `src/areas/home/command-bar.tsx`, `tests/integration/server-hero-commands.test.ts`, `handoff.md`.
- 검증: `npm run typecheck`와 서버 히어로 명령 회귀 테스트 10개 통과. 기존 강의실·활동 이동 테스트에 도크 안내가 비어 있는지 검증을 추가했다.
- 실제 브라우저에서 백엔드 비활성 응답을 사용해 `기계학습 강의실로 이동` 추천을 클릭했고 강의실 주소 이동·비어 있는 도크 안내·화면 스크린샷을 확인했다. 실제 학교 서버 응답 검증은 아니다.
- 남은 작업: 없음. 커밋·푸시하지 않았다.

## 로그인 모달·이동 안내 변경 커밋 및 푸시
- 사용자 요청에 따라 최초 방문 로그인 모달과 강의실·활동 이동 안내 제거를 커밋하고 `origin/main`에 푸시한다.
- 포함 파일: `README.md`, `src/App.tsx`, `src/shared/entry-login.tsx`, `src/shared/backend-prompt.tsx`, `src/shared/backend-ui.css`, `src/areas/home/command-bar.tsx`, `tests/integration/entry-login.test.ts`, `tests/integration/server-hero-commands.test.ts`, 해당 작업의 `handoff.md` 기록.
- 별도 과목 카드·녹음·요약 PDF 변경과 다른 작업의 handoff 기록은 작업 트리에 그대로 남기고 이번 커밋에서는 제외한다.
- 검증은 앞선 타입 검사·통합 테스트·브라우저 확인 결과를 사용하며, 커밋 요청으로 동일 검사를 반복하지 않는다.

## 남은 변경 전체 커밋
- 사용자 요청에 따라 앞서 제외했던 과목 카드 공지 표시 제거·녹음 화면 주황 포인트·요약 PDF 다운로드 및 관련 테스트·기록을 모두 커밋한다.
- 변경 파일: `src/areas/home/course-cards.tsx`, `src/areas/home/home-server.tsx`, `src/areas/home/home.css`, `src/areas/home/recording-study.css`, `src/areas/home/recording.tsx`, `src/areas/home/recording-summary-pdf.ts`, `tests/integration/recording-study.test.ts`, `tests/unit/recording-summary-pdf.test.ts`, `handoff.md`.
- 이번 요청은 기존 변경의 커밋만 수행하며 기능을 추가 수정하거나 검사를 반복하지 않는다. 각 작업의 검증·한계는 앞선 기록을 따른다.
- 남은 작업: 이번 요청에는 푸시가 포함되지 않아 원격 푸시는 수행하지 않는다.

## 남은 변경 원격 푸시 완료
- 후속 사용자 요청에 따라 전체 변경 커밋 `2a47193`을 `origin/main`에 푸시했다. 원격 갱신 `d851254..2a47193` 성공을 확인했다.
- 이번 요청의 변경 파일: 푸시 결과를 기록한 `handoff.md`만. 기능 코드는 변경하지 않았다.
- 남은 작업: 없음.

## 목업(샘플) 모드 로그인 상태 브라우저 실행
- 사용자 요청에 따라 `ASTRA_API_ORIGIN` 없는 샘플 모드로 브라우저를 띄웠다. 이미 실행 중이던 `node server.mjs --dev`(PID 29620, `http://127.0.0.1:4190/`)를 그대로 사용했고 새 서버는 시작하지 않았다.
- 샘플 모드(`backendStatus === "off"`)는 로그인 모달 없이 학생 계정(박민준) 홈이 열린다. `/ilos/main/main_form.acl?as=st`에서 로그인 모달 0개, 홈 렌더링을 스크린샷으로 확인했다.
- 브라우저: 관리형 Chromium 탭 `mock`(1440x900, `persist`). 앱 상태가 `localStorage`에 복원되어 처음엔 마지막 화면(자료 요약)이 열려 홈으로 이동시켰다.
- 변경 파일: `handoff.md`만. 코드 변경 없음.
- 남은 작업: 없음.

## 첨부파일 행 개편: 왼쪽 파일 형식 아이콘, 오른쪽 아이콘 버튼 3개
- 자료 상세의 첨부파일을 한 줄 행으로 바꿨다. 왼쪽에 파일 형식 아이콘(PDF는 빨강 띠)·이름·크기, 오른쪽에 미리보기(돋보기)·내려받기(다운로드)·학습실 저장(파란 업로드 아이콘) 아이콘 버튼만 둔다. 종류·"PDF ·" 접두어는 아이콘으로 옮겼다.
- 학습실 버튼은 기존 `SaveToStudy`의 `variant="icon"`이다. 누르면 기존 폴더 고르는 창이 열리고 서버 학습실이면 `PUT …/placements/course-files/{fileId}`, 샘플이면 목업 저장이다. 저장되면 아이콘이 체크로 바뀐다.
- `FileRow`에서 "요약 보기"·"학습실에서 열기" 버튼을 뺐다(요청: 세 버튼만). 아래 "e-Class에서 열기 / 파일모음 / 학습활동 목록" 이동 줄은 그대로 둔다.
- 학교 e-Class 파일(`SchoolFileRow`)은 미리보기·내려받기 아이콘만 있다. 학습실 버튼은 두지 않았다: 복사 전 ID가 문자열(`school-file-<n>`)이라 학습실 배치 API(숫자 파일 ID, 과목 학습실 view에 있는 PDF만)가 받지 못하고, 학습실에 임의 문서를 올리는 API는 녹음(`…/recordings`)뿐이다. 이 부분은 백엔드 계약이 필요하다.
- 변경 파일: `src/shared/attachment-row.tsx`·`attachment-row.css`(새), `src/app.css`, `src/shared/save-to-study.tsx`(`SaveTrigger`, `variant`), `src/shared/uploads-remote.tsx`(`downloadControl` 세 번째 인자 `"icon"`), `src/shared/school-files.tsx`, `src/areas/learning/activity-server.tsx`(`FileRow`), `src/areas/learning/learning.tsx`(샘플 `fileRow`).
- 검증: `npx tsc` 오류 없음, `npx vitest run` 33파일 1452개 통과, 샘플 모드 `m=lecture_material&n=m-1&kj=bi`에서 행 표시와 학습실 저장 창 열림 확인. 백엔드 연결 모드의 실제 화면은 확인하지 못했다.
- 남은 작업: 학교 파일의 학습실 저장(백엔드 계약 필요), 파일모음(`crs-file`) 행을 같은 아이콘 버튼으로 맞출지 결정. 커밋·푸시는 하지 않았다.

## 홈 명령창 안내 문장 자동 사라짐
- 홈 히어로·챗봇 도크 명령창의 안내 문장("서버 할 일 목록에서 활동을 선택해 주세요. …" 등 `command-bar.tsx`의 `sayText`)이 5초(`SAY_MS`) 뒤 저절로 지워진다. 그릴 때(`currentSay()`) 새 문장이 보이기 시작하면 타이머를 걸고, 같은 문장은 한 번만 건다. 문장만 지우고 입력값·할 일 목록·계획 미리보기는 그대로 둔다. 그 사이 다른 문장으로 바뀌면 새 문장이 자기 타이머를 가진다.
- 다른 화면(계정·알림·커뮤니티 등)의 별도 `say` 함수는 바꾸지 않았다.
- 변경 파일: `src/areas/home/command-bar.tsx`, `tests/integration/server-hero-commands.test.ts`(새 테스트 1개: 5초 직전엔 보이고 5초에 지워지며 목록은 유지).
- 검증: `npx tsc` 오류 없음, `npx vitest run` 33파일 1453개 통과. 브라우저로는 확인하지 않았다.
- 남은 작업: 없음. 커밋·푸시는 하지 않았다.
