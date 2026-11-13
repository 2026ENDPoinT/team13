package com.stackcorp.astra.summary.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.summary.pack.Tag;

/**
 * 쪽 하나에 대한 모델 출력. digest는 story 단계의 입력이고 uncertain은 확인이 필요한 이유다. checkpoint로 저장한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record PageResult(int page, Tag tag, String title, String explain, String digest, String uncertain) {
}
