# 테스트 전략

이 문서는 **무엇을, 왜, 어떤 기법으로, 어디까지** 테스트했는지와 **테스트하지 않은 것과 그 이유**를 기록한다.

## 1. 범위와 원칙

- 대상: 화면(`.tsx`)이 아니라 **화면 밖에서 판단·계산·보관을 맡는 모듈**. 버그가 나면 여러 화면에 번지고, 입력과 출력이 분명해 테스트 비용 대비 효과가 크다.
- 테스트는 **구현이 아니라 관찰 가능한 동작**을 검증한다. 내부 변수·호출 순서를 단정하지 않는다.
- 기대값은 구현 코드를 베끼지 않는다. 문서화된 규칙(소스의 주석, `handoff.md`)이나 독립 계산(예: 11/12는 8/31부터 73일째이므로 11주차)에서 얻는다.
- 결정적이어야 한다: 실제 시간·무작위·네트워크를 쓰지 않는다. 시각은 앱의 고정 "오늘"(`2026-11-12`)이나 주입한 날짜를 쓴다.
- 대체물(mock)은 **경계 밖**에만 둔다. `backend.ts`(세션·네트워크)만 대체하고 그 아래의 모든 로직은 실물로 돌린다.

## 2. 테스트 레벨

| 레벨 | 위치 | 환경 | 내용 |
|---|---|---|---|
| 단위 | `tests/unit` | jsdom (webm 은 node) | 순수 함수와 작은 상태 기계 |
| 통합 | `tests/integration` | jsdom | 여러 모듈 결합(질의 해석 → 계획 → 정렬), 실제 DOM 폼, 실제 `localStorage`, 실제 React 렌더 |
| 수동 E2E | `handoff.md` 의 "검증" 항목 | Chromium | 실제 마이크·MediaRecorder·화면 확인(자동화하지 않음, §6) |

## 3. 설계 기법 적용

| 기법 | 적용한 곳 |
|---|---|
| 동치 분할 | `classifyRoute`(주소 영역별 대표값), `isAppendable`(MIME), `abbreviationMatch`(음절 생략·초성·영문) |
| 경계값 분석 | `dday`(어제/오늘/내일), `attendStatus`(3·11·12·13주차), 서버 ID(`0`, `1`, 16자리, 17자리, 앞자리 0), D-3 마감 경계(`11/15` 포함, `11/16` 제외), 제목 길이(1·2자), `retryAfter` 0/1 |
| 결정 테이블 | `allowsPlan`(명시 요청 × 확인 응답 × 직전 맥락), `passFilter`(필터 × 활동 종류 × 제출 여부), `boardRows`(게시판 종류) |
| 상태 전이 | `createResource`(불러오는 중 → 완료/실패 → 비움), `freshVisit`(entry / key / false), 초안(`keepDraft`의 기준 버전 유지) |
| 속성 기반 | 모든 `RouteKind` × 입력 × 역할 조합에서 "학생 주소는 학생 영역, 교수 주소는 교수 영역, 역할 변환은 멱등", `serverActivityHref` ↔ `activityLinkOf` 왕복 |
| 오류 추측 | 잘린 WebM, 형식이 다른 두 녹음, 늦게 도착한 이전 세션 응답, 알리는 도중 구독 해제 |
| 독립 오라클 | `tests/helpers/webm.ts` 는 구현과 별개의 EBML 작성기·판독기. 합친 파일을 구현의 파서가 아니라 이 판독기로 검증한다 |

## 4. 요구사항 → 테스트 추적표

