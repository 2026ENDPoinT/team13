package com.stackcorp.astra.files.api;

import java.util.Optional;

/**
 * 파일에서 만든 파생물(요약 결과, 중간 결과)의 저장소. content bucket에 두고 파일과 함께 삭제한다(ADR-0007).
 * name은 소문자·숫자와 {@code / . _ -}로 된 상대 이름이다. 예: summary/jobs/12/result.json
 */
public interface FileDerivatives {

	void put(long fileId, String name, byte[] content, String contentType);

	Optional<byte[]> get(long fileId, String name);

}
