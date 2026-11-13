/**
 * 메인 화면 집계 조회와 할 일 목록, 그 자료로 답하는 화면 AI 도우미(ADR-0044). 도메인 쓰기가 없고(도우미의 감사 기록만 쓴다), 어떤 모듈도 이
 * 모듈에 의존하지 않는다. 도우미의 모델 호출은 summary가 공개한 모델 port를 쓴다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "catalog :: api", "learning :: api", "assessment :: api", "communication :: api", "summary :: api", "shared" })
package com.stackcorp.astra.home;
