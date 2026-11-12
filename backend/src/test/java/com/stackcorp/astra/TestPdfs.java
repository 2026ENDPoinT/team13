package com.stackcorp.astra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * 테스트용 PDF. 쪽마다 "Page N sigma"를 쓴다.
 */
public final class TestPdfs {

	private TestPdfs() {
	}

	public static byte[] pdf(int pages) {
		return pdf(pages, null, null);
	}

	/**
	 * @param userPassword null이면 암호화하지 않는다. 빈 문자열이면 소유자 암호만 건다
	 */
	public static byte[] pdf(int pages, String userPassword, String title) {
		try (PDDocument doc = new PDDocument()) {
			for (int i = 1; i <= pages; i++) {
				PDPage page = new PDPage(new PDRectangle(720, 405));
				doc.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
					content.newLineAtOffset(72, 300);
					content.showText("Page " + i + " sigma");
					content.endText();
				}
			}
			if (title != null) {
				PDDocumentInformation info = new PDDocumentInformation();
				info.setTitle(title);
				doc.setDocumentInformation(info);
			}
			if (userPassword != null) {
				StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", userPassword,
						new AccessPermission());
				policy.setEncryptionKeyLength(256);
				doc.protect(policy);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
