<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/stack-corp-logo-dark.png">
    <img src="assets/stack-corp-logo.png" alt="STack-Corp" width="360">
  </picture>
</p>

# ASTRA-Backend

서울과학기술대학교 e-Class(ILOS LMS, 원본 서비스명 `ASTRA`) 재구현의 백엔드 구현 레포다.

## 참고 자료

명세와 설계 근거는 [STack-Corp/ASTRA-Reference](https://github.com/STack-Corp/ASTRA-Reference)에 있다.

| 문서 | 내용 |
|---|---|
| [README](https://github.com/STack-Corp/ASTRA-Reference/blob/main/README.md) | 화면별 UI·동작 명세 |
| [backend/README.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/README.md) | 서버 책임, 근거 수준(`OBSERVED`/`CLIENT`/`INFERRED`/`PROPOSED`/`UNKNOWN`) |
| [backend/00_server_contract.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md) | 권한·과목 context·트랜잭션·시간 등 서버 공통 불변식 |
| [backend/endpoint_inventory.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/endpoint_inventory.md) | 엔드포인트 ID(`EP-xxxx`)와 계약 문서 |
| [backend/90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md) | 바꿔야 할 부분과 우선순위 |
| [db/README.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/db/README.md) | DB 영역 구성, 데이터 흐름, 설계 규칙, ERD 반영 절차 |

## 두 레포의 역할

| | ASTRA-Reference | ASTRA-Backend |
|---|---|---|
| 담는 것 | 화면 명세, 엔드포인트 계약, DB 설계 이유, ERD | 서버 코드, DB 마이그레이션, 테스트 |
| 변경 기준 | 근거와 근거 수준을 함께 기록 | 계약을 지키는지 테스트로 확인 |

계약을 바꿔야 하면 Reference의 해당 문서를 먼저 고친 뒤 이 레포에서 구현한다.

## DB 스키마 기준

- 첫 DB 마이그레이션은 Reference의 [`db/astra_schema.sql`](https://github.com/STack-Corp/ASTRA-Reference/blob/main/db/astra_schema.sql)을 그대로 옮긴 것으로 한다.
- 첫 마이그레이션이 이 레포에 들어온 뒤로는 스키마 변경을 이 레포의 새 마이그레이션으로만 한다. Reference의 `astra_schema.sql`은 그 시점에 고정하고, 이후 변경은 Reference의 `db/design/*.md`(설계 이유)와 ERD 스냅샷에만 반영한다.
- 두 레포의 DDL을 각각 따로 고치지 않는다.

## 구현 순서

Reference [backend/90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md)의 P0 항목부터 닫는다.

| 순서 | 변경 | 주로 닿는 영역 |
|---|---|---|
| CHG-01 | 서버 신원과 객체 권한을 하나의 정책으로 | identity, 전 영역 |
| CHG-02 | 과목 선택을 세션 전역 상태와 분리 | catalog |
| CHG-03 | 수강신청을 폼 열기와 분리하고 한 번에 처리 | catalog |
| CHG-04 | 제출·시험·설문의 최종 상태와 첨부를 한 번에 처리 | assessment, files |
| CHG-05 | 출석·마감 시각을 서버 시계 기준으로 | learning, assessment |
| CHG-06 | 파일·변환·AI가 원문 권한을 우회하지 않게 | files |

## 작업 규칙

- 엔드포인트를 구현할 때 커밋과 PR에 해당 `EP-xxxx` ID와 `CHG-xx` 번호를 적는다.
- Reference에서 `UNKNOWN`이나 `[추정]`인 동작은 구현 전에 결정을 남긴다. 결정 없이 원본 동작이라고 가정하지 않는다.
- 원본 캡처, 개인정보, 토큰, 비밀번호는 커밋하지 않는다.
