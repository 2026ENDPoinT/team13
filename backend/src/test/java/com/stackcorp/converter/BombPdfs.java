package com.stackcorp.converter;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * 메모리 시험용 PDF. 몇백 KB짜리 PDF가 풀거나 그리는 동안 수백 MB를 쓰게 만든다. 쪽마다 "Page N caption" 텍스트가 있다.
 */
final class BombPdfs {

	private static final String CATALOG = "<< /Type /Catalog /Pages 2 0 R >>";

	private static final String HELVETICA = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>";

	private BombPdfs() {
	}

	/**
	 * pages쪽 문서. bombPage쪽에는 width×height RGB Flate 그림을 쪽 전체에 그린다. 그림 데이터는 0으로 채워 deflate했으므로
	 * 1억 화소(약 300 MB)가 약 0.3 MB가 된다. PDFBox는 Flate stream을 heap에 모두 풀어 둔 뒤 읽는다.
	 */
	static byte[] imageBomb(int pages, int bombPage, int width, int height) throws IOException {
		Pdf pdf = new Pdf();
		int font = 3 + 2 * pages;
		int image = font + 1;
		pdf.object(CATALOG);
		StringBuilder kids = new StringBuilder();
		for (int i = 0; i < pages; i++) {
			kids.append(3 + 2 * i).append(" 0 R ");
		}
		pdf.object("<< /Type /Pages /Kids [" + kids + "] /Count " + pages + " >>");
		for (int page = 1; page <= pages; page++) {
			boolean bomb = page == bombPage;
			pdf.object("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " + (4 + 2 * (page - 1))
					+ " 0 R /Resources << /Font << /F1 " + font + " 0 R >>"
					+ (bomb ? " /XObject << /Im1 " + image + " 0 R >>" : "") + " >> >>");
			pdf.stream("", ((bomb ? "q 612 0 0 792 0 0 cm /Im1 Do Q\n" : "") + "BT /F1 24 Tf 72 700 Td (Page " + page
					+ " caption) Tj ET\n").getBytes(StandardCharsets.US_ASCII));
		}
		pdf.object(HELVETICA);
		pdf.stream("/Type /XObject /Subtype /Image /Width " + width + " /Height " + height
				+ " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode",
				deflate("", "\0", 3L * width * height, ""));
		return pdf.withXrefTable();
	}

