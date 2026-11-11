package com.stackcorp.astra.files.filebox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ZIP 항목 이름은 압축을 푼 곳 밖을 가리키지 못하고, 겹치는 이름은 접미사로 구분한다(FILES-08).
 */
class ZipEntryNamesTests {

	@Test
	void removesSeparatorsSoNoEntryCanEscapeTheExtractionDirectory() {
		List<String> names = ZipEntryNames.of(List.of("../../etc/passwd", "/abs/report.pdf", "C:\\Windows\\win.ini",
				"..", "", "...\\..\\x.txt", "a\u0000b\u202e.txt"));

		assertThat(names).containsExactly("_.._etc_passwd", "_abs_report.pdf", "C__Windows_win.ini", "file",
				"file (1)", "_.._x.txt", "ab.txt");
		assertThat(names).allSatisfy((name) -> {
			assertThat(name).doesNotContain("/", "\\").doesNotStartWith(".");
			assertThat(name.codePoints().noneMatch(Character::isISOControl)).isTrue();
		});
	}

	@Test
	void suffixesDuplicatesIgnoringCaseAndKeepsTheExtension() {
		assertThat(ZipEntryNames.of(List.of("보고서.pdf", "보고서.PDF", "Report.txt", "report.txt", "report.txt", "notes")))
			.containsExactly("보고서.pdf", "보고서 (1).PDF", "Report.txt", "report (1).txt", "report (2).txt", "notes");
	}

	@Test
	void keepsEveryNameUniqueEvenBeyondTheSuffixLimit() {
		List<String> names = ZipEntryNames.of(Collections.nCopies(ItemNames.MAX_SUFFIX + 5, "same.txt"));

		assertThat(names).doesNotHaveDuplicates().hasSize(ItemNames.MAX_SUFFIX + 5);
		assertThat(names.stream().map((n) -> n.toLowerCase(java.util.Locale.ROOT)).distinct().count())
			.isEqualTo(names.size());
	}

	@Test
	void truncatesLongNamesBeforeTheSuffixSoTheLengthLimitHolds() {
		String longName = "가".repeat(300) + ".txt";
		List<String> names = ZipEntryNames.of(List.of(longName, longName));

		assertThat(names.get(0)).hasSize(255).endsWith(".txt");
		assertThat(names.get(1)).hasSizeLessThanOrEqualTo(255).endsWith(" (1).txt");
	}

}
