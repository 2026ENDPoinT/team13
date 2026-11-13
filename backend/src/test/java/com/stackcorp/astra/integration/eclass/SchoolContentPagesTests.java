package com.stackcorp.astra.integration.eclass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolMaterial;
import com.stackcorp.astra.identity.api.SchoolLoginException;

/**
 * 학교 강의실 안 화면 조각 해석. fixture는 2026-11 실제 화면에서 구조만 남기고 글자·번호를 모두 합성 값으로 바꾼 것이다.
 */
class SchoolContentPagesTests {

	@Test
	void filesListReadsEveryFileWithItsActivity() {
		List<SchoolFile> files = SchoolContentPages.files(page("school-files-list.html"));

		assertThat(files).hasSize(30);
		assertThat(files.stream().filter((f) -> "lecture_material".equals(f.activityType()))).hasSize(23);
		assertThat(files.stream().filter((f) -> "report".equals(f.activityType()))).hasSize(7);
		// 한 주차 카드 안의 활동마다 그 활동의 파일이 붙는다(받기 키 CONTENT_SEQ가 그 활동 번호다).
		assertThat(files).allSatisfy((f) -> assertThat(f.contentSeq()).isEqualTo(f.activityItemId()));
		assertThat(files).extracting(SchoolFile::fileSeq).doesNotHaveDuplicates();
		SchoolFile first = files.get(0);
		assertThat(first).isEqualTo(new SchoolFile("SYNKEY1000008", "1000007", "파일21.pdf", 3_355_443L,
				"lecture_material", "1000007"));
	}

	@Test
	void attachmentListReadsNameSizeAndDownloadKeys() {
		List<SchoolFile> files = SchoolContentPages.attachments(page("school-attachments.html"), "report", "1000022");

		assertThat(files).containsExactly(
				new SchoolFile("SYNKEY1000023", "1000022", "파일36.docx", 27_852L, "report", "1000022"));
	}

	@Test
	void materialDetailReadsTitleDateAuthorAndBodyAsText() throws IOException {
		String html = text("school-material.html");
		Document doc = Jsoup.parse(html);

		SchoolMaterial material = SchoolContentPages.material(doc, List.of(), 2026);

		assertThat(material.title()).isEqualTo("텍스트16");
		assertThat(material.postedAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 7, 48));
		assertThat(material.author()).isEqualTo("텍스트17");
		assertThat(material.body()).isEqualTo("텍스트18\n텍스트19");
		assertThat(SchoolContentPages.contentSeq(html)).isEqualTo("SYNKEY1000006");
	}

	@Test
	void somethingElseThanAMaterialIsNotFound() {
		Document doc = Jsoup.parse("<html><body><script>alert('권한이 없습니다.');</script></body></html>");

		assertThatThrownBy(() -> SchoolContentPages.material(doc, List.of(), 2026))
			.isInstanceOfSatisfying(SchoolLoginException.class,
					(ex) -> assertThat(ex.kind()).isEqualTo(SchoolLoginException.Kind.NOT_FOUND));
	}

	@Test
	void bodyKeepsParagraphsAndLineBreaksOnly() {
		Document doc = Jsoup.parse("<div class='editor_content'><p>첫&nbsp;줄<br>둘째   줄</p><p></p><p><br></p>"
				+ "<ul><li>하나</li><li>둘</li></ul><script>x()</script></div>");

		assertThat(SchoolContentPages.body(doc.selectFirst(".editor_content"))).isEqualTo("첫 줄\n둘째 줄\n\n하나\n둘");
	}

	@Test
	void sizeLabelsBecomeApproximateBytes() {
		assertThat(SchoolContentPages.bytes("3.2MB")).isEqualTo(3_355_443L);
		assertThat(SchoolContentPages.bytes("405.8KB")).isEqualTo(415_539L);
		assertThat(SchoolContentPages.bytes("1MB")).isEqualTo(1_048_576L);
		assertThat(SchoolContentPages.bytes("12 B")).isEqualTo(12L);
		assertThat(SchoolContentPages.bytes("크기 없음")).isNull();
		assertThat(SchoolContentPages.bytes(null)).isNull();
	}

	private static Document page(String name) {
		try {
			return Jsoup.parse(text(name));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String text(String name) throws IOException {
		try (InputStream in = SchoolContentPagesTests.class.getResourceAsStream(name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
