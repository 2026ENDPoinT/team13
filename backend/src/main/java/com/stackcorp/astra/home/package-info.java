/**
 * 메인 화면 집계 조회와 할 일 목록. 쓰기가 없고, 어떤 모듈도 이 모듈에 의존하지 않는다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "catalog :: api", "learning :: api", "assessment :: api", "communication :: api", "shared" })
package com.stackcorp.astra.home;
