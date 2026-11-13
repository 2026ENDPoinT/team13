# ADR-0004 학교 SSO를 백엔드가 받는 BFF와 session 쿠키

## 상태
채택 (2026-11-09). 학교 IdP 프로토콜은 미정이다.

## 맥락
사용자는 학교 계정으로 로그인한다. 원본 ASTRA의 로그인 방식은 UNKNOWN이다([db/README.md](../../db/README.md) 7절 1번). 학교가 OIDC, SAML, CAS 중 무엇을 제공하는지 아직 확인하지 못했다.

Reference는 session principal을 유일한 actor로 쓰고, 요청 본문의 학번이나 role을 받지 않으며, 과목 범위를 객체 단위로 검증하라고 요구한다([SC-02](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md#3-sc-02-권한-판정), [CHG-01](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md)). identity 설계는 opaque session token의 hash를 `user_sessions`에 저장하고 `session_epoch`, 기기, step-up을 둔다.

나중에 Zero-Trust 방식을 일부 적용할 계획이다.

## 결정
1. 백엔드가 학교 IdP의 client가 된다. OIDC면 Spring Security `oauth2-client`, SAML이면 `saml2-service-provider`를 쓴다. `identity`가 `ExternalIdentityProvider` 포트를 정의하고 `integration`이 프로토콜별 adapter를 구현한다.
2. IdP 토큰은 서버에만 둔다. 브라우저에는 opaque session 쿠키(`HttpOnly`, `Secure`, `SameSite=Lax`)와 CSRF 토큰만 준다. DB에는 session token의 hash만 저장한다. SAML HTTP-POST 응답은 cross-site POST라 `Lax` 쿠키가 전송되지 않으므로, SAML이면 로그인 전 요청 상태를 `RelayState` 기준 저장소에 두거나 로그인 전용 쿠키만 `SameSite=None`으로 둔다.
3. 외부 IdP의 subject와 내부 `users`를 연결하는 컬럼이나 테이블은 V1 baseline에 없다. `user_credentials`는 비밀번호 자격증명용이다. IdP가 학번·교번을 주면 `users.external_ref`(학사 식별자, UNIQUE)가 연결 키 후보다. IdP 프로토콜과 전달 속성이 정해지면 [db/README.md](../../db/README.md) 5절 절차로 연결 구조를 정한다.
4. state를 바꾸는 요청은 CSRF 토큰과 `Origin`을 검사한다. 로그아웃은 `POST`다. 허용 Origin(`astra.session.allowed-origins`)이 비어 있으면 요청 자신의 origin만 받는다. 배포에서는 브라우저가 앞단(CloudFront, 또는 `/api`를 넘기는 프론트엔드 서버)을 거쳐 오고 앞단이 Host나 scheme을 바꾸므로 이 비교가 맞지 않는다. 앱은 누구나 보낼 수 있는 `X-Forwarded-Host`로 자기 origin을 정하지 않고, 배포가 허용 Origin을 명시한다. compose 구성은 이 값이 없으면 시작하지 않는다.
5. 모든 요청은 session principal로 인증하고, 모듈별 `Policy`가 객체 단위로 인가한다. 기본은 거부다.
6. 학교 IdP 장애에 대비해 관리자 break-glass 계정 하나만 로컬 로그인을 허용하고 MFA를 강제한다. 일반 사용자의 로컬 비밀번호는 만들지 않는다.
7. 웹에는 JWT를 쓰지 않는다. 모바일 앱을 붙일 때 그 채널의 토큰 방식을 따로 결정한다.

Zero-Trust로 옮길 때 바꿀 자리는 지금 다음과 같이 둔다.

| 원칙 | 지금 | 나중에 |
|---|---|---|
| 네트워크 위치를 신뢰하지 않음 | 모든 요청에서 인증과 객체 단위 인가 | 유지 |
| 중앙 정책 판정 | 모듈별 `Policy` 인터페이스로만 판정 | Cedar 정책 엔진(AWS Verified Permissions)으로 판정부 교체 |
| 기기 신뢰 | `user_devices`에 session 결속, 기기 폐기 시 session 폐기 | 기기 상태 평가 추가 |
| 지속 검증 | session 폐기(`revoked_at`, `revoke_reason`), `session_step_ups` 재인증 | 위험도 기반 step-up |
| 인프라 최소 권한 | instance/task IAM role, Secrets Manager, 정적 access key 없음 | RDS IAM 인증, 관리 도구에 AWS Verified Access |

## 결과와 트레이드오프
- 브라우저 JS가 토큰을 다루지 않아 XSS로 토큰이 유출되지 않는다. 대신 쿠키 인증이라 CSRF 방어가 필수다.
- 인증된 요청마다 session 조회가 생긴다. 이 규모에서는 DB 조회로 충분하고, 인스턴스가 늘고 측정상 필요할 때 Valkey 캐시를 붙인다.
- IdP 프로토콜이 정해질 때까지 1단계 identity 구현을 끝낼 수 없다. 포트와 session, `Policy`는 먼저 만들 수 있다.
- break-glass 잠금(15분에 5번 실패)은 계정의 `users` 행을 잠근 transaction에서 판정하고 기록하므로 병렬 시도로 한도를 넘지 못한다. 대신 같은 계정의 로그인과 TOTP 재인증은 한 줄로 선다. 로그인 경로는 인증 전이라 사용자 단위 제한 대신 브라우저 주소 단위 제한(15분에 30번)을 둔다. 계정 이름을 아는 사람이 계정을 계속 잠가 둘 수 있는 위험은 남는다(architecture.md 12절).
