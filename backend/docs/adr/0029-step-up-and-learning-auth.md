# ADR-0029 민감한 명령의 재인증과 온라인 학습 인증 grant는 로그인 session에 묶은 session_step_ups 행으로 한다

## 상태
채택 (2026-11-11)

## 맥락
연락처 변경(EP-0266)은 2차 인증 대상을 바꾸고, 다른 기기의 session을 모두 끝내는 명령은 도난 session이 주인을 내쫓는 데 쓰일 수 있다. Reference는 이런 명령에 최근 재인증을 요구하고, 학교 SSO 계정이면 비밀번호 대신 IdP 재인증(`max_age`/`prompt=login`)을 쓰라고 제안한다(IDENTITY-06, IDENTITY-08). 이 서비스는 로컬 비밀번호를 만들지 않는다([ADR-0004](0004-sso-bff-session.md) 6번).

원본의 온라인 학습 전 2차 본인인증(EP-0292~EP-0295)은 client가 빈 응답과 예외에서 통과하고(SEC-04, IDENTITY-03), 발송 대상을 client가 고르며(IDENTITY-04), 시도 횟수와 만료가 확인되지 않는다(IDENTITY-05). 서버 계약 SC-06-A는 학습 session 시작에 유효한 `learning_auth` grant를 요구하고, `credit_authorized_until = min(grant 만료, close_at, 로그인 session 만료)`를 저장해 그 경계에서 적립을 자르며, 만료 뒤에는 `reauth_required`로 두고 재인증 뒤 소급 없이 이어 가라고 정했다. [ADR-0014](0014-learning-session-credit.md) 결과 절이 이 작업을 남겼다.

V1에는 `session_step_ups`(session·목적 유일), `secondary_auth_challenges`(시도 수, 만료, 상태)가 이미 있다. 문자, 메일, 앱 push 발송 수단은 없고 이 단계에서 붙일 외부 제공자도 없다.

## 결정
1. 재인증과 grant는 모두 `session_step_ups`의 행이다. 로그인 session과 목적마다 하나이고, 다시 통과하면 같은 행을 새 시각으로 덮는다. 목적은 `RECENT_AUTH`(민감한 명령 전 재인증)와 `LEARNING_AUTH`(학습 인증 grant)다. session이 폐기되면 그 session의 행은 쓰이지 않는다. 다른 session이나 다른 기기로 옮겨 가지 않는다.
2. 재인증 확인은 `identity.api.RecentAuthentication.requireRecentAuth(actor, maxAge)` 하나다. 어느 모듈이든 명령 전에 부른다. 없거나 오래됐으면 403 `step_up_required`다. 대상은 요청한 사람의 것이므로 존재를 숨기는 404가 아니다. 로그인 자체는 재인증으로 치지 않는다. 학교 IdP가 기존 IdP session으로 화면 없이 로그인시켰을 수 있기 때문이다.
3. 학교 IdP 계정의 재인증은 `GET /api/auth/reauth?returnTo=`로 시작한다. adapter는 `prompt=login`, `max_age=0`을 보내고, ID token의 `auth_time`이 재인증을 시작한 시각(시계 오차 허용) 뒤일 때만 받는다. `auth_time`이 없으면 IdP가 다시 인증했는지 알 수 없으므로 거부한다. callback은 로그인과 같은 `/api/auth/callback`이고, 로그인 전용 쿠키 안의 표시로 재인증을 가린다. 재인증 callback은 새 session을 만들지 않고, 그 브라우저의 session 사용자와 IdP가 확인한 사용자가 같을 때만 그 session에 `RECENT_AUTH`(수단 `SSO`)를 쓴 뒤 `returnTo`로 보낸다. 실패하면 같은 `returnTo`(로그인과 같은 `ReturnPath` 검사를 거친 값)에 `reauth=failed`를 붙여 보낸다. 경로가 안전하지 않으면 `/?reauth=failed`다. break-glass session은 `POST /api/me/step-up/totp`로 TOTP를 다시 넣는다. 로그인과 같은 실패 잠금(15분 5번)을 쓰고, 로그인이나 TOTP 재인증에 성공한 뒤 90초 동안은 코드를 받지 않는다. 이 session이 쓸 수단은 `GET /api/me`의 `stepUpMethod`(`idp`, `totp`, 로컬 실행의 `dev`, 없으면 null)다. break-glass 계정 여부와 `GET /api/auth/methods`가 이미 알리는 로그인 수단으로만 정한다.
4. 학습 인증 grant는 2차 인증 challenge(`secondary_auth_challenges`)를 통과해야 생긴다. 수단은 `identity.api.SecondFactorChannel` port 뒤에 둔다. 지금 구현한 수단은 `idp` 하나이고, 그 근거는 challenge를 만든 뒤 같은 로그인 session에 기록된 IdP 재인증이다.
   - 상태는 PENDING → CONFIRMED | LOCKED | EXPIRED | CANCELLED다. 확인은 challenge 행을 잠근 뒤 PENDING이고 만료 전이며 시도가 한도 안일 때만 한 번 CONFIRMED가 되고, 같은 transaction에서 grant를 쓴다. 이미 확인한 challenge의 재확인은 409 `challenge_used`이고 새 grant를 만들지 않는다.
   - 근거가 없는 확인은 시도 수를 올린다. challenge당 5번이면 LOCKED이고 그 뒤에는 맞는 근거가 와도 통과하지 않는다. 사용자의 최근 15분 실패 합이 10이면 새 challenge를 만들지 못한다(429 `learning_auth_locked`). 시작과 확인에는 사용자 단위 요청 제한이 있다.
   - 확인 요청의 본문은 근거가 아니다. 빈 본문, `{}`, 형식이 다른 값, client가 보낸 성공 표시로는 통과하지 않는다(SEC-04). 발송 대상은 서버가 정한다(IDENTITY-04).
   - 새 challenge는 같은 session의 PENDING challenge를 취소한다. 그래서 시작 명령은 다시 보내도 쓸 수 있는 challenge가 늘지 않고 `Idempotency-Key`를 받지 않는다.
   - grant 유효 시간은 기본 2시간이고 로그인 session의 절대 만료를 넘지 않는다. 사용자가 폐기하면 만료를 지금으로 당긴다.
