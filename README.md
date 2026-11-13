<p align="center">
  <img src="docs/assets/app-icon.png" alt="STack" width="128">
</p>

<h1 align="center">STack e-Class</h1>

<p align="center">
  <b>학생을 위한 올인원 학습 플랫폼</b><br>
  서울과학기술대학교 e-Class(ILOS)를 다시 만든 웹 LMS — 강의·과제·시험·출석·성적·쪽지에<br>
  AI 학습 비서, 강의 녹음 전사·요약, 자료 요약까지 한 화면에서.
</p>

<p align="center">
  <a href="https://github.com/2026ENDPoinT/team13/actions/workflows/backend.yml"><img alt="Backend CI" src="https://github.com/2026ENDPoinT/team13/actions/workflows/backend.yml/badge.svg"></a>
  <a href="https://github.com/2026ENDPoinT/team13/actions/workflows/frontend.yml"><img alt="Frontend CI" src="https://github.com/2026ENDPoinT/team13/actions/workflows/frontend.yml/badge.svg"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-007396?logo=openjdk&logoColor=white">
  <img alt="Spring Boot 4.1" src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white">
  <img alt="React 19" src="https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black">
  <img alt="TypeScript" src="https://img.shields.io/badge/TypeScript-strict-3178C6?logo=typescript&logoColor=white">
  <img alt="MySQL 8.4" src="https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white">
  <img alt="Docker Compose" src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white">
</p>

<p align="center">
  <a href="#-주요-기능">주요 기능</a> ·
  <a href="#-아키텍처">아키텍처</a> ·
  <a href="#-빠르게-시작하기">빠르게 시작하기</a> ·
  <a href="#-cicd">CI/CD</a> ·
  <a href="#-운영">운영</a> ·
  <a href="#-팀">팀</a>
</p>

<p align="center">
  <img src="docs/screenshots/home.png" alt="STack e-Class 홈" width="900">
</p>

> ENDPoinT 해커톤 2026 **Team 13** 제출 저장소입니다. 실제 서비스는 `https://eclass.stack-edu.com`(팀 허용 계정만 접속)에서 돌아가고 있습니다.

---

## 목차

