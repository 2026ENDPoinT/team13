# ADR-0005 첫 화면은 정적 shell과 집계 API 한 번으로

## 상태
채택 (2026-11-09)

## 맥락
첫 진입에서 화면이 바로 보여야 한다. 로그인한 사용자의 메인 화면은 사람마다 내용이 달라서 HTML을 CDN에 캐시할 수 없다. SSR을 쓰면 요청마다 Node 서버가 API를 호출해 HTML을 만들어야 하고, VPC 안에 프론트 서버 계층이 하나 더 생긴다. 원본 ASTRA는 알림 수(EP-0138), 할 일 수(EP-0144), 새 쪽지 확인(EP-0239)을 각각 별도 POST로 가져온다([endpoint_inventory.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/endpoint_inventory.md)).

## 결정
1. 프론트는 정적 빌드를 S3에 두고 CloudFront로 서빙한다. 프론트 실행 서버는 두지 않는다. CloudFront price class는 한국 edge가 포함되는 등급(PriceClass_200 이상)으로 둔다.
2. shell HTML에는 critical CSS와 skeleton을 inline으로 넣는다. `<head>`에서 JS 번들을 기다리지 않고 `GET /api/me/home`을 시작한다.
3. 백엔드의 `home` 모듈이 메인에 필요한 과목, 할 일, 알림 수, 공지를 한 응답으로 돌려준다. 쓰기는 하지 않는다.
4. `/api/me/home`의 p95 예산은 100 ms다. Micrometer로 측정하고, 예산을 넘을 때만 캐시를 넣는다.
5. session이 유효하면 IdP redirect를 거치지 않는다.
6. 과목 페이지는 route 단위로 code splitting하고 메인에서 미리 받는다.
7. 비로그인 공개 페이지를 검색엔진에 노출해야 하면 그 페이지만 정적 생성(SSG)한다.

## 결과와 트레이드오프
- 프론트 서버를 운영하지 않는다. 정적 파일은 서울 edge에서 응답한다.
- 첫 화면의 개인화된 내용은 API 응답이 온 뒤에 채워진다. 그 사이에는 skeleton이 보인다.
- 메인 집계 API 하나가 여러 모듈을 읽으므로 응답 형태가 메인 화면 구성에 묶인다. 화면이 바뀌면 이 API도 함께 바뀐다.
- 프론트 스택은 Vite SPA와 Next.js static export 모두 이 결정과 맞는다. SSR이 필요하다는 요구가 생기면 이 ADR을 다시 연다.
