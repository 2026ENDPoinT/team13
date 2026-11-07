<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/stack-corp-logo-dark.png">
    <img src="assets/stack-corp-logo.png" alt="STack-Corp" width="360">
  </picture>
</p>

# STack-Backend

서울과학기술대학교 e-Class(ILOS LMS, 원본 서비스명 `ASTRA`) 재구현의 백엔드 구현 레포다.

## 폴더 구성

| 경로 | 내용 |
|---|---|
| `reference/` | [ASTRA-Reference](https://github.com/STack-Corp/ASTRA-Reference) 서브모듈: 화면 명세, 엔드포인트 계약, DB 설계 근거 |
| `src/` | Spring Boot 애플리케이션. DB 마이그레이션은 `src/main/resources/db/migration/` |
| `db/` | ERD 생성·비교 도구, ERDCloud 스냅샷, [DB 가이드](db/README.md) |
| `assets/` | 팀 로고 |

처음 받을 때는 서브모듈까지 함께 받는다.

```sh
git clone --recurse-submodules git@github.com:STack-Corp/STack-Backend.git
# 이미 받았다면
git submodule update --init
```

## 참고 자료

| 문서 | 내용 |
|---|---|
| [reference/README.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/README.md) | 화면별 UI·동작 명세 |
| [reference/backend/README.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/README.md) | 서버 책임, 근거 수준(`OBSERVED`/`CLIENT`/`INFERRED`/`PROPOSED`/`UNKNOWN`) |
| [reference/backend/00_server_contract.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md) | 권한·과목 context·트랜잭션·시간 등 서버 공통 불변식 |
| [reference/backend/endpoint_inventory.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/endpoint_inventory.md) | 엔드포인트 ID(`EP-xxxx`)와 계약 문서 |
| [reference/backend/90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md) | 바꿔야 할 부분과 우선순위 |
| [reference/db/design/](https://github.com/STack-Corp/ASTRA-Reference/tree/main/db/design) | DB 영역별 설계 근거와 미결 사항 |
| [db/README.md](db/README.md) | DB 영역 구성, 데이터 흐름, 설계 규칙, 스키마 변경·ERD 반영 절차 |

## 두 레포의 역할

| | ASTRA-Reference | STack-Backend |
|---|---|---|
| 담는 것 | 화면 명세, 엔드포인트 계약, DB 설계 근거 | 서버 코드, 정본 DB 스키마, ERD 도구·스냅샷, 테스트 |
| 변경 기준 | 근거와 근거 수준을 함께 기록 | 계약을 지키는지 테스트로 확인 |

명세나 계약을 바꿔야 하면 Reference를 먼저 고친다. 그다음 이 레포에서 서브모듈을 그 변경으로 올리고 구현한다.

```sh
git -C reference pull origin main
git add reference
git commit -m "Update reference to <변경 요약>"
```

서브모듈은 특정 커밋에 고정된다. Reference에 새 커밋이 생겨도 이 레포의 `reference/`는 위 명령으로 올리기 전까지 바뀌지 않는다.

## DB 스키마 기준

- 정본은 Flyway 마이그레이션 [`src/main/resources/db/migration/`](src/main/resources/db/migration/)이다. `V1__baseline.sql`은 Reference에서 설계한 97개 테이블 그대로다. Reference의 `db/design/*.md`에 있는 DDL 조각은 설계 당시 초안이다.
- 이미 적용된 마이그레이션 파일은 고치지 않는다. 바꿀 내용은 다음 번호의 새 파일로 만든다.
- 변경·점검·ERD 반영 절차는 [db/README.md](db/README.md) 5~6절을 따른다.

## 빌드와 실행

| 항목 | 버전 |
|---|---|
| JDK | 25 (Gradle toolchain으로 지정) |
| Spring Boot | 4.1.2 |
| Gradle | 9.8.2 (wrapper 포함, 따로 설치하지 않음) |
| DB | MySQL 8.4, Flyway |

테스트와 로컬 실행에는 Docker가 필요하다. Testcontainers가 MySQL 8.4 컨테이너를 띄우고 마이그레이션을 적용한다.

```sh
./gradlew build          # Windows: gradlew.bat build
./gradlew bootTestRun    # DB 없이 로컬 실행. MySQL 컨테이너를 자동으로 띄운다
```

8080 포트를 다른 프로그램이 쓰고 있으면 `Port 8080 was already in use`로 실패한다. Docker로 띄운 다른 앱이 8080을 쓰는 경우가 많다. 이때는 포트를 바꿔 실행한다.

```sh
./gradlew bootTestRun --args=--server.port=18080    # Windows: gradlew.bat bootTestRun --args=--server.port=18080
```

실제 DB에 붙여 실행할 때는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수를 넣고 `./gradlew bootRun`을 쓴다.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml)이 `main` 푸시와 모든 PR에서 두 작업을 실행한다.

| 작업 | 확인하는 것 |
|---|---|
| Build and test | `./gradlew build`. 테스트가 MySQL 8.4 컨테이너에 Flyway 마이그레이션을 적용하고 앱이 뜨는지 확인한다 |
| Schema check and ERD snapshot | 마이그레이션을 MySQL 8.4에 적용한 뒤 `db/tools/check.sql`로 깨진 FK, COMMENT 누락, 고립 테이블을 찾는다. `db/erdcloud/snapshot.json`이 현재 스키마로 다시 만든 결과와 같은지도 확인한다 |

마이그레이션을 추가했다면 같은 PR에서 `python db/tools/erdcloud_snapshot.py`로 스냅샷도 갱신해야 CI가 통과한다. CI는 `reference` 서브모듈을 받지 않는다. 빌드에 필요 없고, 비공개 레포라 기본 토큰으로는 받을 수 없기 때문이다.

## 구현 순서

[reference/backend/90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md)의 P0 항목부터 닫는다.

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
