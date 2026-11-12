package com.stackcorp.astra.files.filebox;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import com.stackcorp.astra.files.filebox.FileboxRecords.Item;
import com.stackcorp.astra.shared.ApiException;

/**
 * 파일함 목록의 정렬 key allowlist(SC-09, EP-0257의 ODR). client가 보낸 값은 SQL에 넣지 않고 여기 있는 식으로만 바꾼다.
 * 폴더는 언제나 파일보다 앞이고, 같은 값이면 항목 ID로 순서를 정한다.
 */
enum ItemSort {

	NAME("name", "i.item_name", true),

	SIZE("size", "COALESCE(f.file_size_bytes, 0)", false),

	DATE("date", "i.created_at", false);

	private final String key;

	private final String expression;

	private final boolean ascendingByDefault;

	ItemSort(String key, String expression, boolean ascendingByDefault) {
		this.key = key;
		this.expression = expression;
		this.ascendingByDefault = ascendingByDefault;
	}

	static Optional<ItemSort> of(String key) {
		for (ItemSort sort : values()) {
			if (sort.key.equals(key)) {
				return Optional.of(sort);
			}
		}
		return Optional.empty();
	}

	String key() {
		return this.key;
	}

	String expression() {
		return this.expression;
	}

	boolean ascendingByDefault() {
		return this.ascendingByDefault;
	}

	/** cursor에 담는 정렬 값. */
	String valueOf(Item item) {
		return switch (this) {
			case NAME -> item.name();
			case SIZE -> Long.toString(item.sizeBytes() == null ? 0 : item.sizeBytes());
			case DATE -> item.createdAt().toString();
		};
	}

	/** @throws ApiException cursor의 값이 이 정렬의 형식이 아니면 400 */
	Object parse(String value) {
		try {
			return switch (this) {
				case NAME -> value;
				case SIZE -> Long.parseLong(value);
				case DATE -> LocalDateTime.parse(value);
			};
		}
		catch (NumberFormatException | DateTimeParseException ex) {
			throw ApiException.invalid("cursor가 올바르지 않습니다.");
		}
	}

}
