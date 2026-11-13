package com.stackcorp.astra.identity.session;

/**
 * 이 session이 break-glass 계정의 것인지 판정한다. 계정은 설정으로 하나만 지정한다.
 */
@FunctionalInterface
public interface BreakGlassMarker {

	boolean isBreakGlass(long userId);

}
