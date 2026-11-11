package com.stackcorp.astra.files.api;

import java.util.Optional;

/**
 * 파일에서 만든 파생물(요약 결과, 중간 결과)의 저장소. content bucket에 두고 파일과 함께 삭제한다(ADR-0007).
 * name은 소문자·숫자와 {@code / . _ -}로 된 상대 이름이다. 예: summary/jobs/12/result.json
 */
public interface FileDerivatives {

	/**
	 * @throws IllegalStateException 파일이 없거나 삭제됐을 때. 삭제한 파일의 파생물은 다시 만들지 않는다(ADR-0011)
	 */
	void put(long fileId, String name, byte[] content, String contentType);

	Optional<byte[]> get(long fileId, String name);

	/** 파생물 하나를 지운다. 없으면 아무것도 하지 않는다. */
	void delete(long fileId, String name);

	/**
	 * 이름이 prefix로 시작하는 파생물을 모두 지운다. 파일 전체의 파생물은 파일을 지울 때 GC가 지운다.
	 * @param namePrefix name과 같은 형식이고 {@code /}로 끝난다. 예: studychat/12/
	 */
	void deletePrefix(long fileId, String namePrefix);

}
