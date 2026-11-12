package com.stackcorp.astra.summary.pipeline;

import java.util.List;
import java.util.SortedMap;

import com.stackcorp.astra.summary.pack.Pack;

/**
 * @param review 쪽 번호별로 사람이 원본과 비교해야 하는 이유
 * @param warnings 받아들였지만 권장 기준을 벗어난 항목
 */
public record GenerateResult(Pack pack, SortedMap<Integer, String> review, Usage usage, List<String> warnings) {
}
