# Handoff

## 2026-11-14 홈 히어로: AI 명령 입력 중심 UI + 전체화면 토글

### 작업 내용
- 히어로 명령창을 Gemini/GPT식 입력창으로 개편: 둥근 큰 입력창, AI 아이콘(`spark`), 원형 실행 버튼. 입력창은 히어로 맨 아래에 둔다.
- 순서(위→아래): 인사말 → 다음 행동 줄 → 추천 문장 칩 → 비서 한 줄 답(`cmd-say`) → 입력창. 자동완성 목록(`cmd-list`)은 위로 열린다.
- 추천 문장 칩(`PROMPTS`): 누르면 입력창에 채우고 바로 실행한다. 계획 요약(`ready`)이 떠 있으면 칩은 숨긴다.
- 히어로 오른쪽 아래에 전체화면 ↔ 일반 모드 토글(`<>`를 -45° 기울인 아이콘; 전체화면에서는 `><`).
  - 전체화면: 상단 바는 유지하고, 히어로가 상단 바 아래 화면 전체를 차지한다. 본문·오른쪽 열은 숨기고, 왼쪽 메뉴는 1024px 이상에서만 숨긴다(좁은 화면의 메뉴 서랍은 유지). 인사말은 남는 높이의 가운데, 입력창 묶음은 화면 아래에 붙는다.
  - 전체화면으로 바꾸면 입력창에 포커스한다.
  - 마지막 상태를 `localStorage["astra-hero-mode"]`(`full` | `normal`)에 저장한다. 기본값은 일반 모드.
  - 요청함·Plan 트레이가 떠 있으면 토글을 그 위로 올린다.
  - 전환 애니메이션: `document.startViewTransition` + `flushSync`로 그린다. 히어로(`view-transition-name: home-hero`)가 위쪽 기준으로 펼쳐지고(460ms) 접히며, 이전 모습은 불투명하게 깔고 새 모습이 180ms에 덮는다. 나머지 화면은 240ms 크로스페이드. 전환 중에만 `html.hero-morph`를 붙여 `tokens.css`의 테마 원형 전환 규칙을 덮어쓴다. 미지원 브라우저·`prefers-reduced-motion`에서는 즉시 전환.
- 서버 로그인(`backendStatus() === "ready"`) 히어로에는 명령창이 없어서 토글과 전체화면도 적용하지 않는다.

### 변경 파일
- `src/areas/home/home.tsx`: 모드 상태·저장(`readHeroMode`, `toggleHeroMode`, View Transition), `HeroModeToggle`, `.is-full` 클래스
- `src/areas/home/command-bar.tsx`: `focusCommand` export, `PROMPTS`·`runPrompt`, 요소 순서 변경, placeholder·aria-label 문구
- `src/areas/home/home.css`: 입력창·칩·토글 스타일, 전체화면 레이아웃(`.body:has(> .hero-slot > .home-hero.is-full)`), 위로 열리는 목록, `html.hero-morph` 전환 규칙
- `src/ui/icon.tsx`: `spark` 아이콘 추가

### 검증
- `npm run typecheck` 통과
- 브라우저(1440×900, 390×844): 토글 전환, 새로고침 후 상태 유지, 일반 모드 복귀, 위로 열리는 자동완성 목록, 칩 "기계학습 강의실로 이동" → `kj=ai` 강의실로 이동하는 것 확인
- 전환 애니메이션: Chromium에서 전환 중 `::view-transition-*(home-hero/root)` 애니메이션 실행, 200ms 시점 정지 화면으로 펼침·접힘 확인, 종료 후 `hero-morph` 제거·입력창 포커스 확인

### 남은 작업
- AI 칩(오늘 할 일·데베 강의·마감 과제)은 `GEMINI_API_KEY`가 필요하다. 키 없이 실제 응답은 확인하지 못했다.
- 계획 요약(`hero-plan`)이 뜬 상태의 전체화면 2열 배치는 실제 계획 응답으로 확인하지 못했다.

## 2026-11-14 히어로 전환 중 이전 배치 잔상 제거

### 작업 내용
- 전체화면 → 일반으로 접힐 때, 불투명하게 깔아 둔 이전(full) 히어로 스냅샷의 아래쪽 카드·글자(다음 행동 줄)가 새 모습 아래에서 비쳐 본문 윗선에 잘려 보이던 문제를 고쳤다.
- `::view-transition-old(home-hero)`를 `display: none`으로 그리지 않고, `::view-transition-new(home-hero)`는 페이드 없이 처음부터 불투명하게 둔다. 히어로 상자 크기 변화(460ms)와 위쪽 기준 clip은 그대로.

