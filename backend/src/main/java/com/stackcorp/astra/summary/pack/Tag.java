package com.stackcorp.astra.summary.pack;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 쪽의 비중. 값은 LessonViewer의 Pack 스키마와 같다.
 */
public enum Tag {

	CORE("core"), SUPPORT("support"), BACKGROUND("background"), META("meta");

	private final String value;

	Tag(String value) {
		this.value = value;
	}

	@JsonValue
	public String value() {
		return value;
	}

	/** @return 값이 네 가지 중 하나가 아니면 null */
	public static Tag of(String value) {
		for (Tag tag : values()) {
			if (tag.value.equals(value)) {
				return tag;
			}
		}
		return null;
	}

}
