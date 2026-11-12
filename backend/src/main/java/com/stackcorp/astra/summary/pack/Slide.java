package com.stackcorp.astra.summary.pack;

/**
 * 쪽 하나의 해설. explain은 Markdown이다.
 */
public record Slide(int page, Tag tag, String title, String explain) {
}
