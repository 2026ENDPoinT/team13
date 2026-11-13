/**
 * 오류 형식, requestId, 시간, 멱등 원장, 감사 로그, 페이지네이션. 모든 모듈이 쓸 수 있다.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN, allowedDependencies = {})
package com.stackcorp.astra.shared;
