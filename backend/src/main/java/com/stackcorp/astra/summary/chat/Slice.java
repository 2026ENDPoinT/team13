package com.stackcorp.astra.summary.chat;

import java.util.List;

/**
 * cursor 목록의 한 쪽. 쪽 크기보다 하나 더 읽어 다음 쪽이 있는지 정한다. 마지막 쪽이 쪽 크기와 같아도 빈 다음 쪽을 알리지 않는다.
 *
 * @param items 이 쪽의 항목. 최신이 먼저다
 * @param more 다음 쪽이 있으면 true
 */
public record Slice<T>(List<T> items, boolean more) {

	/** @param rows 쪽 크기보다 하나 더 읽은 행 */
	static <T> Slice<T> of(List<T> rows, int pageSize) {
		return rows.size() > pageSize ? new Slice<>(List.copyOf(rows.subList(0, pageSize)), true)
				: new Slice<>(List.copyOf(rows), false);
	}

}
