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
```

`http://127.0.0.1:4190/`. 다른 포트는 `npm run dev -- --port 4191`.

## 환경 변수

| 변수 | 내용 |
|---|---|
| `ASTRA_API_ORIGIN` | 백엔드 주소. 지정하면 `/api/**` 요청을 이 주소로 중계한다. 없으면 샘플 데이터로 동작한다 |
| `GEMINI_API_KEY` | 홈 명령줄 AI 비서 키. 없으면 `/api/assistant`가 503으로 비활성화된다 |
| `GEMINI_MODEL` | 비서 모델 이름. 기본 `gemini-3.5-flash-lite` |
| `ASTRA_PUBLIC_HOSTS` | 추가로 허용할 공개 호스트 이름(쉼표 구분) |

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
