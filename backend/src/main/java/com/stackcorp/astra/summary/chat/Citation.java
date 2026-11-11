package com.stackcorp.astra.summary.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 답이 근거로 밝힌 쪽. 그 파일에 있고 이번 답에서 모델이 실제로 받은 쪽만 남긴다.
 *
 * @param quote 답이 따옴표로 인용했고 그 쪽 텍스트에 그대로 있는 문장. 없으면 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Citation(int page, String title, String quote) {
}
