# STack e-Class Frontend (final)

STack e-Class 학생·교수 화면 프론트엔드. `STack-Corp/STack-eclass-Frontend`의 UI 코드만 옮긴 저장소다. 원본 화면 보존 자산, 문서, 검증 도구는 포함하지 않는다.

## 실행

Node.js 22.12 이상. 의존성 설치가 필요 없다(`server.mjs`는 Node 표준 라이브러리만 쓰고, PDF.js·hls.js는 `public/app/vendor`에 들어 있다).

```sh
npm run dev
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
| `server.mjs` | 정적 파일 서버, 백엔드 프록시, AI 비서 중계 |
| `public/index.html` | 앱 진입점 |
| `public/app/` | 화면 코드, 스타일(`tokens.css`), 글꼴, 이미지, 샘플 미디어 |
| `public/sw.js`, `public/manifest.webmanifest` | PWA 설정 |
