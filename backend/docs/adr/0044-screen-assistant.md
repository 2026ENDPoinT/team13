# ADR-0044 화면 AI 도우미는 백엔드가 로그인한 사용자의 자료로 답하고, 열 곳만 돌려준다

## 상태
채택 (2026-11-14). 모델 호출은 [ADR-0008](0008-llm-port-deepseek.md)의 port와 [ADR-0032](0032-llm-consent-and-study-chat.md)의 배포 스위치를 따른다.

## 맥락
프론트엔드의 AI 도우미(메인 입력창과 마스코트 버튼)는 지금까지 프론트엔드 서버(Frontend-final `server.mjs`)가 혼자 처리했다. 브라우저가 `POST /api/assistant`에 과목 이름, 활동 제목, 마감, 상태를 담아 보내면 `server.mjs`가 Gemini `generateContent`(배포에서는 같은 host의 LLM gateway `deploy/hermes/ops/agy-llm-gateway`)로 넘기고 답을 검사해 돌려주었다. 이 경로에는 다음 문제가 있었다.

- 백엔드를 거치지 않아 로그인 확인이 없고, 백엔드의 외부 AI 스위치와 처리 조건을 따르지 않으며, 감사 기록이 남지 않는다.
- 모델에 가는 자료(학교에서 가져온 활동 포함)를 브라우저가 정한다. 서버가 그 값을 믿을 근거가 없다.
- 답이 활동 ID를 돌려주면 화면이 자기 자료로 주소를 찾아야 해서, 화면이 모든 자료를 미리 들고 있어야 했다.

소유자는 이 기능을 백엔드가 맡고, 브라우저는 사용자가 쓴 문장과 지금 보고 있는 곳만 보내라고 정했다.

## 결정
### 요청과 응답
1. `POST /api/assistant`는 `home` 모듈의 route다. 할 일, 마감, 과목 목록을 이미 `home`이 계산하기 때문이다(architecture.md 4.1절). 모델 port(`summary.api.LlmClient`)만 `summary`에서 가져온다. 다른 모델 client를 더하지 않는다.
2. 요청 본문은 `{ text, context?: { courseId?, activityId?, history? } }`다. `text`는 공백이 아닌 500자 이하, `courseId`는 정수(그 사용자의 과목이 아니면 쓰지 않는다), `activityId`는 화면 주소의 값(`srv-<n>`, `school-<n>`, 숫자) 64자 이하로 모델에 주는 힌트일 뿐이다. `history`는 앞 차례 `{text ≤500, say ≤300}` 4개까지다. 그 밖의 필드, 형식, 길이는 400 `invalid_request`다. 본문은 16 KiB까지만 읽고 넘으면 413 `body_too_large`다. JSON만 받는다.
3. 응답은 `{ say, intent, actions: [{ label, href, external }] (최대 5개), plan, source: "rule" | "model" }`다. `href`는 화면이 그대로 따라가는 주소다. 화면은 자료를 몰라도 된다. `intent`는 규칙 답이면 `course`(강의실), `list`(목록), `open`(하나 열기), `plan`(계획), 모델 답이면 모델의 의도이고 없으면 null이다. `plan`은 계획일 때만 있고(18~20번) 그 밖은 null이다.
4. 로그인한 사용자만 쓴다(401 `unauthorized`). 출처 검사와 CSRF는 다른 POST와 같다(403 `forbidden`).

### 규칙 답
5. 분명한 명령은 모델 없이 답한다. Frontend-final(origin/main e44daf9)이 서버 모드에서 직접 처리하던 명령을 옮겼다(`areas/home/command-bar.tsx`, `shared/assistant/classrooms.ts`, `course-match.ts`, `server-home.ts`).
   - "<과목> 강의실로 이동": 이름이나 코드가 같으면 그 과목, 포함하면 그다음, 줄임말(초성, 음절 건너뛰기)이면 그다음이다. 같은 이름 과목은 화면이 붙이는 `(c<courseId>)`로 가린다. 후보가 여럿이면 강의실 목록을 돌려준다.
   - "할 일 목록 보여줘": 할 일(`GET /api/me/todo`와 같은 계산) 앞 5개.
   - "오늘 마감 …": 오늘 마감인 할 일.
   - "다음 할 일 열어줘", "<과목> 다음 할 일": 그 과목(말하지 않았으면 지금 과목, 그것도 없으면 전체)의 첫 할 일.
   - "마감 임박한 과제 열어줘": 24시간 안에 마감인 과제 중 가장 이른 것.
   - "<과목> <제목> 열어줘", "<제목> 열어줘": 그 할 일 하나와 정확히 같을 때만.
