# ADR-0001 단일 프로젝트 modular monolith와 worker 프로세스

## 상태
채택 (2026-11-09)

## 맥락
사용 기관은 학교 하나이고 사용자는 수백 명이다. 수강신청, 제출, 첨부, 권한 상태는 한 transaction 안에서 일관되게 commit해야 한다([SC-05](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md#6-sc-05-원자성중복동시성)). 서비스를 네트워크로 나누면 이 원자성을 분산 transaction이나 saga로 다시 만들어야 한다. Reference의 [SC-01](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/00_server_contract.md#2-sc-01-모듈-경계)은 modular monolith와 비동기 worker를 기본안으로 두고, [90_changes.md](https://github.com/STack-Corp/ASTRA-Reference/blob/main/backend/90_changes.md)는 microservice 분리를 개선 목표로 보지 않는다.

## 결정
1. 단일 Gradle 프로젝트, 단일 배포 이미지로 만든다. Gradle 멀티모듈로 나누지 않는다.
2. `com.stackcorp.astra` 아래에 `identity`, `catalog`, `learning`, `assessment`, `communication`, `files`, `integration`, `home`, `shared` 패키지를 모듈로 둔다. 담당과 의존 방향은 [architecture.md](../architecture.md) 4.1절에 있다.
3. 모듈은 `@NamedInterface`로 지정한 `api` 하위 패키지만 공개한다. 다른 모듈의 테이블을 직접 읽거나 바꾸지 않는다.
4. 경계는 Spring Modulith의 `ApplicationModules.verify()` 테스트로 CI에서 확인한다. 순환 의존은 의존 역전으로 끊는다. 소비하는 쪽이 공개 API에 타입이나 포트를 정의하고, 이미 그 모듈에 의존하는 쪽이 호출하거나 구현한다. listener가 발행 측 이벤트 타입을 참조해도 의존이 생기므로 이벤트만으로는 순환이 끊기지 않는다.
5. 같은 이미지를 `api` 프로필과 `worker` 프로필로 나눠 실행한다. worker는 미완료 이벤트 재전송, 미디어 완료 처리, GC, 동기화 같은 배경 작업만 한다.
6. 큐 제품은 도입하지 않는다. DB 변경과 후속 작업을 함께 보장해야 하는 곳은 Spring Modulith JDBC event publication registry로 처리한다. 이벤트는 같은 transaction에 기록되고, listener는 commit 뒤 이벤트를 발행한 프로세스에서 실행된다. worker는 미완료 이벤트 재전송만 ShedLock으로 묶어 맡는다. AWS 서비스 완료 알림을 받는 SQS만 예외다.

## 결과와 트레이드오프
- 한 transaction으로 제출, 첨부 확정, 감사 기록, 후속 작업 이벤트를 함께 commit할 수 있다.
- event publication registry는 외부 broker로 내보내는 outbox가 아니다. 다른 서비스로 이벤트를 내보내야 하면 그때 outbox 구현을 따로 결정한다.
- 배포 단위가 하나라 모듈 하나만 따로 배포하거나 따로 확장할 수 없다. 이 규모에서는 필요하지 않다고 본다.
- 패키지 경계가 테스트로만 지켜진다. `verify()` 테스트를 지우거나 우회하면 경계가 무너진다.
- 특정 모듈의 부하가 다른 모듈과 크게 달라지면, 그 모듈의 공개 API가 이미 경계이므로 그 단위로 분리를 검토한다.
