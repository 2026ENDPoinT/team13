# ADR-0009 CDN이 없는 환경에서는 앱이 전달 경로를 응답한다

## 상태
채택 (2026-11-11)

## 맥락
[ADR-0007](0007-confidential-content-placement.md)은 쪽 이미지(`/doc/`)와 HLS(`/vod/`)를 CloudFront가 `delivery` bucket에서 읽어 전달하고, 백엔드는 권한을 판정한 뒤 그 경로에만 유효한 signed cookie를 발급하도록 정했다.

프론트엔드 연결을 시작하는 시점에는 AWS 인프라가 없다. 로컬 실행과 CloudFront를 붙이지 않은 단일 서버 시연에서는 `delivery` bucket을 브라우저가 읽을 방법이 없어 쪽 이미지와 영상을 화면에 띄울 수 없다. bucket을 공개하거나 presigned URL을 내보내는 방법은 ADR-0007의 "API가 권한을 판정하고, 전달 경로는 짧은 cookie로만 연다"는 모델과 다르고, URL이 로그와 Referer에 남는다(SEC-17).

## 결정
1. `astra.delivery.origin.enabled=true`이면 앱이 `/doc/{renditionKey}/{파일}`, `/vod/{assetKey}/{파일}` 요청에 `delivery` bucket의 객체를 직접 응답한다. 기본값은 꺼짐이다.
2. 권한 흐름은 ADR-0007과 같다. 요약 조회 API와 재생 허가 API가 권한을 판정한 뒤 그 경로 prefix에만 유효한 cookie를 발급한다. 달라지는 것은 서명과 검증의 주체뿐이다.

   | | CloudFront | 앱 직접 응답 |
   |---|---|---|
   | 서명 | RSA(custom policy), cookie 3개 | HMAC-SHA256, cookie `ASTRA-Delivery` 1개 |
   | 서명 대상 | resource `{도메인}/{prefix}*`, 만료 시각 | 경로 prefix, 만료 시각 |
   | 검증 | CloudFront edge | 앱 |
   | 추가 조건 | 없음 | 로그인한 session |

3. cookie는 `HttpOnly`이고 `Path`를 그 prefix로 둔다. 값은 `만료 시각.서명`이다. 앱은 요청 경로의 prefix로 서명을 다시 계산해 상수 시간으로 비교하고 만료를 확인한다. 서명이 없거나 맞지 않으면 객체가 있는지와 관계없이 403이다.
4. 요청 경로는 정해진 형식(`doc/{64자 hex}/{3자리 숫자}.jpg`, `vod/{asset key}/{이름}.{m3u8|ts|m4s|mp4|aac|vtt}`)만 받는다. 요청 경로를 저장 key에 그대로 이어 붙이지 않는다. 응답 형식은 확장자 목록에서만 정한다.
5. 서명 key는 `astra.delivery.origin.signing-key`(Base64, 32 byte 이상)로 받는다. 비밀값이다. 로컬 실행은 기동할 때마다 새로 만든다.
6. CloudFront 서명(`astra.cloudfront.key-pair-id`)과 함께 설정하면 기동을 막는다. 둘 중 하나만 쓴다.
7. port는 그대로다. `files`의 `DeliverySigner`를 `integration`이 구현하고, 객체 읽기는 `files`의 `DeliveryObjects`로만 한다.
8. `astra.delivery.required=true`이면 서명기(CloudFront 또는 앱 직접 응답)가 없을 때 시작하지 않는다. 서명기가 없으면 cookie를 내지 않아 미리보기의 쪽 이미지, 요약의 쪽 그림, 영상 재생이 모두 열리지 않는데 다른 API는 정상이라 설정 누락이 드러나지 않기 때문이다. compose 구성은 api에 이 값을 켠다. Terraform 배포는 CloudFront 서명을, 프론트엔드 서버(STack-Frontend `server.mjs`)가 `/doc/**`, `/vod/**`를 api로 넘기는 단일 host 구성은 앱 직접 응답을 쓴다.

## 결과와 트레이드오프
- CloudFront 없이도 권한 판정 → 짧은 cookie → 전달 경로의 흐름을 그대로 확인할 수 있다. 프론트엔드는 두 환경에서 같은 URL을 쓴다.
- 영상과 쪽 이미지 바이트가 앱 서버를 지난다. [architecture.md](../architecture.md) 2절의 전송량에서는 쓸 수 없다. 학교 도입 구성은 CloudFront다.
- 객체를 메모리에 올려 응답한다. HLS segment 크기(수 MB)에 맞춘 구현이고 byte range를 지원하지 않는다.
- cookie 하나가 유출되면 만료 전까지 그 문서나 영상의 전달 경로를 열 수 있다. 앱 직접 응답은 session도 함께 요구하므로 cookie만으로는 열리지 않는다. 영상은 key가 따로 있어야 재생된다.
- api 인스턴스가 여러 대면 같은 서명 key를 넣어야 한다.
