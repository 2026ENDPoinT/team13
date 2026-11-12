package com.stackcorp.astra.files.api;

import java.io.InputStream;

/**
 * 악성코드 검사 port(SC-08, ADR-0018). 구현은 integration 모듈에 있고 같은 배포 안의 검사기만 부른다. 기관 자료를 외부 분석 서비스로
 * 보내지 않는다. 구현 bean이 없으면 업로드는 형식 검사만 거친다(ADR-0010 6번).
 */
public interface ContentScanner {

	/**
	 * content를 끝까지 읽어 검사한다. stream은 호출한 쪽이 닫는다. 예외를 던지지 않는다. 검사기에 닿지 못하거나 응답을 해석하지 못하면
	 * {@link Unavailable}이다.
	 *
	 * @param sizeBytes 저장할 때 잰 원본 크기
	 */
	Verdict scan(InputStream content, long sizeBytes);

	sealed interface Verdict permits Clean, Infected, Unavailable {

	}

	record Clean() implements Verdict {

	}

	/**
	 * @param signature 검사기가 보고한 signature 이름. 영문자, 숫자, {@code . _ - : /}만 남기고 100자 이하로 자른다
	 */
	record Infected(String signature) implements Verdict {

	}

	/**
	 * @param reason 운영 로그용 코드. connect, timeout, size_limit, io, protocol, error 중 하나
	 */
	record Unavailable(String reason) implements Verdict {

	}

}