### 변경 파일
- `src/areas/home/home.css` (`html.hero-morph::view-transition-old/new(home-hero)` 규칙, 주석)

### 검증
- 전환 중간 프레임은 headless에서 일시정지·캡처하지 못했다(전환 `ready` 대기가 타임아웃). 전환 종료 직전 프레임과 최종 상태에서 잔상이 없는 것만 확인.

### 남은 작업
- 실제 브라우저(다크 테마)에서 전체화면 → 일반 접힘 전환 육안 확인.

## 2026-11-14 전체화면 히어로 배경 사진 살짝 더 보이게

### 작업 내용
- 전체화면 히어로의 중앙 방사형 베일(`.home-hero.is-full::after`)을 `--bg` 90%→72%에서 80%→58%로 낮춰 학교 사진이 조금 더 비치게 했다. 일반 모드·사진 필터(`--hero-photo-filter`)는 그대로.

### 변경 파일
- `src/areas/home/home.css`

### 검증
- 다크 테마 1568×740 전체화면에서 사진이 더 보이고 인사말·카드·입력창 글자가 읽히는 것 확인. 라이트 테마는 확인하지 못함.

### 남은 작업
- 더 보이게/덜 보이게 조정하려면 위 두 퍼센트 값만 바꾸면 된다.

## 2026-11-14 QA 피드백 수정 (eclass.stack-edu.com 검수 목록 1~10)

### 작업 내용
1. 질의응답·열린게시판 남의 글 수정/삭제 차단
   - 샘플: `format.ts` `ownsRow`로 작성자 확인. 목록(`course.tsx` `renderBoard`), 상세(`dialogs.tsx` `qnaDialog`), URL로 연 수정 창(`compose`: 남의 글이면 "다른 사람이 쓴 글은 고칠 수 없습니다", 없는 글이면 "글을 찾을 수 없습니다").
   - 서버: `boards-server.tsx` `editable` = `canEdit && mine`, `deletable` = `canDelete && (mine || instructor)`. `PostForm`도 남의 글이면 폼 대신 안내. 답글에는 `mine` 필드가 없어 서버 플래그만 따른다. 권한은 백엔드에서도 반드시 막아야 한다.
2. 상단 할 일·쪽지·알림 배지: `unread-badges.tsx` `useBadge`/`useSeenWhileOpen`(App.tsx에서 호출). 목록을 열면 "봤음"으로 기록(`AppState.badgeSeen`, localStorage). 샘플은 항목 ID, 서버는 사용자·종류별로 열었을 때의 개수를 기억. 알림 `read-all` API는 호출하지 않는다(보기만 해도 모두 읽음 처리되는 문제). 샘플 숫자는 실제 샘플 항목 수(알림 3, 쪽지 1)로 바뀜. 서버 모드 할 일 창 제목 `"? ?"` → "할 일".
3. 영상 플레이어(`learning.tsx` `SamplePlayer`): `단원으로`가 자기 플레이어 주소로 가던 문제 → `routeFor(currentRole(), "classroom", { kj, special })`로 강의실(비정규는 `70_nr_room_submain`)에 가고 `openUnit`이 그 주차를 선택·펼침. 전체화면에서는 `강의내용`·`질의하기`(와 패널) 숨김. `video-study.tsx`의 중복 `영상 전체화면` 버튼과 관련 코드(`FullscreenResult`, `reasonOf`) 제거.
4. 출석 차시 칩: `attendance-server.tsx` `pickAttendanceWeek`가 해당 카드(`#att-<week>`)로 스크롤·포커스(동작 줄이기면 즉시). 샘플 화면도 같은 함수 사용. `scroll-margin-top`으로 상단 바 회피. 샘플 출석 화면이 주소의 `kj` 과목을 따르게 수정.
5. 한글 자모 분리: 현재 코드(React)에서는 재현되지 않음. CDP `Input.imeSetComposition`으로 성적·공지사항·질의응답·파일모음·열린게시판 검색(샘플·가짜 서버 모드)과 전 화면 134개의 모든 입력란을 검사해 통과. 증상은 React 이전 렌더러(`2cc2e3c` `public/app/app.js`: 입력마다 `replaceChildren`으로 페이지를 다시 만들고 재포커스)와 일치 → 배포 사이트가 `fbbcc06` 이전 빌드일 가능성이 크다. 쓰이지 않는 `data-name="surfaceQuery"`/`data-scope`/`data-focus` 속성 제거. 조합 중 Enter 가드(`event.nativeEvent.isComposing`)를 빠져 있던 5곳에 추가: `professor.tsx` `finder`, `message-box.tsx` 쪽지 검색·받는 사람 검색, `study-files.tsx` 새 폴더 이름, `filebox-server.tsx` 이름 바꾸기.
6. 과목별 샘플 데이터: `data.ts`에 과목별 공지·질의응답·열린게시판(`coursePosts`)·파일 추가, `boardRows`가 `screen.course`로 거름. 게시판 링크는 모두 `kj` 유지. 서버 모드는 요청·캐시 키가 이미 과목별이라 변경 없음.
7. 강의실·학습활동 필터: `course.css` `.crs > .crs-filters, .crs-rail .crs-filters`를 세로 배치해 종류/상태 2줄.
8. 비정규과목 버튼이 다른 과목으로 가던 문제: `course.tsx` `renderActivityList`가 화면 정의의 `course: "bi"`를 쓰던 것 → `roomCourse`(주소의 `kj`). `screens.ts` 비정규 메뉴의 하드코딩 `courseId: "sec"` 제거(`NavItem.courseId`와 처리 코드도 제거).
9. 파일함: 서버 `filebox-server.tsx` 파일 이름 = 내려받기 버튼(`downloadItem`), "연결된 곳"에 강의실 링크(`FileboxReference.courseId`). 샘플은 제출물로 이동 + "강의실로 가기" 링크.
10. 왼쪽 메뉴 "학교 서비스는 새 탭에서 열립니다." 및 `.nav-note` 삭제(링크의 `aria-label`에 "(새 탭)" 유지).

