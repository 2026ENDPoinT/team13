package com.stackcorp.astra.identity.api;

/**
 * 요청을 보낸 사용자. 인증된 session에서만 만든다. 요청 본문의 학번이나 역할은 쓰지 않는다(SC-02).
 * 컨트롤러는 {@code @AuthenticationPrincipal Actor actor}로 받는다.
 *
 * @param breakGlass 학교 IdP 장애 때 쓰는 관리자 계정으로 로그인했는지(ADR-0004 6번)
 */
public record Actor(long userId, String name, UserRole role, boolean breakGlass, long sessionId) {
}