	/** 교차 참조 stream이 512 MiB로 풀린다. {@code Loader.loadPDF} 안에서 풀린다. */
	static byte[] xrefBomb() throws IOException {
		Pdf pdf = new Pdf();
		pdf.object(CATALOG);
		pdf.object("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
		pdf.object("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
		return pdf.withXrefStream(deflate("", "\0", 512L << 20, ""));
	}

	/** 쪽 내용 stream이 "q" 약 370만 개로 풀린다. q마다 PDFBox가 그래픽 상태를 하나씩 쌓는다. 앞에 텍스트가 있다. */
	static byte[] savedStateBomb() throws IOException {
		return textPage(deflate("BT /F1 24 Tf 72 700 Td (Page 1 caption) Tj ET\n", "q\n", 7L << 20, ""));
	}

	/** 쪽 내용 stream이 쪽을 가로지르는 굵은 선 약 130만 개로 풀린다. 메모리는 적게 쓰지만 그리는 데 오래 걸린다. 앞에 텍스트가 있다. */
	static byte[] strokeBomb() throws IOException {
		return textPage(deflate("BT /F1 24 Tf 72 700 Td (Page 1 caption) Tj ET\n40 w 1 J\n", "0 0 m 612 792 l S\n",
				24L << 20, ""));
	}

	/** 쪽 내용 stream이 8 MiB, 약 700만 글자로 풀린다. 쪽 텍스트를 뽑을 때 글자 위치가 쌓인다. */
	static byte[] glyphBomb() throws IOException {
		return textPage(deflate("BT /F1 1 Tf 10 10 Td\n", "(" + "A".repeat(58) + ") Tj\n", 8L << 20, "ET\n"));
	}

	/** width×height 사진 한 장을 쪽 전체에 그린 문서. 화소마다 값이 달라 JPEG가 실제 사진처럼 크다. */
	static byte[] jpegPhoto(int width, int height) throws IOException {
		BufferedImage photo = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				photo.setRGB(x, y, ((x * 7) & 0xFF) << 16 | ((y * 5) & 0xFF) << 8 | ((x ^ y) & 0xFF));
			}
		}
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage(new PDRectangle(width * 0.12f, height * 0.12f));
			doc.addPage(page);
			PDImageXObject image = JPEGFactory.createFromImage(doc, photo, 0.9f);
			try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
				content.drawImage(image, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	// FlateDecode 하나뿐인 내용 stream과 Helvetica 하나를 가진 쪽
	private static byte[] textPage(byte[] deflatedContent) {
		Pdf pdf = new Pdf();
		pdf.object(CATALOG);
		pdf.object("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
		pdf.object("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R"
				+ " /Resources << /Font << /F1 5 0 R >> >> >>");
		pdf.stream("/Filter /FlateDecode", deflatedContent);
		pdf.object(HELVETICA);
		return pdf.withXrefTable();
	}

	// head, unit을 size바이트까지 되풀이한 것, tail을 이어 deflate한 결과. 같은 내용의 512 MiB가 약 0.5 MiB가 된다.
	private static byte[] deflate(String head, String unit, long size, String tail) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] one = unit.getBytes(StandardCharsets.US_ASCII);
		byte[] chunk = new byte[one.length * ((1 << 16) / one.length)];
		for (int i = 0; i < chunk.length; i += one.length) {
			System.arraycopy(one, 0, chunk, i, one.length);
		}
		try (OutputStream out = new DeflaterOutputStream(bytes, new Deflater(Deflater.BEST_COMPRESSION))) {
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			for (long left = size; left > 0; left -= chunk.length) {
				out.write(chunk, 0, (int) Math.min(chunk.length, left));
			}
			out.write(tail.getBytes(StandardCharsets.US_ASCII));
		}
		return bytes.toByteArray();
	}

	/** 객체를 차례로 쓰고 마지막에 교차 참조를 붙이는 최소 PDF 작성기. */
	private static final class Pdf {

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private final List<Integer> offsets = new ArrayList<>();

		Pdf() {
			ascii("%PDF-1.7\n");
		}

		void object(String body) {
			begin();
			ascii(body + "\nendobj\n");
		}

		void stream(String dictionary, byte[] data) {
			begin();
			ascii("<< /Length " + data.length + " " + dictionary + " >>\nstream\n");
			this.out.writeBytes(data);
			ascii("\nendstream\nendobj\n");
		}

		byte[] withXrefTable() {
			int start = this.out.size();
			StringBuilder xref = new StringBuilder("xref\n0 " + (this.offsets.size() + 1) + "\n0000000000 65535 f \n");
			for (int offset : this.offsets) {
				xref.append("%010d 00000 n \n".formatted(offset));
			}
			ascii(xref + "trailer\n<< /Size " + (this.offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + start
					+ "\n%%EOF\n");
			return this.out.toByteArray();
		}

		byte[] withXrefStream(byte[] deflated) {
			int start = this.out.size();
			int size = this.offsets.size() + 2;
			stream("/Type /XRef /Size " + size + " /W [1 4 2] /Root 1 0 R /Filter /FlateDecode", deflated);
			ascii("startxref\n" + start + "\n%%EOF\n");
			return this.out.toByteArray();
		}

		private void begin() {
			this.offsets.add(this.out.size());
			ascii(this.offsets.size() + " 0 obj\n");
		}

		private void ascii(String text) {
			this.out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
		}

	}

}