### 남은 작업 / 확인 필요
- 배포 사이트를 현재 main으로 다시 배포한 뒤 실제 OS 한글 입력기(Windows/macOS/모바일)로 검색란을 다시 확인해야 한다.
- 검증: `npm run build`(tsc + vite) 통과, 통합 스모크(홈 배지·알림 열면 배지 사라짐·과목별 공지 다름·안내 문구 삭제, 콘솔 오류 없음).
- 서버 모드는 실제 백엔드 없이(배포 `/api`는 Cloudflare Access 뒤) 코드 검토·스텁 응답으로만 확인했다.
- 서버가 질의응답 `canEdit`/`canDelete`를 잘못 보내고 있다면 백엔드도 고쳐야 한다(프론트는 `mine`으로 한 번 더 거른다).
- 배지 서버 모드: 백엔드가 개수만 줘서, 옛 항목을 읽는 동시에 새 항목이 오면 새 항목이 적게 보일 수 있다.

## 2026-11-14 입력창 오렌지 테두리 + 마스코트

### 작업 내용
- 히어로 입력창(`.cmd-box`) 테두리를 `--brand-orange`(STack 로고 오렌지) 계열로 변경: 기본 70% 투명, hover/포커스는 100%, 포커스 링도 오렌지.
- 입력창 왼쪽 별(`spark`) 아이콘을 오렌지 마스코트 GIF로 교체. 36px 슬롯의 원에 몸통이 맞도록 80px로 확대·배치했고, 점프 동작(위로 최대 약 38px)은 슬롯 밖으로 나가게 뒀다. `prefers-reduced-motion`에서는 GIF 대신 오렌지 원을 표시한다.
- 더 이상 쓰이지 않는 `spark` 아이콘 삭제.

### 변경 파일
- `public/app/assets/mascot/orange-mascot.gif` (신규, 원본 `orange_mascot_wink_boing.gif`)
- `src/areas/home/command-bar.tsx`, `src/areas/home/home.css`, `src/ui/icon.tsx`

### 검증
- 다크·라이트 테마에서 전체화면/일반 모드 입력창 표시, 포커스 시 오렌지 링, 콘솔 오류 없음 확인. `npm run typecheck` 통과.

### 남은 작업
- 마스코트 크기/위치는 `.cmd-ico`(36px)와 `img`의 left/top/width 값으로 조정.

## 2026-11-14 전체화면 토글 아이콘 흰색

### 작업 내용
- 히어로 오른쪽 아래 전체화면 토글(`.hero-mode-toggle`)의 아이콘을 `--on-photo`(흰색)로, 배경을 어두운 반투명(`rgba(16,17,20,.55)`)·테두리를 흰색 40%로 변경. 라이트 테마의 밝은 사진 위에서도 흰 아이콘이 보이도록 배경을 테마와 무관하게 어둡게 고정했다. hover는 배경 75%·흰 테두리.

### 변경 파일
- `src/areas/home/home.css`

### 검증
- 다크·라이트 테마에서 아이콘이 흰색으로 또렷하게 보이는 것 확인.

### 남은 작업
- 없음

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
