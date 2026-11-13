# STack e-Class Frontend (final)

STack e-Class 학생·교수 화면 프론트엔드. `STack-Corp/STack-eclass-Frontend`의 UI 코드만 옮긴 저장소다. 원본 화면 보존 자산, 문서, 검증 도구는 포함하지 않는다.

React 19 + TypeScript(strict), Vite로 빌드한다.

## 실행

Node.js 22.18 이상(`server.mjs`가 TypeScript 모듈 `src/shared/assistant/aliases.ts`를 그대로 불러온다).

```sh
npm install
npm run dev        # Vite 개발 서버(HMR)를 server.mjs에 붙여 실행
npm run build      # tsc 타입 검사 후 dist/로 빌드
npm start          # dist/를 서빙(빌드 필요)
npm run typecheck  # 타입 검사만
npm test           # 단위·통합 테스트(Vitest)
npm run test:coverage  # 커버리지(임계값 포함)
npm run test:mutation  # 변이 테스트(Stryker, 10분 이상)
```

`http://127.0.0.1:4190/`. 다른 포트는 `npm run dev -- --port 4191`.

## 환경 변수

| 변수 | 내용 |
|---|---|
| `ASTRA_API_ORIGIN` | 백엔드 주소. 지정하면 `/api/**` 요청을 이 주소로 중계한다. 없으면 샘플 데이터로 동작한다 |
| `GEMINI_API_KEY` | 홈 명령줄 AI 비서 키. 없으면 `/api/assistant`가 503으로 비활성화된다 |
| `GEMINI_MODEL` | 비서 모델 이름. 기본 `gemini-3.5-flash-lite` |
| `ASTRA_PUBLIC_HOSTS` | 추가로 허용할 공개 호스트 이름(쉼표 구분) |

## 로그인 홈 UI와 서버 데이터

- 목업과 e-Class 로그인 홈은 같은 히어로·명령 입력창·전체화면 전환·나의 하루 카드·수강과목 카드 구성을 사용한다. 예전 `cal=month` 주소로 접속해도 홈을 월간 달력으로 대체하지 않는다.
- 로그인 후 이름·할 일·시간표·수강과목·진도는 서버 응답만 사용한다. 학습실 입장 링크는 해당 서버 과목의 `kj`를 유지하며, 자료·폴더·녹음 API는 기존 연결을 그대로 사용한다.
- 나의 하루와 필터 팝업은 `/api/me/todo`의 동일한 목록과 서버 시각을 기준으로 계산한다. 이번 주 자료는 이번 주 마감인 미완료 영상·자료다. 다음 쪽이 남으면 `N+`와 `불러온 목록 기준`을 표시하고 팝업의 `더 보기`로 이어서 조회한다. 미수신·오류·기준 시각 미제공을 샘플이나 0건으로 대체하지 않는다.
- 로그인 히어로의 추천·자동완성은 실제 서버 과목과 활동을 연다. `할 일 목록 보여줘`는 서버 목록을 표시하며, 목업의 자동 계획 실행이나 과제 제출을 서버 기능인 것처럼 제공하지 않는다.
- 강의실도 목업·로그인 화면이 같은 `RoomLayout`을 사용한다. 평면 과목 헤더·강의계획서·종류/상태 필터·주차 막대·활동 행·빈 화면을 공유하고, 서버 전용 배너·커버·공지/할 일 묶음을 본문에 추가하지 않는다.
- 로그인 강의실의 주차 수·완료 표시·진도·제출 상태·상세 링크는 서버 응답을 사용한다. 좌측 주차/공지 숫자와 우측 오늘 수업/할 일도 서버 값이며, 미수신 값을 목업 숫자로 채우지 않는다. 계정/로그아웃은 기존 상단 메뉴를 쓰고 본문에 별도 인증 행을 붙이지 않는다.

회귀 검증: `npx vitest run tests/integration/home-layout.test.ts tests/integration/server-hero-commands.test.ts tests/integration/assistant-resolve.test.ts`.
강의실·공통 셸 회귀 검증: `npx vitest run tests/integration/course-room-layout.test.ts tests/integration/server-chrome.test.ts tests/integration/backend-prompt-layout.test.ts`.

## 구조

