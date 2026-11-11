/**
 * 학교 IdP, 학사 동기화, AI, 외부 allowlist client. 다른 모듈이 공개 API에 정의한 port를 구현한다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "identity :: api", "catalog :: api", "files :: api", "summary :: api", "shared" })
package com.stackcorp.astra.integration;
