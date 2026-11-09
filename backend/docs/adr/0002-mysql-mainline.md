# ADR-0002 루트 MySQL 스키마를 본류로

## 상태
채택 (2026-11-09). GitHub 이슈 #5(STAC-90)에서 다룬 결정이다.

## 맥락
이 레포에는 Spring Boot 프로젝트가 두 개 있다.

| 프로젝트 | 실행 환경 | DB | 역할 |
|---|---|---|---|
| 루트 `src/` | Java 25, Spring Boot 4.1 | MySQL 8.4, 97 테이블 | ASTRA 재구현과 정본 스키마 |
| `backend/` 캠퍼스 온 | Java 21, Spring Boot 3.5 | PostgreSQL 17 | 학생 계정으로 e-class에 로그인해 과목·일정을 수집하는 API |

두 프로젝트의 스키마와 Flyway 이력은 호환되지 않는다. ASTRA를 대체할 서비스가 어느 쪽을 기반으로 갈지 정해야 했다.

## 결정
1. ASTRA 대체 서비스는 루트 `src/`의 MySQL 8.4 스키마를 기반으로 만든다. 정본은 `src/main/resources/db/migration/`이다.
2. 운영 DB는 Amazon RDS for MySQL 8.4를 쓴다.
3. 캠퍼스 온(`backend/`)은 독립 프로젝트로 유지한다. 루트 앱과 DB를 공유하지 않고, 두 스키마를 합치지 않는다.
4. 학교에 정식 도입하면 학생 비밀번호로 e-class를 수집할 필요가 없어진다. 그때 캠퍼스 온 기능 중 필요한 것만 루트 앱의 `integration` 모듈로 옮길지 따로 결정한다.
5. 규모가 커져 다른 DB가 필요해지면 그때 마이그레이션을 검토한다.

## 결과와 트레이드오프
- Reference 설계, 97개 테이블 baseline, ERD A, CI 스키마 점검을 그대로 쓴다.
- MySQL 8에는 부분 인덱스가 없다. 조건부 중복 금지는 생성 컬럼과 UNIQUE로 구현한다([db/README.md](../../db/README.md) 4절).
- 캠퍼스 온과 루트 앱의 Spring Boot, Java 버전이 다른 상태가 당분간 유지된다. 캠퍼스 온 업그레이드는 STAC-94에서 따로 진행한다.
