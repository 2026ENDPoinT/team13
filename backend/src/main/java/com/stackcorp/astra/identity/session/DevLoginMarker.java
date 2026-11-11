package com.stackcorp.astra.identity.session;

/**
 * 개발용 로그인이 있는 실행에만 있는 bean. 로컬 실행 구성(테스트 소스의 DevLoginConfiguration)만 등록한다. 배포하는 jar에는
 * 구현이 없다. {@code GET /api/auth/methods}가 이 bean으로 개발용 로그인을 알린다.
 */
public interface DevLoginMarker {

}
