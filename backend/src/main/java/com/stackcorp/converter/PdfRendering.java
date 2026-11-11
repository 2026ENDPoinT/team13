package com.stackcorp.converter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * PDF 하나를 쪽 이미지와 쪽 텍스트로 바꾼다. 기준값은 LessonViewer scripts/module-extract.py와 같다.
 */
final class PdfRendering {

	// 화면용 폭과 세로로 긴 쪽의 높이 상한(px)
	static final int DISPLAY_WIDTH = 1600;

	static final int DISPLAY_MAX_HEIGHT = 2400;

	// 모델 입력 이미지의 긴 변 상한(px). token 사용량을 묶는다.
	static final int MODEL_LONG_EDGE = 1024;

	static final float DISPLAY_QUALITY = 0.82f;

	static final float MODEL_QUALITY = 0.72f;

	// 쪽당 텍스트 상한(문자)
	static final int MAX_TEXT = 20000;

	// PDF 사양의 쪽 한 변 범위(pt)
	private static final float MIN_SIDE_PT = 1f;

	private static final float MAX_SIDE_PT = 14400f;

	private final PDDocument document;

	private PdfRendering(PDDocument document) {
		this.document = document;
	}

	/**
	 * PDF를 읽고 쪽 수를 확인한다. 사용자 암호가 필요한 PDF는 거부한다.
	 */
	static PdfRendering open(byte[] pdf, int maxPages) throws ConversionFailure {
		if (pdf.length == 0) {
			throw new ConversionFailure("empty", "빈 파일입니다.");
		}
		PDDocument document;
		try {
			document = Loader.loadPDF(pdf);
		}
		catch (InvalidPasswordException ex) {
			throw new ConversionFailure("encrypted", "암호가 걸린 PDF입니다.");
		}
		catch (IOException ex) {
			throw new ConversionFailure("invalid_pdf", "PDF로 읽을 수 없는 파일입니다.");
		}
		int pages = document.getNumberOfPages();
		if (pages == 0) {
			close(document);
			throw new ConversionFailure("empty", "쪽이 없는 PDF입니다.");
		}
		if (pages > maxPages) {
			close(document);
			throw new ConversionFailure("too_many_pages", "쪽 수가 " + maxPages + "쪽을 넘습니다.");
		}
		for (PDPage page : document.getPages()) {
			PDRectangle box = page.getCropBox();
			float w = box.getWidth();
			float h = box.getHeight();
			if (!(w >= MIN_SIDE_PT && h >= MIN_SIDE_PT && w <= MAX_SIDE_PT && h <= MAX_SIDE_PT)) {
				close(document);
				throw new ConversionFailure("invalid_pdf", "쪽 크기가 PDF 사양 범위를 벗어납니다.");
			}
		}
		return new PdfRendering(document);
	}

	int pageCount() {
		return this.document.getNumberOfPages();
	}

	/**
	 * zip으로 쪽마다 pages/NNN.json, pages/NNN.display.jpg, pages/NNN.model.jpg를 순서대로 쓰고 마지막에 document.json을
	 * 쓴다. 중간에 실패하면 error.json을 쓰고 끝낸다.
	 */
	void writeZip(OutputStream out) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.setLevel(0);
			try {
				PDFRenderer renderer = new PDFRenderer(this.document);
				PDFTextStripper stripper = new PDFTextStripper();
				stripper.setSortByPosition(true);
				int count = pageCount();
				for (int i = 0; i < count; i++) {
					int page = i + 1;
					String name = "pages/%03d".formatted(page);
					String text = extractText(stripper, page);
					boolean truncated = text.length() > MAX_TEXT;
					if (truncated) {
						text = text.substring(0, MAX_TEXT);
					}
					PDRectangle box = this.document.getPage(i).getCropBox();
					float scale = Math.min(DISPLAY_WIDTH / box.getWidth(), DISPLAY_MAX_HEIGHT / box.getHeight());
					BufferedImage display = renderer.renderImage(i, scale, ImageType.RGB);
					BufferedImage model = shrink(display, MODEL_LONG_EDGE);
					Map<String, Object> meta = new LinkedHashMap<>();
					meta.put("page", page);
					meta.put("text", text);
					meta.put("textTruncated", truncated);
					entry(zip, name + ".json", Json.write(meta).getBytes(StandardCharsets.UTF_8));
					entry(zip, name + ".display.jpg", jpeg(display, DISPLAY_QUALITY));
					entry(zip, name + ".model.jpg", jpeg(model, MODEL_QUALITY));
				}
				String title = this.document.getDocumentInformation().getTitle();
				Map<String, Object> doc = new LinkedHashMap<>();
				doc.put("pageCount", count);
				doc.put("title", title == null || title.isBlank() ? null : title.strip());
				entry(zip, "document.json", Json.write(doc).getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException | RuntimeException ex) {
				entry(zip, "error.json", Json.write(Map.of("code", "failed", "message", "쪽을 변환하지 못했습니다."))
					.getBytes(StandardCharsets.UTF_8));
			}
		}
		finally {
			close(this.document);
		}
	}

	void close() {
		close(this.document);
	}

	private String extractText(PDFTextStripper stripper, int page) {
		stripper.setStartPage(page);
		stripper.setEndPage(page);
		try {
			return stripper.getText(this.document).strip();
		}
		catch (IOException ex) {
			// 텍스트를 읽지 못한 쪽은 이미지로만 다룬다.
			return "";
		}
	}

	private static BufferedImage shrink(BufferedImage source, int longEdge) {
		int max = Math.max(source.getWidth(), source.getHeight());
		if (max <= longEdge) {
			return source;
		}
		double ratio = (double) longEdge / max;
		int w = Math.max(1, (int) Math.round(source.getWidth() * ratio));
		int h = Math.max(1, (int) Math.round(source.getHeight() * ratio));
		BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, w, h, null);
		}
		finally {
			g.dispose();
		}
		return target;
	}

	private static byte[] jpeg(BufferedImage image, float quality) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content);
		zip.closeEntry();
	}

	private static void close(PDDocument document) {
		try {
			document.close();
		}
		catch (IOException ex) {
			// 닫기 실패는 결과에 영향이 없다.
		}
	}

}