6. 규칙 답은 외부 AI 스위치가 꺼져 있거나 제공자가 없어도 동작한다. 외부로 보내는 것이 없기 때문이다.

### 모델 답
7. 규칙에 맞지 않으면 모델에 한 번 묻는다. 도구를 주지 않는다. 화면의 응답 시간을 지키기 위해서다.
8. 모델에 보내는 것은 서버가 그 사용자의 자료로 만든다. 브라우저가 보낸 목록은 없다.
   - 끝나지 않은 내 과목(30개까지): 요청 안에서만 쓰는 이름(`C1` …), 과목 이름, 과목 코드, 역할.
   - 항목(60개까지, `A1` …): 할 일(서비스 활동과 학교에서 가져온 활동, 30개까지), 7일 안의 마감(끝낸 것 포함), 지금 과목이나 문장에 이름·코드가 나온 과목의 이번 주와 다음 주 마감 활동과 그 과목에서 가져온 학교 활동(학교 주차 기간이나 시작·마감 일시가 그 두 주 안), 최근 공지(서비스 공지와 가져온 학교 공지). 학교 행은 학교 과목 키가 있는 과목의 학생에게만 넣는다(`learning.api.SchoolCourseItems`, 가져온 행만 읽고 학교에 요청하지 않는다). 항목마다 과목 이름, 유형, 제목, 마감 일시, 상태(todo, done, closed, open, notice)다.
   - 사용자 문장, 앞 차례, 지금 과목과 항목의 이름, 지금 시각.
   - 보내지 않는 것: 사용자 이름, 학번, 사용자 ID와 과목·활동의 DB ID, 성적, 제출물, 본문, 다른 사람의 자료.
   - 강의 자료의 쪽 내용이 아니라 사용자 본인의 과목·할 일 목록(제목과 마감)이므로 교수자 동의 범위(ADR-0032 3번)가 아니다. 이전 `server.mjs`도 같은 종류의 값을 보냈다. 외부 AI 스위치와 제공자 처리 조건은 그대로 따른다(15번).
9. 답은 `response_format: {"type":"json_object"}`로 받고, system 문구와 사용자 message의 response_schema로도 형식을 알린다. 답은 `{intent: open|choose|plan|none|clarify, targets: [이름] (12개 이하), say}`다. intent가 정해 둔 값이 아니거나 say가 문자열이 아니거나 300자를 넘으면 502 `assistant_invalid_response`다. targets 중 이번에 준 이름이 아닌 것은 버린다. 남은 것이 없으면 "맞는 항목이 없습니다"다.
10. 이름은 서버가 주소로 바꾼다(`AssistantLinks`, Frontend-final origin/main f096656의 주소 규칙과 같다). 강의실은 정규·비정규 강의실 주소, 서비스 영상·자료는 `n=srv-<활동 ID>`, 과제·시험·설문은 과제·퀴즈·설문 ID가 있으면 `n=srv-<그 ID>` 없으면 연결 화면 `act=<활동 ID>`, 강의실 공지는 공지 창 주소(`id=<공지 ID>`)다. 학교에서 가져온 영상(`m=lecture_weeks&n=school-<n>`), 자료(`m=lecture_material&n=school-<n>`), 과제(`m=report&n=school-<n>`), 공지(`id=school-<n>`)는 STack 상세를 같은 창(`external: false`)으로 연다. STack 화면이 없는 가져온 시험, 설문, 그 밖의 유형만 학교 e-Class 주소(https)를 `external: true`로 준다. 볼 화면이 없는 서비스 유형은 그 강의실로 간다.

