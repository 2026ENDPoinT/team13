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

import com.stackcorp.astra.identity.api.SchoolCourseContent;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolAssignment;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolNotice;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolPost;
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

		SchoolPost material = SchoolContentPages.post(doc, List.of(), 2026);

		assertThat(material.title()).isEqualTo("텍스트16");
		assertThat(material.postedAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 7, 48));
		assertThat(material.author()).isEqualTo("텍스트17");
		assertThat(material.body()).isEqualTo("텍스트18\n텍스트19");
		assertThat(SchoolContentPages.contentSeq(html)).isEqualTo("SYNKEY1000006");
	}

	@Test
	void somethingElseThanAMaterialIsNotFound() {
		Document doc = Jsoup.parse("<html><body><script>alert('권한이 없습니다.');</script></body></html>");

		assertThatThrownBy(() -> SchoolContentPages.post(doc, List.of(), 2026))
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

	@Test
	void noticeListReadsEveryNoticeWithReadMarkViewsAndDateInTheCourseYear() throws IOException {
		String html = text("school-notice-list.html");

		List<SchoolNotice> notices = SchoolContentPages.notices(Jsoup.parse(html), html, 2026, (id) -> "u" + id);

		assertThat(notices).extracting(SchoolNotice::itemId).containsExactly("1000003", "1000001", "1000002");
		SchoolNotice first = notices.get(0);
		assertThat(first.title()).isEqualTo("텍스트1");
		assertThat(first.postedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 21, 0));
		// 댓글 수 칸(.comment)은 조회수가 아니다.
		assertThat(notices).extracting(SchoolNotice::viewCount).containsExactly(54, 155, 93);
		assertThat(notices).allSatisfy((n) -> assertThat(n.read()).isTrue());
		assertThat(notices).extracting(SchoolNotice::expired).containsExactly(false, false, true);
		assertThat(first.url()).isEqualTo("u1000003");
		assertThat(SchoolContentPages.noticeTotal(html)).isEqualTo(3);
	}

	@Test
	void noticeListUnreadMarkAndUnexpectedRowsAreReadWithoutThrowing() throws IOException {
		String html = text("school-notice-list-long.html")
			.replaceFirst("class=\"is_read \"", "class=\"is_read unread\"")
			.replace("<!-- [S]Paging -->", "<button class=\"board_list_wrap\" data-num=\"x1\"><div class=\"board_title\">"
					+ "이상한 행</div></button><button class=\"board_list_wrap\" data-num=\"1999999\"></button>"
					+ "<button class=\"board_list_wrap\" data-num=\"1999998\"><div class=\"board_title\">날짜 없음</div>"
					+ "<div class=\"reg_info\">곧</div></button><!-- [S]Paging -->");

		List<SchoolNotice> notices = SchoolContentPages.notices(Jsoup.parse(html), html, 2026, (id) -> id);

		assertThat(notices).hasSize(7);
		assertThat(notices.get(0).read()).isFalse();
		assertThat(notices.subList(1, 7)).allSatisfy((n) -> assertThat(n.read()).isTrue());
		SchoolNotice odd = notices.get(6);
		assertThat(odd.itemId()).isEqualTo("1999998");
		assertThat(odd.postedAt()).isNull();
		assertThat(odd.viewCount()).isNull();
		assertThat(notices.get(5).postedAt()).isEqualTo(LocalDateTime.of(2026, 8, 31, 15, 4));
	}

	@Test
	void anEmptyNoticeListIsEmptyAndSomethingElseIsUnrecognized() throws IOException {
		String empty = text("school-notice-list-empty.html");
		assertThat(SchoolContentPages.notices(Jsoup.parse(empty), empty, 2026, (id) -> id)).isEmpty();

		String other = "<html><body><script>alert('오류');</script></body></html>";
		assertThatThrownBy(() -> SchoolContentPages.notices(Jsoup.parse(other), other, 2026, (id) -> id))
			.isInstanceOfSatisfying(SchoolLoginException.class,
					(ex) -> assertThat(ex.kind()).isEqualTo(SchoolLoginException.Kind.UNAVAILABLE));
	}

	@Test
	void noticeDetailRenderedInEnglishReadsAuthorDateBodyAndAttachmentKey() throws IOException {
		String html = text("school-notice-view-english.html");

		SchoolPost notice = SchoolContentPages.post(Jsoup.parse(html), List.of(), 2026);

		assertThat(notice.title()).isEqualTo("텍스트1");
		assertThat(notice.author()).isEqualTo("텍스트2");
		assertThat(notice.postedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 21, 0));
		assertThat(notice.body()).isEqualTo("텍스트4\n텍스트5\n텍스트6\n텍스트7\n텍스트8\n텍스트9");
		assertThat(SchoolContentPages.contentSeq(html)).isEqualTo("SYNKEY0000010");
		assertThat(SchoolContentPages.attachments(page("school-notice-attachments-empty.html"), "notice", "1"))
			.isEmpty();
	}

	@Test
	void noticeDetailInKoreanAndWithUnknownLabelsFindsTheAuthorByPosition() throws IOException {
		String korean = text("school-notice-view-english.html").replace(">Writer<", ">작성자<")
			.replace(">Publishing Date<", ">게시일<")
			.replace("Thu, November 5 9:00 PM", "11월 5일 (목) 오후 9:00");
		String unknown = korean.replace(">작성자<", ">글쓴이<").replace(">게시일<", ">날짜<");

		for (String html : List.of(korean, unknown)) {
			SchoolPost notice = SchoolContentPages.post(Jsoup.parse(html), List.of(), 2026);
			assertThat(notice.author()).isEqualTo("텍스트2");
			assertThat(notice.postedAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 21, 0));
		}
	}

	@Test
	void assignmentDetailReadsPeriodDescriptionAttachmentsAndTheStudentsOwnSubmission() throws IOException {
		String html = text("school-report-view.html");
		List<SchoolFile> files = SchoolContentPages.attachments(page("school-report-attachments.html"), "report",
				"1000011");
		List<SchoolFile> submitted = SchoolContentPages.attachments(page("school-report-submitted.html"),
				SchoolCourseContent.SUBMISSION, "1000011");

		SchoolAssignment report = SchoolContentPages.assignment(Jsoup.parse(html), files, submitted, 2026);

		// 첫 efile_list는 과제 첨부, 둘째(content_seq 변수)는 학생 본인 제출 파일이다.
		assertThat(SchoolContentPages.contentSeq(html)).isEqualTo("SYNKEY0000014");
		assertThat(SchoolContentPages.submittedContentSeq(html)).isEqualTo("SYNKEY0000013");
		assertThat(report.title()).isEqualTo("텍스트1");
		assertThat(report.openAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 11, 39));
		assertThat(report.closeAt()).isEqualTo(LocalDateTime.of(2026, 11, 17, 23, 59));
		assertThat(report.body()).startsWith("텍스트3\n텍스트4").endsWith("텍스트13");
		assertThat(report.files()).containsExactly(
				new SchoolFile("SYNKEY0000015", "1000011", "텍스트1", 2_969L, "report", "1000011"));
		assertThat(report.submittedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 12, 1));
		assertThat(report.submissionLabel()).isEqualTo("정상제출");
		assertThat(report.submissionBody()).isNull();
		assertThat(report.submittedFiles()).extracting(SchoolFile::fileSeq)
			.containsExactly("SYNKEY0000017", "SYNKEY0000016");
		assertThat(report.submittedFiles()).allSatisfy(
				(f) -> assertThat(f.activityType()).isEqualTo(SchoolCourseContent.SUBMISSION));
	}

	@Test
	void assignmentNotSubmittedHasNoSubmissionAndLabelsDoNotMatter() throws IOException {
		String html = text("school-report-view.html")
			.replace("content_seq = \"SYNKEY0000013\";", "")
			.replaceAll("(?s)<div class=\"submit_info_box\">.*?</div>\\s*</div>", "</div>")
			.replace("공개일", "Open Date")
			.replace("마감일", "Due Date")
			.replace("배점", "Points");

		SchoolAssignment report = SchoolContentPages.assignment(Jsoup.parse(html), List.of(), List.of(), 2026);

		assertThat(SchoolContentPages.submittedContentSeq(html)).isNull();
		assertThat(report.openAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 11, 39));
		assertThat(report.closeAt()).isEqualTo(LocalDateTime.of(2026, 11, 17, 23, 59));
		assertThat(report.submittedAt()).isNull();
		assertThat(report.submissionLabel()).isNull();
		assertThat(report.submittedFiles()).isEmpty();
	}

	@Test
	void aPageThatIsNotAnAssignmentIsNotFound() {
		assertThatThrownBy(() -> SchoolContentPages.assignment(page("school-notice-list.html"), List.of(), List.of(),
				2026))
			.isInstanceOfSatisfying(SchoolLoginException.class,
					(ex) -> assertThat(ex.kind()).isEqualTo(SchoolLoginException.Kind.NOT_FOUND));
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