| ID | 요구(동작) | 근거 | 테스트 |
|---|---|---|---|
| R1 | 과목을 이름·줄임말·초성·영문 약어·별칭으로 찾는다 | `course-match.ts`, `aliases.ts` 주석 | `unit/course-match`, `integration/assistant-resolve` "과목 지정" |
| R2 | 오늘 계획(plan)은 명시적 요청에만 실행한다. 확인 응답("응")은 직전이 명시적 요청일 때만 | `aliases.ts` `allowsPlan` 주석 | `unit/allows-plan`, `integration/assistant-resolve` "오늘 계획" |
| R3 | 계획 대상 = 오늘 강의 + 마감 D-0~D-3, 시험 제외, 영상 → 과제 → 기타, 묶음 안은 마감 순 | `plan.ts` | `integration/assistant-resolve` `buildPlan / orderPlan` |
| R4 | 나의 하루 카드의 숫자와 할 일 목록이 같은 항목을 센다 | `format.ts` `myDayGroups` 주석 | `unit/format` "나의 하루" |
| R5 | D-day 표기, 오늘 포함 | `format.ts` | `unit/format` `dday` |
| R6 | 역할(학생/교수)에 맞는 주소를 만들고, 역할 전환 시 같은 대상으로 옮긴다 | `routes.ts` | `unit/routes`(예시 + 속성), `unit/routes-table`·`unit/routes-switch-table`(주소 표) |
| R7 | 서버 활동·과제·시험·설문 ID 로 학생 화면 주소를 만든다. 유효하지 않은 ID 는 만들지 않는다 | `activity-links.ts` 주석 | `unit/activity-links`, `unit/routes` "서버 ID" |
| R8 | 서버 응답 캐시: 동시 요청 공유, 세션이 바뀌면 폐기, 늦게 온 이전 세션 응답 무시, 쓰기 뒤 갱신(drop), 새 방문일 때만 재조회 | `remote-cache.ts` 주석, PR #5 | `unit/remote-cache`, `integration/remote-cache-hooks` |
| R9 | 입력 중인 폼은 다시 그려도 유지, 기준 버전은 처음 고칠 때 값을 유지(If-Match), 세션이 바뀌면 폐기 | `session-drafts.ts` 주석 | `integration/session-drafts` |
| R10 | 이어서 녹음: 두 WebM 을 재인코딩 없이 시간 이음, 형식이 다르거나 깨진 파일은 한국어 오류로 거부 | `webm-append.ts` 주석, `handoff.md` | `unit/webm-append` |
| R11 | 상태 저장·복원, 로그아웃/다른 탭 로그아웃 시 개인 입력은 저장·유지하지 않는다 | `store.ts` 주석 | `integration/store` |
| R12 | 교수가 만든 활동을 샘플 목록과 동기화(삭제·대체·제목 없음 제외) | `store.ts` `syncActivities` 주석 | `integration/store-sync-activities` |
| R13 | 게시판 목록은 종류·과목·대상에 맞는 글만 | `format.ts` `boardRows` | `unit/format` "boardRows" |

## 5. 결과

실행: `npm test` · `npm run test:coverage` · `npm run test:mutation` (CI: `.github/workflows/test.yml`)

| 항목 | 값 |
|---|---|
| 테스트 | 1315개, 14개 파일 |
| 문장 / 분기 / 함수 / 줄 커버리지 | 98.3% / 93.6% / 98.2% / 99.4% (대상 모듈 한정, 임계값 95 / 90 / 95 / 95) |
| 변이 점수(전체) | 87.6% (변이 2,788개 중 2,441개 검출, 임계값 75) |

커버리지는 "실행됐다"만 보증한다. 그래서 변이 테스트(Stryker)를 함께 둔다. 코드를 일부러 망가뜨린 변이(조건 반전, 경계 `>` ↔ `>=`, 문자열 비우기 등)를 테스트가 잡아내는 비율이다.

| 파일 | 변이 점수 | 파일 | 변이 점수 |
|---|---|---|---|
| `activity-links.ts` | 96% | `aliases.ts` | 95% |
| `session-drafts.ts` | 94% | `resolve.ts` | 90% |
| `format.ts` | 96% | `routes.ts` | 84% |
| `remote-cache.ts` | 86% | `webm-append.ts` | 85% |
| `course-match.ts` | 78% | | |

살아남은 변이는 대부분 같은 동작을 하는 변이(equivalent mutant)다. 예: 초기 세션 문자열 `""` 를 다른 문자열로 바꿔도 첫 알림은 어차피 다른 값이다. 단, `ACTIVITY_SCREEN`·`POP` 표의 변이는 같은 동작이 아니라 표 자체가 중복이어서 생긴 것이다(결함 5). `webm-append.ts` 의 남은 변이는 EBML 헤더 바이트 길이 경계(1~4바이트 ID, 8바이트 크기)로, 깨진 바이너리를 직접 만들어야 잡을 수 있어 비용 대비 효과가 낮다고 보고 남겼다.

이 수치를 올리는 과정에서 테스트의 약점이 드러났다. 처음 변이 점수는 `routes.ts` 38%, 전체 58% 였다. "주소가 학생/교수 영역에 속하는가"만 검사하는 속성 테스트는 종류가 엉뚱한 화면으로 연결되는 오류를 잡지 못했다. 그래서 주소 표 테스트(`routes-table`, `routes-switch-table`)를 추가했다.

