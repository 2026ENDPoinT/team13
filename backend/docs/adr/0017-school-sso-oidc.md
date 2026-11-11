# ADR-0017 학교 SSO는 OpenID Connect authorization code flow + PKCE adapter로 먼저 만든다

## 상태
채택 (2026-11-11). 학교 IdP 프로토콜은 여전히 미정이다([architecture.md](../architecture.md) 12절). 이 결정은 학교가 OpenID Connect를 제공한다고 가정한다.

## 맥락
[ADR-0004](0004-sso-bff-session.md)는 백엔드가 학교 IdP의 client가 되고(BFF), 브라우저에는 opaque session 쿠키만 주고, `identity`의 port를 `integration`의 프로토콜별 adapter가 구현한다고 정했다. 학교가 OIDC, SAML, CAS 중 무엇을 주는지는 아직 모른다.

앱 process는 stateless다(4.3절). 로그인 시작과 callback 사이의 값(state, nonce, PKCE verifier, 돌아갈 경로)을 서버 메모리나 `HttpSession`에 둘 수 없다. IdP 장애가 앱 시작과 break-glass 로그인을 막아서도 안 된다. 외부 호출은 SC-08을 따른다.

## 결정
1. `identity.api.ExternalIdentityProvider` port는 `begin(returnTo)`와 `complete(binding, callback parameters)` 두 가지다. adapter는 브라우저에 맡길 값을 암호화한 `binding`으로 돌려주고, 검증에 성공하면 `users.external_ref`로 쓸 값만 돌려준다. 실패는 짧은 reason code가 붙은 `ExternalLoginException` 하나다. SAML이나 CAS adapter도 같은 port로 붙인다(SAML은 POST callback 경로가 따로 필요하다. ADR-0004 2번).
2. 지금 adapter는 `integration.oidc`의 OpenID Connect authorization code flow다. PKCE `S256`, `state`, `nonce`를 언제나 보낸다. 라이브러리 없이 JDK `HttpClient`와 JDK 서명 검증으로 구현한다.
   - ID token은 RS256만 받는다. `none`, HMAC, 그 밖의 알고리즘과 `crit` header는 거부한다. 서명을 확인한 뒤 `iss`, `aud`(여럿이면 `azp`), `exp`·`iat`·`nbf`(시계 오차 60초), `nonce`, `sub`를 검사한다. `iat`는 로그인 제한 시간(10분)보다 오래되면 거부한다.
   - 서명 key는 discovery 문서의 `jwks_uri`에서 받는다. 모르는 `kid`가 오면 JWKS를 한 번 다시 받고(key 교체) 그래도 없으면 거부한다. discovery는 1시간 다시 쓴다.
   - client 인증은 `client_secret_basic`이다. discovery가 `client_secret_post`만 알리면 그것을 쓴다.
   - callback에 `iss`가 있으면 설정한 issuer여야 한다(RFC 9207).
3. 로그인 전 값은 로그인 전용 쿠키 `ASTRA_LOGIN`에 둔다. 값은 AES-256-GCM으로 암호화하고 key는 client secret에서 HMAC-SHA256으로 만든다. 쿠키는 `HttpOnly`, `SameSite=Lax`, `Path=/api/auth/callback`, 10분이고 안에도 만료 시각이 있다. callback은 성공과 실패 모두 이 쿠키를 지운다. 같은 이름의 쿠키가 둘이면 거부한다.
4. callback(`GET /api/auth/callback`)은 binding, state(상수 시간 비교), code 교환, ID token, 사용자 순서로 확인한다. 사용자는 설정한 claim(`astra.oidc.user-claim`, 기본 `sub`)과 `external_ref`가 같은 ACTIVE 사용자여야 하고 break-glass 계정이 아니어야 한다. 사용자를 새로 만들지 않는다. 성공하면 다른 로그인과 같은 `SessionStore` session을 새로 만들고 `login_events`에 남긴다. 실패는 이유와 관계없이 `302 /?login=failed`이고 이유는 로그에 code로만 남긴다. 아는 사용자의 실패는 `login_events`에 `SSO_INACTIVE`, `SSO_BREAK_GLASS`로 남긴다.
5. IdP가 준 token은 저장하지 않는다. ID token은 검증하고 claim 하나를 읽은 뒤 버린다. access token과 refresh token은 쓰지 않고 userinfo endpoint도 부르지 않는다. 브라우저에는 opaque session 쿠키만 간다. token, code, state는 응답 본문과 로그에 넣지 않는다.
6. 외부 호출은 discovery, JWKS, token endpoint 세 곳이다. issuer와 같은 scheme·host·port이거나 `astra.oidc.allowed-hosts`에 적은 https host만 부른다. https만 허용하고 http는 loopback 주소(테스트)에만 허용한다. redirect를 따르지 않고, 요청마다 전체 제한 시간(기본 10초)과 응답 256 KB 상한을 둔다.
7. `astra.oidc.issuer`가 없으면 adapter bean이 없다. 로그인 시작과 callback은 404이고 아무것도 부르지 않는다. 있으면 설정 형식만 시작할 때 검사하고 IdP는 처음 로그인할 때 부른다. IdP가 멈춰도 앱은 뜨고 break-glass 로그인은 된다.
8. `GET /api/auth/methods`는 로그인 화면에 보일 수단(`oidc`, `breakGlass`, 로컬 실행의 `devLogin`)을 알린다. 설정 여부만 보고 IdP를 부르지 않는다.

