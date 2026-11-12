package com.stackcorp.converter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * 테스트와 로컬 실행에서 LibreOffice 대신 쓰는 office 엔진. pptx는 slide 수만큼, 그 밖의 형식은 한 쪽짜리 PDF를 만든다. 쪽마다
 * "Slide N"(pptx)이나 "Office page N"을 쓴다. 문서 zip에 {@code astra-test/fail} 항목이 있으면 {@code convert_failed},
 * {@code astra-test/timeout} 항목이 있으면 {@code timeout}으로 실패한다. LibreOffice의 변환 결과와 모양이 같지 않다.
 */
public final class FakeOfficeEngine implements OfficeEngine {

	private static final Pattern SLIDE = Pattern.compile("ppt/slides/slide\\d+\\.xml");

	@Override
	public byte[] toPdf(byte[] document, OfficeFormat format) throws ConversionFailure {
		Set<String> names = entries(document);
		if (names.contains("astra-test/fail")) {
			throw new ConversionFailure("convert_failed", "문서를 PDF로 바꾸지 못했습니다.");
		}
		if (names.contains("astra-test/timeout")) {
			throw new ConversionFailure("timeout", "문서 변환이 제한 시간을 넘었습니다.");
		}
		int pages = format == OfficeFormat.PPTX ? (int) names.stream().filter((n) -> SLIDE.matcher(n).matches()).count()
				: 1;
		if (pages == 0) {
			throw new ConversionFailure("convert_failed", "slide가 없는 문서입니다.");
		}
		return pdf(pages, format == OfficeFormat.PPTX ? "Slide " : "Office page ");
	}

	private static Set<String> entries(byte[] document) throws ConversionFailure {
		Set<String> names = new HashSet<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(document))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				names.add(entry.getName());
			}
		}
		catch (IOException ex) {
			throw new ConversionFailure("convert_failed", "office 문서로 읽을 수 없습니다.");
		}
		if (names.isEmpty()) {
			throw new ConversionFailure("convert_failed", "office 문서로 읽을 수 없습니다.");
		}
		return names;
	}

	private static byte[] pdf(int pages, String label) {
		try (PDDocument doc = new PDDocument()) {
			for (int i = 1; i <= pages; i++) {
				PDPage page = new PDPage(new PDRectangle(720, 405));
				doc.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
					content.newLineAtOffset(72, 300);
					content.showText(label + i);
					content.endText();
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
