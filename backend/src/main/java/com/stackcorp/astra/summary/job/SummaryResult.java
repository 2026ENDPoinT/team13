package com.stackcorp.astra.summary.job;

import java.util.List;
import java.util.SortedMap;

import com.stackcorp.astra.summary.pack.Pack;

/**
 * 완료된 작업의 결과. 파일 파생물 summary/jobs/{jobId}/result.json으로 저장한다.
 *
 * @param review 쪽 번호별로 원본과 비교해야 하는 이유
 */
public record SummaryResult(Pack pack, SortedMap<Integer, String> review, List<String> warnings) {
}
