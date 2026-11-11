package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class UploadInputTests {

	@Test
	void keepsOnlyTheLastPathSegmentAndDropsControlAndDirectionCharacters() {
		assertThat(FileNames.sanitize("../../etc/passwd")).contains("passwd");
		assertThat(FileNames.sanitize("C:\\Users\\me\\9주차 강의.pdf")).contains("9주차 강의.pdf");
		assertThat(FileNames.sanitize("evil\u202Efdp.exe")).contains("evilfdp.exe");
		assertThat(FileNames.sanitize("a\r\nSet-Cookie: x.pdf")).contains("aSet-Cookie_ x.pdf");
		assertThat(FileNames.sanitize("<img src=x onerror=1>.pdf")).contains("_img src=x onerror=1_.pdf");
		assertThat(FileNames.sanitize("  ..hidden.  ")).contains("hidden");
		assertThat(FileNames.sanitize("...")).isEmpty();
		assertThat(FileNames.sanitize(null)).isEmpty();
	}

	@Test
	void truncatesLongNamesButKeepsTheExtension() {
		String name = FileNames.sanitize("가".repeat(400) + ".pdf").orElseThrow();
		assertThat(name).hasSize(FileNames.MAX_LENGTH).endsWith(".pdf");
	}

	@Test
	void readsTheExtensionFromTheLastDotOnly() {
		assertThat(FileNames.extension("report.PDF")).contains("pdf");
		assertThat(FileNames.extension("archive.pdf.exe")).contains("exe");
		assertThat(FileNames.extension("noextension")).isEmpty();
		assertThat(FileNames.extension(".bashrc")).isEmpty();
	}

	@Test
	void acceptsContentOnlyWhenItMatchesTheExtension() {
		byte[] pdf = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
		byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.US_ASCII);
		byte[] zip = { 'P', 'K', 3, 4, 20, 0 };
		assertThat(ContentInspector.detect("pdf", pdf)).contains("application/pdf");
		assertThat(ContentInspector.detect("pdf", html)).isEmpty();
		assertThat(ContentInspector.detect("png", pdf)).isEmpty();
		assertThat(ContentInspector.detect("pptx", zip))
			.contains("application/vnd.openxmlformats-officedocument.presentationml.presentation");
		assertThat(ContentInspector.detect("pptx", pdf)).isEmpty();
		assertThat(ContentInspector.detect("txt", html)).contains("text/plain");
		assertThat(ContentInspector.detect("txt", new byte[] { 'a', 0, 'b' })).isEmpty();
		// 검사 규칙이 없는 확장자는 내용과 관계없이 거부한다.
		assertThat(ContentInspector.detect("html", html)).isEmpty();
		assertThat(ContentInspector.detect("svg", html)).isEmpty();
	}

}