- [왜 만들었나](#-왜-만들었나)
- [주요 기능](#-주요-기능)
- [아키텍처](#-아키텍처)
- [기술 스택](#-기술-스택)
- [저장소 구조](#-저장소-구조)
- [빠르게 시작하기](#-빠르게-시작하기)
- [테스트](#-테스트)
- [CI/CD](#-cicd)
- [운영](#-운영)
- [문서](#-문서)
- [팀](#-팀)

## 💡 왜 만들었나

학교 e-Class는 강의·과제·시험·공지가 과목마다 흩어져 있어 "오늘 무엇을 해야 하는지"를 한눈에 알기 어렵습니다. STack은 같은 학사 데이터를 쓰면서

- **오늘 할 일 중심 홈** — 마감이 가까운 과제와 이어 들을 강의를 첫 화면에,
- **말로 시키는 학습 비서** — "오늘 할 일 전부 해줘" 한 문장으로 영상 → 과제 순서의 학습 계획을,
- **학습실** — 내 자료·녹음·요약을 과목별 폴더로 모으고, 녹음은 자동 전사·요약,

을 더했습니다. 학생 화면뿐 아니라 교수자 화면(출제·채점·성적·출석·수강생 관리)과 모바일 화면도 함께 구현했습니다.

## ✨ 주요 기능

### 학생

| | |
|---|---|
| <img src="docs/screenshots/home.png" alt="홈"><br>**홈** — 마감 임박 할 일, 이어 듣기, 오늘 수업, 나의 하루 | <img src="docs/screenshots/assistant-plan.png" alt="AI 학습 비서"><br>**AI 학습 비서** — "오늘 할 일 전부 해줘" → 영상·과제 순서의 계획을 만들고 승인하면 차례로 진행 |
| <img src="docs/screenshots/course-room.png" alt="강의실"><br>**강의실** — 주차별 학습활동, 강의계획서, 공지·Q&A·파일·열린게시판 | <img src="docs/screenshots/player.png" alt="강의 플레이어"><br>**강의 플레이어** — 배속·자막·이어 보기, 옆에 학습 노트(요약·전사·연결 PDF) |
| <img src="docs/screenshots/report-submit.png" alt="과제 제출"><br>**과제 제출** — 서식 있는 설명과 파일 첨부(악성코드 검사 후 접수) | <img src="docs/screenshots/test.png" alt="시험"><br>**시험** — 응시 기간·배점·제한 시간·결과 공개 여부, 시작 시각 전에는 응시 잠금 |
| <img src="docs/screenshots/file-preview.png" alt="자료 미리보기"><br>**자료 미리보기** — PDF·문서를 브라우저 안에서(PDF.js) | <img src="docs/screenshots/activities.png" alt="학습활동"><br>**학습활동** — 영상·자료·과제·시험·진행중·미제출 필터 |
| <img src="docs/screenshots/study-room.png" alt="학습실"><br>**학습실** — 과목별 내 폴더에 자료·녹음·요약을 모음 | <img src="docs/screenshots/study-summary.png" alt="자료 요약"><br>**자료 요약** — 쪽별 핵심·흐름·학습 도구 |
| <img src="docs/screenshots/study-recording.png" alt="강의 녹음"><br>**강의 녹음** — 녹음을 올리면 자동 전사(Deepgram)·요약 | <img src="docs/screenshots/calendar-week.png" alt="주간 달력"><br>**달력·시간표** — 주간 일정과 마감 |
| <img src="docs/screenshots/catalog.png" alt="수강 과목 찾기"><br>**과목 찾기·수강 신청** — 정규·비정규 과목 검색과 신청 | <img src="docs/screenshots/messages.png" alt="쪽지"><br>**쪽지** — 받은·보낸 쪽지, 과목별 수신자 검색 |
| <img src="docs/screenshots/attendance.png" alt="출석"><br>**출석** — 주차별 출결 | <img src="docs/screenshots/grades.png" alt="성적"><br>**성적** — 공개된 항목별 점수 |
| <img src="docs/screenshots/community-qna.png" alt="커뮤니티"><br>**알림마당** — 공지·질의응답·자료실·설문 | <img src="docs/screenshots/notifications.png" alt="알림"><br>**알림·설정** — 읽지 않은 알림, 테마·언어·방해 금지 |

### 교수자

| | |
|---|---|
| <img src="docs/screenshots/prof-home.png" alt="교수 홈"><br>**교수 홈** — 담당 과목, 미채점 과제, 이의신청, 확인할 일 | <img src="docs/screenshots/prof-gradebook.png" alt="성적 관리"><br>**성적 관리** — 반영 비율과 항목별 점수 입력 |
| <img src="docs/screenshots/prof-report-grade.png" alt="과제 채점"><br>**과제 채점** — 학생별 제출 상태·파일 확인과 점수 입력 | <img src="docs/screenshots/prof-exam-create.png" alt="시험 출제"><br>**시험 출제** — 문제은행에서 문항을 골라 제한 시간과 함께 출제 |
| <img src="docs/screenshots/prof-learners.png" alt="수강생"><br>**수강생** — 학번 검색, 최근 접속, 진도 | <img src="docs/screenshots/prof-attendance.png" alt="출석 관리"><br>**출석 관리** — 출석부, 스마트 출석, 휴강·보강 |

### 모바일

<p>
  <img src="docs/screenshots/mobile-home.png" alt="모바일 홈" width="260">
  <img src="docs/screenshots/mobile-menu.png" alt="모바일 메뉴" width="260">
  <img src="docs/screenshots/mobile-todo.png" alt="모바일 할 일" width="260">
</p>

> 스크린샷은 `frontend/`를 백엔드 없이 띄운 **샘플 데이터 모드**에서 찍었습니다(AI 비서만 실제 모델에 연결). 운영 환경에서는 같은 화면이 학교 계정으로 로그인한 실제 데이터로 채워집니다.

## 🏗 아키텍처

### 시스템 구성

```mermaid
flowchart LR
  subgraph Users["사용자"]
    ST(["학생"])
    PF(["교수자"])
  end

  subgraph Edge["Cloudflare"]
    AC["Access<br/>(허용 이메일)"]
    TN["Tunnel"]
  end

  subgraph Host["Hermes (단일 VM, Docker Compose)"]
    FE["frontend<br/>server.mjs<br/>SPA · /api 프록시 · AI 비서 중계"]
    subgraph BE["backend (Spring Boot, compose 프로젝트 astra)"]
      API["api"]
      WK["worker"]
      CV["converter<br/>LibreOffice"]
      TC["transcoder<br/>ffmpeg"]
      AV["clamav<br/>악성코드 검사"]
    end
    DB[("MySQL 8.4")]
    S3[("SeaweedFS<br/>S3 호환 저장소")]
    GW["LLM 게이트웨이<br/>Gemini 4 Hydrogen → DeepSeek 폴백"]
  end

  SCH["학교 e-Class<br/>로그인·수강 동기화"]
  DG["Deepgram<br/>음성 인식"]

  ST & PF --> AC --> TN --> FE
  FE -- "/api · /doc · /vod" --> API
  FE -- "AI 비서" --> GW
  API --> DB
  API --> S3
  WK --> DB
  WK --> S3
  WK --> AV
  WK --> CV
  WK --> TC
  API & WK -- "요약 · 학습 대화" --> GW
  WK -- "녹음 전사" --> DG
  API -- "학번 로그인" --> SCH
```

- **프론트엔드**(`frontend/`) — React 19 SPA를 `server.mjs`가 서빙합니다. 같은 서버가 `/api/**`·`/doc/**`·`/vod/**`를 백엔드로 넘기고, 홈 AI 비서 요청을 LLM 게이트웨이로 중계합니다. 백엔드 주소가 없으면 샘플 데이터로 혼자 동작합니다.
- **백엔드**(`backend/`) — Spring Boot **모듈러 모놀리스**입니다. 한 이미지로 `api`와 `worker` 프로필을 띄우고, 문서 변환(LibreOffice)·영상 변환(ffmpeg)·악성코드 검사(ClamAV)는 각자의 컨테이너와 내부 네트워크로 분리했습니다.
- **AI** — 쪽별 요약·학습 대화·녹음 요약과 홈 비서는 OpenAI 호환 LLM 게이트웨이를 거칩니다. 1순위는 Gemini 4 Hydrogen, 실패하면 즉시 DeepSeek로 넘어가고 응답 형식(JSON Schema)을 검사합니다. 녹음 전사는 Deepgram `nova-3`(한국어·영어 혼합)입니다.

### 백엔드 모듈

```mermaid
flowchart TB
  subgraph astra["com.stackcorp.astra"]
    identity["identity<br/>학교 로그인 · 세션 · break-glass · 재인증"]
    catalog["catalog<br/>과목 · 개설 · 수강 신청"]
    learning["learning<br/>강의 · 진도 · 출석"]
    assessment["assessment<br/>과제 · 시험 · 설문 · 성적"]
    communication["communication<br/>공지 · Q&A · 쪽지 · 알림"]
    files["files<br/>업로드 · 파일함 · 학습실 · 녹음"]
    summary["summary<br/>쪽별 요약 · 학습 대화"]
    home["home<br/>홈 · 할 일 · 달력"]
    integration["integration<br/>LLM · Deepgram · 학교 e-Class · S3"]
    shared["shared<br/>멱등 원장 · 감사 · 오류 · 요청 기록"]
  end
  home --> learning & assessment & communication & catalog
  summary --> files & integration
  files --> integration
  identity --> integration
  learning & assessment & communication & catalog & files --> shared
```

설계 결정은 41개의 ADR(`backend/docs/adr/`)로 남겼습니다. 예: [모듈러 모놀리스](backend/docs/adr/0001-modular-monolith.md), [업로드 접수와 검사](backend/docs/adr/0010-upload-acceptance.md), [멱등 원장과 감사](backend/docs/adr/0012-idempotency-ledger-and-audit.md), [영상 업로드와 변환](backend/docs/adr/0016-video-upload-and-transcoder.md), [ClamAV 검사](backend/docs/adr/0018-malware-scan-clamd.md), [단일 호스트 Compose](backend/docs/adr/0019-container-image-and-single-host-compose.md), [녹음 전사·요약](backend/docs/adr/0041-recording-transcription-and-summary.md). C4 다이어그램은 [`backend/docs/c4.md`](backend/docs/c4.md)에 있습니다.

### 보안 설계 요약

| 영역 | 방식 |
|---|---|
| 공개 범위 | Cloudflare Access(허용 이메일) → Tunnel. 서버는 인터넷에 포트를 열지 않음 |
| 세션 | 서버 세션 쿠키 + CSRF 토큰, 허용 Origin 검사(`ASTRA_SESSION_ALLOWED_ORIGINS`) |
| 로그인 | 학교 e-Class 학번 로그인(비밀번호는 확인 요청 한 번에만 쓰고 저장하지 않음), 운영자 break-glass(비밀번호 + TOTP, 잠금·재사용 방지) |
| 업로드 | 형식 검사 → 격리(quarantined) → ClamAV 검사 통과 후 공개. 검사기는 외부와 끊긴 네트워크에만 있음 |
| 콘텐츠 전달 | `/doc`·`/vod` 서명 URL, 원본 저장소는 비공개 |
| LLM | 과목별 교수자 동의 후에만 자료 전송, 제공자·처리 지역 고지, 게이트웨이는 sudo·소스·비밀 경로를 막은 systemd 샌드박스 |

## 🧰 기술 스택

| 영역 | 기술 |
|---|---|
| 프론트엔드 | React 19, TypeScript(strict), Vite, PDF.js, hls.js, PWA, Vitest, Stryker |
| 프론트 서버 | Node.js 22(`server.mjs`: 정적 서빙·백엔드 프록시·AI 비서 중계·CSP) |
| 백엔드 | Java 25, Spring Boot 4.1(Web MVC, Security, JDBC, Flyway, Actuator, OpenTelemetry) |
| 데이터 | MySQL 8.4(Flyway 마이그레이션), S3 호환 오브젝트 저장소(SeaweedFS) |
| 비동기 처리 | worker 프로필: 악성코드 검사(ClamAV), 문서 변환(LibreOffice), 영상 변환(ffmpeg), 요약·전사 작업 |
| AI | Gemini 4 Hydrogen(Antigravity) + DeepSeek 폴백(LLM 게이트웨이), Deepgram nova-3 전사 |
| 인프라 | Docker Compose(단일 호스트), Cloudflare Tunnel·Access, Oracle Cloud ARM64 |
| CI/CD | GitHub Actions, Jenkins(JCasC), systemd 타이머 기반 백업·감시 |

## 📁 저장소 구조

```text
team13/
├── frontend/                 # React SPA + server.mjs (Frontend-final)
│   ├── src/areas/            #   화면: home, course, learning, professor, mobile
│   ├── src/shared/           #   서버 클라이언트, 공용 위젯, AI 비서
│   ├── public/               #   글꼴·이미지·샘플 미디어·PDF.js·hls.js·PWA
│   ├── tests/                #   단위·통합 테스트(Vitest)
│   └── deploy/               #   Jenkins(JCasC)·Hermes 배포·운영 스크립트
├── backend/                  # Spring Boot 모듈러 모놀리스 (STack-Backend)
│   ├── src/main/java/…/astra #   identity, catalog, learning, assessment, communication, files, summary, home, integration, shared
│   ├── src/main/resources/db/migration  # Flyway 마이그레이션
│   ├── docker/               #   entrypoint, healthcheck, smoke test
│   ├── db/                   #   스키마 검사, ERD 스냅샷
│   ├── docs/                 #   ADR 41개, C4, 아키텍처, 운영
│   └── compose.yaml          #   api · worker · converter · transcoder · mysql · clamav
├── docs/                     # 이 README의 스크린샷과 아이콘
└── .github/workflows/        # Backend CI, Frontend CI
```

## 🚀 빠르게 시작하기

### 프론트엔드만 (샘플 데이터, 백엔드 불필요)

```sh
cd frontend
npm install
npm run dev            # http://127.0.0.1:4190/  (Vite HMR)
```

Node.js 22.18 이상이 필요합니다. 백엔드에 붙이려면 `ASTRA_API_ORIGIN=http://127.0.0.1:8080 npm start` 처럼 백엔드 주소를 줍니다. 환경 변수는 [`frontend/README.md`](frontend/README.md)를 참고하세요.

### 백엔드 (Docker Compose)

```sh
cd backend
cp .env.example .env   # DB 비밀번호, 허용 Origin, S3, 서명 키 등을 채운다
docker build -t astra-backend:local .
docker build --target converter -t astra-backend-converter:local .
docker compose up -d --wait
docker/smoke-test.sh   # 기동 확인
```

`compose.yaml`은 `api`(127.0.0.1:8080)·`worker`·`converter`·`transcoder`·`mysql`(프로필 `mysql`)·`clamav`(프로필 `scan`)을 띄웁니다. 로컬 개발용 실행, 로그인 방식, LLM·Deepgram·S3 설정은 [`backend/README.md`](backend/README.md)에 정리돼 있습니다.

## 🧪 테스트

| 대상 | 명령 | 내용 |
|---|---|---|
| 백엔드 | `cd backend && ./gradlew build` | 단위·통합 테스트(Testcontainers로 MySQL 8.4를 띄워 Flyway 마이그레이션 적용, ffmpeg 변환 포함) |
| 백엔드 스키마 | GitHub Actions `Schema check` | 모든 마이그레이션 적용 후 FK·COMMENT·고립 테이블 검사, ERD 스냅샷 최신 여부 |
| 백엔드 컨테이너 | `docker/smoke-test.sh` | 실제 이미지와 compose로 기동해 헬스·API 응답 확인 |
| 프론트엔드 | `cd frontend && npm test` | Vitest 단위·통합 테스트, `npm run test:coverage`(임계값) |
| 프론트 변이 테스트 | `npm run test:mutation` | Stryker(수동 실행) |

## 🔁 CI/CD

```mermaid
flowchart LR
  dev(["개발자 push / PR"]) --> T13["team13 (이 저장소)"]
  dev --> SC["STack-Corp<br/>Frontend-final · STack-Backend"]

  T13 --> GA1["GitHub Actions<br/>Backend CI · Frontend CI"]

  SC --> GA2["GitHub Actions<br/>백엔드 빌드·스키마·컨테이너"]
  SC --> J["Jenkins (JCasC)<br/>yoobin-desktop-linux"]
  GA2 -. "CI 성공 확인" .-> J
  J -- "프론트: 타입 검사·빌드" --> J
  J -- "ssh 제한 키<br/>jenkins-deploy" --> H["Hermes"]
  H --> FE2["frontend 교체<br/>dist 교체 → 재기동 → 헬스"]
  H --> BE2["backend 교체<br/>이미지 빌드 → DB 덤프 → compose up --wait"]
  FE2 & BE2 -. "실패 시" .-> RB["이전 버전으로 자동 롤백"]
```

- **이 저장소(team13)** — `backend/**`·`frontend/**`가 바뀌는 push와 PR마다 GitHub Actions가 돕니다.
  - [Backend CI](.github/workflows/backend.yml): Gradle 빌드·테스트(Testcontainers), 마이그레이션 스키마 검사·ERD 스냅샷, Docker 이미지 빌드와 compose smoke test
  - [Frontend CI](.github/workflows/frontend.yml): 타입 검사, Vitest 커버리지, 프로덕션 빌드, 서버 문법 검사(변이 테스트는 수동 실행)
- **운영 배포** — 실제 서비스는 팀 개발 저장소(STack-Corp)의 `main`에서 자동 배포합니다. 두 곳이 같은 서버에 배포해 충돌하지 않도록 이 저장소는 CI만 맡습니다.
  - 프론트엔드: `main` 병합 → Jenkins가 검사·빌드 → Hermes에서 새 `dist`로 교체하고 헬스 확인(약 20초)
  - 백엔드: `main` 병합 → GitHub Actions CI 성공을 기다림 → Hermes에서 이미지 빌드, DB 덤프, `docker compose up --wait`, 헬스 확인(약 3분)
  - 어느 단계든 실패하면 이전 커밋·이미지로 자동으로 되돌립니다. 배포 대상 커밋이 서버 전용 설정 파일을 덮어쓰려 하면 배포 자체를 거부합니다.
  - 구성 원본: [`frontend/deploy/`](frontend/deploy/) — Jenkins JCasC, Hermes 배포 스크립트, 운영 스크립트와 systemd 유닛

## 🛠 운영

| 항목 | 방식 |
|---|---|
| 감시 | 5분마다 디스크·메모리·OOM·컨테이너·핵심 서비스·공개 주소·백업 나이를 점검해 텔레그램으로 알림, 복구되면 복구 알림. 다른 머신에서 생존 신호(heartbeat)를 따로 확인 |
| 백업 | 매일 DB(MySQL·PostgreSQL)와 업로드 저장소·SQLite를 백업하고 다른 머신으로 복사(삭제는 전파하지 않음) |
| 자원 | 모든 컨테이너에 메모리·CPU·로그 상한, 주간 Docker 캐시 정리 |
| LLM 게이트웨이 | Gemini 실패·쿼터 초과·지연 시 즉시 DeepSeek, 차단기(1분/15분), 응답 형식 검사와 재질의 |

자세한 운영 절차는 [`frontend/deploy/jenkins/README.md`](frontend/deploy/jenkins/README.md)에 있습니다.

## 📚 문서

- [`backend/README.md`](backend/README.md) — 실행, 설정(로그인·LLM·Deepgram·S3·검사), 배포 점검표
- [`backend/docs/adr/`](backend/docs/adr/) — 설계 결정 41개
- [`backend/docs/c4.md`](backend/docs/c4.md), [`backend/docs/architecture.md`](backend/docs/architecture.md) — 구조
- [`frontend/README.md`](frontend/README.md) — 실행, 환경 변수, 화면 구조
- [`frontend/docs/TEST_STRATEGY.md`](frontend/docs/TEST_STRATEGY.md) — 테스트 전략

## 👥 팀

<p>
  <img src="docs/assets/mascot.gif" alt="STack 마스코트" width="96" align="right">
</p>

**ENDPoinT 해커톤 2026 · Team 13 — STack**

| 이름 |
|---|
| 김유빈 |
| 양하정 |
| 이진우 |
| 심규민 |

<p align="center">
  <img src="docs/assets/logo.png" alt="STack" height="40"><br>
  <sub>All in one platform for students</sub>
</p>
