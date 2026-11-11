/**
 * 문서 변환 process. PDF를 쪽 이미지와 쪽 텍스트로 바꾼다(ADR-0006, SC-08).
 * <p>
 * 애플리케이션과 같은 jar에 들어 있지만 Spring을 쓰지 않는 별도 process로 실행한다. 외부 network와 AWS credential이
 * 없는 container에서 실행하고, worker만 이 process를 부른다. {@code com.stackcorp.astra}의 어떤 코드도 이 package를
 * 참조하지 않는다.
 */
package com.stackcorp.converter;