### 테스트로 찾은 결함

| # | 결함 | 처리 |
|---|---|---|
| 1 | `webm-append.ts`: 크기를 모르는 클러스터(MediaRecorder 형식)의 마지막 블록이 잘린 파일을 거부하지 않고 받아들여, 뒤에 붙인 녹음 바이트를 삼킨 깨진 파일을 만든다 | 수정(`children` 에 "요소가 파일보다 김" 검사 한 줄), 회귀 테스트 추가 |
| 2 | `format.ts` `boardRows`: 교수가 쓴 과목 공지(`noticesExtra`, scope=과목)가 **전체 공지 게시판**에도 나온다 | 수정(전체 범위 공지만 통과), 회귀 테스트 추가 |
| 3 | `activity-links.ts`·`routes.ts`: ID 정규식은 16자리까지 허용하지만 `Number()` 는 9007199254740991(16자리) 넘는 값을 반올림한다. 16자리 중 9.0e15 초과 ID 는 다른 ID 로 바뀐다 | 미수정. 서버 ID 가 이 크기에 이르지 않으리라는 것은 추정이다. 정규식을 15자리로 줄일지 결정 필요 |
| 4 | `routes.ts` `routeFor(…"screen")`: `find()` 가 없는 ID 에 던지므로 `if (!targetScreen) return routeFor(role, "home", …)` 는 실행되지 않는 코드 | 미수정(동작 변화 없음). 던지는 동작은 테스트로 고정 |
| 5 | `routes.ts` 의 `ACTIVITY_SCREEN`·`POP` 표: 값을 비우면(= 일반 규칙으로 대체) 주소가 하나도 바뀌지 않는다. 변이 테스트에서 이 항목들의 변이가 모두 살아남아 드러났다. 현재 화면 표에서는 중복 데이터다 | 미수정. 정리할지 결정 필요 |

## 6. 하지 않은 것과 이유

| 제외 | 이유 | 대신 한 것 |
|---|---|---|
| `.tsx` 화면 컴포넌트 단위 테스트 | 샘플 데이터·전역 상태·라우터에 강하게 묶여 있고 문구·레이아웃이 자주 바뀐다. 비용 대비 회귀 방지 효과가 낮다 | 로직을 `shared/` 로 분리된 부분만 테스트. 화면은 브라우저에서 확인하고 `handoff.md` 에 기록 |
| 자동화된 E2E(Playwright) | 이 저장소에는 브라우저 자동화 환경이 없다. 백엔드(`ASTRA_API_ORIGIN`) 없이는 서버 모드를 띄울 수 없다 | Chromium 수동 확인(`handoff.md`). 서버 모드는 미확인으로 명시 |
| `server.mjs`, `backend.ts`, `api.ts`, `*-remote.ts` | 네트워크·세션·서버 계약에 의존한다. 서버와 함께 계약 테스트로 다루는 것이 맞다 | 경계(`backend.ts`)를 대체해 그 위의 캐시·초안 로직만 테스트 |
| 실제 MediaRecorder 출력 | Node/jsdom 에는 MediaRecorder 가 없다 | 합성 WebM(EBML 규격대로 작성) + 독립 판독기. 실제 Chromium 녹음 합치기는 `handoff.md` 에 확인 기록 |
| 시간대·로케일 의존 표기 | `fmt` 는 `toLocaleString` 을 쓴다 | 시간대 없는 값만 검증해 어느 시간대에서도 같은 결과 |
| 접근성 자동 검사(axe) | 화면 테스트가 없어 적용할 대상이 없다 | 해당 없음 |

## 7. 알려진 한계

- 샘플 데이터(`data.ts`)에 기대는 테스트가 일부 있다(`openTodos` 고정 항목, 나의 하루의 "2개"). 데이터가 바뀌면 함께 갱신해야 한다. 질의 해석·계획 테스트는 데이터를 주입해 이 문제가 없다.
- `syncActivities` 는 모듈 전역 `activities` 를 바꾸는 숨은 전역 상태다. 테스트가 매번 원상 복구한다. 구조적 개선은 이 범위 밖이다.
- TypeScript 7 은 Stryker 가 쓰는 JS API(`parseConfigFileTextToJson`)가 없어 `stryker.config.json` 에서 `tsconfigFile` 을 존재하지 않는 파일로 지정해 우회한다. TypeScript 가 업데이트되면 이 우회를 지운다.
