/**
 * 문서 변환 process. PDF를 쪽 이미지와 쪽 텍스트로 바꾼다(ADR-0006, SC-08). office 문서(pptx, docx, xlsx)는 LibreOffice로 먼저
 * PDF로 바꾼다(ADR-0033). 외부 실행 파일은 고정한 인자 목록으로 shell 없이 실행하고, 작업마다 새 임시 디렉터리를 쓰고 지운다.
 * <p>
 * 애플리케이션과 같은 jar에 들어 있지만 Spring을 쓰지 않는 별도 process로 실행한다. 외부 network와 AWS credential이
 * 없는 container에서 실행하고, worker만 이 process를 부른다. {@code com.stackcorp.astra}의 어떤 코드도 이 package를
 * 참조하지 않는다.
 */
package com.stackcorp.converter;