5. `identity.api.LearningAuthGrants.current(sessionId)`는 grant의 유효기한(grant 만료와 로그인 session 절대 만료 중 이른 쪽)만 돌려주고 만료 판정은 하지 않는다. `learning`이 자기 학습 시계(DB 시계)로 판정해 그 경계에서 구간을 자른다.
6. `learning`은 배포 설정 `astra.learning.require-auth-grant`(기본 false)가 켜져 있을 때 SC-06-A를 적용한다. 적립 규칙은 v2다.
   - 학생의 학습 session 시작에 유효한 grant가 없으면 403 `learning_auth_required`다. 담당자 미리보기는 적립하지 않으므로 요구하지 않는다.
   - 시작할 때 `credit_authorized_until = min(grant 유효기한, close_at)`을 저장한다. grant 유효기한에는 로그인 session 만료가 이미 들어 있다.
   - 하트비트와 종료마다 요청한 로그인 session의 grant를 다시 보고, 구간을 min(close_at, grant 유효기한)에서 자른다. grant가 close_at보다 먼저 끝나고 그 경계를 지났으면, 최소 간격을 보지 않고 경계까지 적립한 뒤 session을 `reauth_required`로 바꾼다. 이미 적립한 시간과 충족 기록은 그대로 둔다.
   - `reauth_required`에서는 하트비트를 받아 lease와 재생(영상 cookie)을 유지하지만 적립하지 않고 기준 시각만 지금으로 옮긴다. 유효한 grant가 생기면 다음 하트비트에서 `active`로 돌아가고, 인증이 없던 시간은 소급하지 않는다.
   - 로그인 session이 폐기되면 그 session의 요청은 401이다. 같은 사람이 다른 로그인 session으로 하트비트를 보내도 그 session에 grant가 없으면 적립하지 않는다.
   - `reauth_required`도 열린 session이다. `active_user_key` 유일 제약, 대체, lease 만료가 두 상태를 함께 다룬다.
   - 설정이 꺼져 있으면 grant를 보지 않고 `credit_authorized_until`은 NULL이며 규칙 v1로 적립한다. 켠 뒤에 시작한 session만 v2다.
7. IdP가 없는 로컬 실행은 테스트 소스의 `POST /api/me/step-up/dev`가 IdP 재인증을 대신한다(수단 `DEV`). 배포하는 jar에는 없고, `idp` 수단은 개발용 로그인이 있는 실행에서만 `DEV` 기록을 근거로 받는다.

검토하고 쓰지 않은 방법은 다음과 같다.

