package com.stackcorp.astra.summary.pipeline;

import java.util.Optional;

/**
 * 작업 하나의 중간 결과 저장소. 이름은 "pages/003", "story" 같은 상대 이름이다.
 */
public interface CheckpointStore {

	Optional<String> read(String name);

	void write(String name, String json);

}
