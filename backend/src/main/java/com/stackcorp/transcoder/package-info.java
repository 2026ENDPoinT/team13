/**
 * 영상 변환 process. 강의 영상을 HLS MPEG-TS segment로 나눈다(ADR-0016, SC-08).
 * <p>
 * 애플리케이션과 같은 jar에 들어 있지만 Spring을 쓰지 않는 별도 process로 실행한다. 외부 network와 AWS credential이 없는
 * container에서 실행하고, worker만 이 process를 부른다. 입력은 요청 본문으로만 받고 결과는 응답으로만 돌려준다. 암호화 key는 이
 * process에 오지 않는다. {@code com.stackcorp.astra}의 어떤 코드도 이 package를 참조하지 않는다.
 */
package com.stackcorp.transcoder;