### 같은 host의 LLM gateway
11. 배포의 `ASTRA_LLM_*`은 loopback 중계를 거쳐 gateway의 `/chat/completions`를 부른다. gateway는 `Authorization: Bearer <GATEWAY_TOKEN>`을 비교하므로 `ASTRA_LLM_API_KEY`가 `GATEWAY_TOKEN`과 같아야 한다(도우미용 `ASSISTANT_TOKEN`은 `generateContent` 경로의 값이고 이 경로에서는 쓰지 않는다).
12. 1순위(agy, Gemini 4 Hydrogen)는 요청에서 `messages`, `tools`, `tool_choice`만 넘기고 `AGY_MODEL`(기본 `gemini-4-hydrogen-medium`)을 쓴다. `response_format`, `temperature`, `max_tokens`, `model`은 버린다(거절하지 않는다). 그래서 형식을 문구로도 알리고 답의 앞뒤 글을 버리고 읽는다. 2순위(DeepSeek, `FALLBACK_MODEL` 기본 `deepseek-ai/deepseek-v4.1-flash-1017`)는 본문을 그대로 받아 `model`만 바꾸고 `thinking`을 빼고 `reasoning_effort: none`을 넣는다. `response_format: json_object`는 gateway 자신의 도우미 경로도 2순위에 보내는 값이다. 강제 도구 호출(`tool_choice`)은 1순위가 지킨다는 근거가 없어 쓰지 않았다.
13. 요청에 `reasoning_effort`(`astra.assistant.reasoning-effort`, 기본 `none`)를 넣는다. DeepSeek v4.x는 추론을 끄지 않으면 추론 token이 출력 상한(600)을 먹어 JSON 답이 중간에 잘린다(Hive API에서 확인: 추론 550 token, 답이 잘려 `finish_reason: length`. `none`이면 추론 0 token, 약 1.5초에 온전한 답). gateway의 2순위는 같은 값을 스스로 넣고 1순위는 버린다. 이 값을 받지 않는 제공자면 설정을 비운다. 모델 답에 intent나 say가 빠지면 채우고(targets로 판정), 있는데 정해 둔 값이 아니면 거절한다.

### 한도, 스위치, 기록
14. 한도는 process 메모리의 `RateLimiter`다. 모든 요청이 사용자별 요청 한도(`astra.assistant.request-rate-limit`, 기본 분당 30)를 쓰고, 모델을 부르는 요청은 사용자별 모델 한도(`rate-limit`, 기본 분당 10)와 모든 사용자의 합계(`total-rate-limit`, 기본 분당 60)를 더 쓴다. 넘으면 429 `rate_limited`와 `Retry-After`다.
15. `astra.assistant.enabled=false`면 규칙 답까지 모두 503 `llm_disabled`다. 외부 AI 스위치(`astra.llm.enabled=false`)가 꺼졌거나 제공자 key가 없으면 모델이 필요한 요청만 503 `llm_disabled`다. 화면의 "설정되지 않음" 안내가 그대로 맞는다.
16. 모델 답을 기다리는 시간은 `astra.assistant.timeout`(기본 20초, 동시 요청 자리를 기다리는 시간 포함)이다. 넘으면 504 `assistant_timeout`, 그 밖의 모델 호출 실패는 502 `assistant_upstream_error`다. 이 시간은 요청에만 적용되고 다른 모델 기능의 `astra.llm.timeout`(300초)은 바뀌지 않는다.
17. 요청마다 감사 기록 하나(`assistant.ask`)를 남긴다. 사용자, session, requestId, 결과 코드(`ok` 또는 오류 코드)만 있고 문장과 답은 없다. 개수(과목 수, 항목 수, 모델이 고른 수와 버린 수, token 수, 시간)는 같은 requestId의 서버 로그 한 줄에 남긴다. 지표는 다른 모델 기능과 같은 `astra.llm.calls`, `astra.llm.tokens`에 `kind=assistant`다. 감사 기록 밖에는 아무것도 저장하지 않는다. migration은 없다.

### 계획
18. 표본 모드(Frontend-final origin/main 69524cf)가 "오늘 할 일 전부 해줘"류 요청을 계획 카드("이 순서로 진행할까요?")로 만들어 차례로 열던 것을 서버가 만든다. `plan`은 `{ steps: [{ id, kind, title, course, due, href, external, mode }] }`이고 12단계까지다. 예전 화면을 위해 앞 5단계를 `actions`로도 준다.
    - `id`는 화면이 그 활동 화면에서 보는 값, 곧 주소의 `n`이다(`App.tsx`의 `observeScreen(params.get("n"))`, 완료 처리도 같은 값으로 단계를 찾는다). 서비스 영상·자료는 `srv-<활동 ID>`, 서비스 과제·시험·설문은 `srv-<과제·퀴즈·설문 ID>`, 가져온 학교 활동은 `school-<n>`이다. 연결 화면(`act=`)이나 강의실로 가는 서비스 단계는 `srv-<활동 ID>`다.
    - `kind`는 video, report, test, survey, material, other(zoom, 토론 등). 공지는 단계가 되지 않는다. `due`는 offset 없는 벽시계 일시, `course`는 과목 이름이다. `href`와 `external`은 `actions`와 같은 규칙(10번)이다.
    - `mode`는 STack이 재생하는 서비스 영상만 `auto`(끝나면 넘어간다)이고, 가져온 학교 영상 상세와 학교 주소로 가는 단계를 포함해 나머지는 모두 `confirm`이다(`plan.ts` `createRun`의 영상 auto 규칙을 서버 모드의 재생 여부로 좁혔다).