검토하고 쓰지 않은 방법은 다음과 같다.

| 방법 | 쓰지 않은 이유 |
|---|---|
| Spring Security `oauth2-client`(ADR-0004 1번의 예시) | 인가 요청을 기본으로 `HttpSession`에 두고, `issuer-uri` 설정은 시작할 때 discovery를 불러 IdP 장애가 앱 시작을 막는다. host 허용 목록, 응답 크기 상한, 실패 응답 통일을 맞추려면 대부분을 갈아 끼워야 한다 |
| Nimbus JOSE(`spring-security-oauth2-jose`) | Spring Boot BOM이 판을 관리하지 않는 의존이 생긴다. 기본 JWKS source가 재요청 간격을 제한해 key 교체 직후 로그인이 막힐 수 있다. 쓰는 기능이 RS256 검증 하나라 JDK로 충분하다 |
| 서버 저장소(DB 표)에 state와 사용 기록 | 표와 정리 작업이 생긴다. code 재사용은 IdP가 거부해야 하고(RFC 6749 4.1.2), 쿠키는 callback에서 지워지고 10분 뒤 만료된다 |
| `returnTo`를 쿠키 없이 state에 넣기 | state는 URL로 오가므로 위조와 열람을 막을 수 없다 |

## 결과와 트레이드오프
- 학교가 OIDC를 주면 설정만으로 붙는다. IdP에는 redirect URI `https://<서비스 도메인>/api/auth/callback`을 등록하고, 학번이 들어 있는 ID token claim을 `astra.oidc.user-claim`으로 정한다. 그 claim이 ID token에 없고 userinfo에만 있으면 이 adapter로는 로그인할 수 없다.
- SAML이나 CAS면 `integration`에 adapter와 callback을 새로 만든다. session 생성, 사용자 매핑, 로그인 기록, `returnTo` 검사, 로그인 수단 조회는 그대로 쓴다.
- code 재사용은 쿠키 삭제와 IdP의 code 일회성에 기댄다. 쿠키와 callback 주소를 함께 훔친 사람이 피해자보다 먼저 쓰는 경우는 막지 못한다. 이 경우는 서버에 사용 기록을 두어도 같다.
- `GET /api/auth/callback`은 session을 만든다. GET이 상태를 바꾸지 않는다는 SC-04의 예외다. IdP가 브라우저를 GET으로 돌려보내기 때문이고, 같은 브라우저의 로그인 전용 쿠키와 state가 맞아야 하므로 다른 사이트가 이 요청으로 로그인을 일으킬 수 없다.
- client secret을 바꾸면 진행 중인 로그인(최대 10분)만 실패한다. secret이 바뀌어도 이미 만든 session은 그대로다.
- IdP 로그아웃 연계(RP-initiated logout)는 하지 않는다. 로그아웃은 이 서비스의 session만 끝낸다. 학교 IdP session이 남아 있으면 다음 로그인은 IdP 화면 없이 끝날 수 있다.
- 첫 로그인과 key 교체 직후에는 IdP 요청이 하나 더 생긴다. 이 규모에서는 문제가 아니다.