| 경로 | 내용 |
|---|---|
| `server.mjs` | 정적 파일 서버(`dist/`), 개발 모드 Vite 미들웨어(`--dev`), 백엔드 프록시, AI 비서 중계 |
| `index.html` | 앱 진입점(Vite) |
| `src/main.tsx`, `src/App.tsx` | 부팅, 루트 컴포넌트(셸·화면·창·토스트) |
| `src/router.tsx`, `src/store.ts` | 주소 ↔ 화면, 앱 상태(`localStorage` 저장)와 `useAppState` |
| `src/areas/` | 영역별 화면(home, course, learning, professor, mobile) |
| `src/shared/` | 서버 클라이언트, 공용 위젯 |
| `src/ui/` | 공용 컴포넌트(`kit.tsx`), 아이콘, 이미지 |
| `public/` | 그대로 복사되는 파일: 글꼴, 이미지, 샘플 미디어, PDF.js·hls.js(`public/app/vendor`), PWA 설정 |
| `tests/` | 단위(`unit`)·통합(`integration`) 테스트와 보조 도구(`helpers`). 설계 근거와 추적표는 `docs/TEST_STRATEGY.md` |

## 녹음 전사·요약 연동 계약

**프론트 수신 계약이며, 백엔드 구현 완료를 뜻하지 않는다.** 이 저장소는 음성 인식·요약 생성·서버 저장을 구현하지 않는다. 녹음 업로드 이후의 처리와 결과 저장은 백엔드 책임이다. 프론트는 기존 `GET /api/me/study-room/recordings/{id}` 응답에 아래 선택 필드가 있으면 사용한다. 타입은 `src/shared/recording-study.ts`에 있다.

```json
{
  "transcription": {
    "status": "ready",
    "result": {
      "segments": [
        { "at": 0, "text": "첫 번째 전사 구간입니다." },
        { "at": 12.5, "text": "다음 구간입니다." }
      ],
      "text": "첫 번째 전사 구간입니다.\n다음 구간입니다."
    }
  },
  "summary": {
    "status": "ready",
    "result": {
      "overview": "서버가 생성한 전체 요약",
      "sections": [
        {
          "title": "주요 내용",
          "at": 0,
          "points": [
            { "term": "핵심어", "text": "설명", "at": 12.5 }
          ]
        }
      ],
      "note": "요약 출처 안내"
    }
  }
}
```

- 위 JSON은 기존 녹음 메타데이터에 추가되는 필드만 보여준다. 목록·업로드 응답도 같은 녹음 타입을 사용한다.
- `transcription`과 `summary`는 독립적이다. 각각 `{ "status": "queued" }`, `{ "status": "processing" }`, `{ "status": "ready", "result": ... }`, `{ "status": "failed", "message": "사용자에게 보여줄 사유" }` 중 하나다. 미제공·`null`은 결과 미제공으로 표시하며 처리 중으로 추정하지 않는다.
- 처리 예정이면 **첫 상세 조회부터 `queued` 또는 `processing`을 반환해야 한다.** 프론트는 음성 검사 중이거나 두 작업 중 하나가 대기·처리 중일 때만 4초 간격으로 상세를 다시 조회한다. 완료·실패·미제공 상태에서는 자동 조회하지 않는다. 조회 실패 시 플레이어를 유지하고 조회를 멈추며 `상태 다시 확인`으로 조회를 재개한다. 이 버튼은 생성 작업 재시작 요청이 아니다.
- 음성 검사 `state`(`scanning`/`ready`/`rejected`)와 생성 작업 `status`는 별개다. 파일 `state=ready`일 때만 전사·요약·TXT를 제공한다. 실패한 작업이 있어도 완료된 다른 결과는 표시한다.
- 모든 `at`은 녹음 시작 기준 **초**(소수 허용, 0 이상·녹음 길이 이내)이며 기존 `durationMs`는 밀리초다. `segments`·`sections`는 시각 오름차순으로 제공한다. `sections[].table`은 선택 사항이며 `{ "head": ["열 이름"], "rows": [["값"]] }` 형식이다. 빈 전사 구간 배열은 음성 없음 등으로 표시할 구간이 없는 완료 결과다.
- 전사문 TXT는 백엔드가 만든 `transcription.result.text`를 UTF-8로 그대로 저장한다. 프론트에서 전사·문장 재생성을 하지 않으며 별도 파일 URL도 요구하지 않는다. 요약과 전사 내용은 일반 텍스트로 렌더링한다.
- 녹음 본문과 같은 소유자 인증을 적용하고 결과가 그 녹음의 최신 음원에 해당하도록 서버가 보장해야 한다. 프론트는 결과를 브라우저 영구 저장소에 저장하지 않으며 사용자 전환 시 상세 화면을 새로 연다. 결과가 없는 서버 모드에 샘플 전사·요약을 끼워 넣지 않는다.
- 음성 다운로드는 변환 없이 원본 형식이다. MP3 변환 API는 이번 계약에 포함하지 않는다.

프론트 회귀 검증: `npx vitest run tests/integration/recording-study.test.ts`.