19. 규칙 계획은 표본 모드의 판정을 옮겼다. `isTodayAllRequest`("오늘 할 일 전부 해줘" 꼴)와 `allowsPlan`의 명시 판정(계획 낱말: 전부, 남은 거 다, 순서대로, 시작해 …)이 계획 문장이다. 범위는 기본이 `todayDeadlinePlan`·`buildPlan`과 같이 오늘부터 사흘 안 마감이고 시험과 끝낸 일은 뺀다. 문장에 "오늘 마감"이 있으면 오늘만, "이번 주"가 있으면 이번 주 토요일까지(캘린더와 같이 일요일 시작)다. 과목(이름, 코드, 줄임말, "이 과목")을 말하면 그 과목의 남은 일 전부, 유형 낱말(과제, 강의 …)이 있으면 그 유형만이다(`resolve.ts` 39-47행). 순서는 `orderPlan`이다: 영상 → 과제 → 그 밖, 같은 무리 안에서는 마감이 이른 순, 마감이 없으면 뒤. 확인 답("응")으로 앞 계획 요청을 잇는 판정은 앞 차례의 결과 종류가 요청에 없어 옮기지 않았다.
20. 모델은 intent `plan`과 targets를 낼 수 있다. 이번에 준 활동 이름만 단계가 되고(과목·공지·모르는 이름은 버린다) 순서는 같은 `orderPlan`이다. 범위 규칙(사흘, 시험 제외, 과목·유형·기간으로 좁힘)은 system 문구로 알린다.

## 결과와 트레이드오프
- 화면은 문장과 지금 위치만 보내고 받은 주소로 이동한다. 화면이 들고 있지 않은 자료(다른 쪽의 할 일, 학교에서 가져온 활동)도 답이 된다.
- 다른 사용자의 자료는 모델 입력에 들어갈 길이 없다. 모든 자료가 로그인한 사용자의 과목에서만 나온다.
- 학교에서 가져온 시험과 설문은 학교 e-Class 새 창으로만 연다. 화면에 그 상세가 생기면 `AssistantLinks.school`만 바꾼다.
- 모델 입력의 서비스 항목은 마감 일시가 있는 활동과 공지만이다. 마감이 없는 서비스 자료나 영상은 할 일, 마감, 주차 마감 목록에 없으므로 모델이 고를 수 없다. 가져온 학교 활동은 학교 주차 기간으로 고르므로 마감이 없어도 들어간다.
- gateway의 1순위는 60초까지 기다린 뒤에야 2순위로 넘어가므로, 1순위가 20초 안에 답하지 않으면 2순위가 답할 수 있었어도 504다. gateway가 요청별 마감이나 도우미용 model(`AGY_ASSISTANT_MODEL`, 미리 띄운 agy)을 `/chat/completions`에도 쓰게 하면 줄어든다. gateway는 Frontend-final 저장소에 있다.
- 한도가 process 메모리라 api가 둘이 되면 인스턴스마다 따로 센다(ADR-0032와 같다).
- 같은 api process의 모델 동시 요청 상한(`astra.llm.max-concurrency`, 기본 2)을 함께 쓴다. 자리를 기다리는 시간도 20초 안에 센다.

## 기각한 대안
- **`server.mjs`의 요청과 응답을 그대로 옮기기**: 브라우저가 보낸 과목·활동 목록을 모델에 보내므로 서버가 그 값을 믿어야 하고, 화면이 주소를 찾아야 한다.
- **`summary`에 route 두기**: 할 일, 마감, 공지를 모으려면 `summary`가 `catalog`, `learning`, `assessment`, `communication`에 의존해야 한다. `home`은 이미 그 공개 API를 쓰고 `summary.api`만 더하면 된다.
- **도구를 주고 여러 번 묻기(학습 대화처럼)**: 화면 응답 시간에 맞지 않는다. 자료를 한 번에 주고 한 번만 묻는다.
- **강제 도구 호출로 형식 받기**: gateway 1순위가 `tool_choice`를 지키는지 확인할 수 없다. JSON 객체 응답은 두 경로 모두 거절하지 않는다.

## 정하지 않은 것
- 가져온 학교 시험·설문의 STack 상세(화면 쪽).
- gateway의 `/chat/completions` 경로가 도우미 요청에 짧은 마감과 가벼운 model을 쓰게 하는 방법(gateway 쪽).