| 방법 | 쓰지 않은 이유 |
|---|---|
| 학습 인증 수단으로 사용자별 TOTP 등록 | 소유 요소라 2차 인증으로는 더 강하다. 그러나 사용자마다 secret을 DB에 두어야 하고, 그 암호화 key와 등록·분실·재등록 절차, 등록 전 재인증이 함께 필요하다. 학교의 인증 수단 정책이 정해지지 않았다. 수단을 port로 두었으므로 정해지면 수단 하나로 붙인다 |
| 문자·메일 코드 | 발송 제공자와 비용, 학교 연락처 원천이 정해지지 않았다. 검증할 수 없는 구현을 넣지 않는다 |
| 재인증 callback에서 바로 grant 발급 | challenge의 시도 수, 만료, 한 번 확인이 없어 원본의 상태 기계(EP-0292~EP-0295)를 대체하지 못한다. 재인증 하나가 여러 목적에 자동으로 쓰이게 된다 |
| 로그인 시각을 재인증으로 인정 | IdP가 기존 IdP session으로 화면 없이 로그인시켰을 수 있다. 사람이 방금 인증했다는 근거가 아니다 |
| grant 만료를 identity가 판정 | 학습 시계와 다른 시계로 판정하면 경계가 어긋난다. 적립 경계는 적립하는 쪽 시계 하나로 정한다 |
| `reauth_required`를 상태 대신 별도 표시 컬럼으로 | 상태 하나를 두 컬럼이 나눠 가지면 대체·lease·종료 조건이 모두 두 컬럼을 봐야 한다. SC-06-A의 상태 이름을 그대로 쓴다 |
| 재인증이 필요할 때 401 | client가 로그아웃으로 해석해 로그인 화면으로 보낸다. 인증은 유효하고 추가 확인만 필요하므로 403과 전용 code로 구분한다 |

## 결과와 트레이드오프
- `idp` 수단은 학교 IdP에 다시 로그인하는 것이다. 학교 IdP가 다요소 인증을 걸면 그것이 2차 요소가 되지만, 비밀번호만 받으면 같은 요소를 한 번 더 확인하는 것에 그친다. 계정을 빌려 준 사람이 비밀번호까지 알려 주면 막지 못한다. 막는 것은 열어 둔 session이나 훔친 쿠키가 grant 만료 뒤에도 적립하는 경우, client가 인증을 건너뛰는 경우(SEC-04), 다른 대상으로 코드를 받는 경우(IDENTITY-04)다. 학교에 IdP의 다요소 인증과 `acr` 값을 확인해야 한다.
- IdP가 `max_age` 요청에 `auth_time`을 주지 않으면 재인증이 언제나 실패한다. 학교 IdP 등록 때 확인한다.
- grant 만료(기본 2시간)마다 학생은 재인증해야 한다. 재인증하는 동안의 학습 시간은 적립하지 않는다. 기관 정책으로 grant 시간과 함께 정한다(CHG-05).
- 재인증 실패는 로그인 실패와 같은 주소 규칙이다. ID token 검증 단계에서 실패하면 로그인 실패와 같은 `/?login=failed`로 간다. 그 단계에서는 재인증인지 알기 전이다.
- `GET /api/auth/reauth`는 브라우저의 top-level 이동이라 시작하지 못할 때도 problem JSON 대신 3번의 실패 주소(`returnTo`에 `reauth=failed`)로 302를 보낸다. session이 없거나 만료됐을 때, IdP 설정이 없을 때, break-glass session일 때, 사용자당 요청 제한(10분에 10번)을 넘었을 때다. 그래서 이 경로만은 로그인하지 않은 요청도 handler까지 오지만, 검사한 같은 origin 경로로 돌려보내는 일 말고는 하지 않는다. session과 로그인 전용 쿠키를 새로 만들거나 지우지 않고, 만료된 session은 다른 요청과 같이 폐기될 뿐 되살아나지 않는다. 요청 제한은 로그인한 사용자의 시도를 그대로 센다. 만료된 session도 로그인 실패 주소(`/?login=failed`)가 아니라 시작한 경로로 보낸다. 새 탭에서 시작한 학습 인증은 그 경로에서 실패를 처음 탭에 알리고, 계정 화면은 로그인 안내를 그 자리에서 보이기 때문이다.
- break-glass TOTP 재인증 실패는 로그인 기록(`login_events`)에 실패로 남아 접속이력 화면의 로그인 기록에도 실패 줄로 보인다. 같은 잠금을 쓰기 위해서다.
- 학습 인증 설정을 바꾸면 켠 뒤 시작한 session부터 v2가 된다. 진행 중인 session은 시작할 때의 규칙을 끝까지 쓴다.
