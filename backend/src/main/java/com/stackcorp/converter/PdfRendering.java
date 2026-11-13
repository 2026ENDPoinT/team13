package com.stackcorp.converter;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * PDF 하나를 쪽 이미지와 쪽 텍스트로 바꾼다. 기준값은 LessonViewer scripts/module-extract.py와 같다. 이 class는 heap을 고정한 자식
 * JVM({@link RenderChild}) 안에서만 쓴다. 쪽 하나가 실패해도 나머지 쪽은 변환한다.
 * <ul>
 * <li>쪽마다 텍스트를 먼저 뽑고, 화면 폭 {@value #DISPLAY_WIDTH}px로 그린다. 실패하면 {@value #LOW_DISPLAY_WIDTH}px로 다시 그린다.
 * 그래도 실패하면 "{@value #PLACEHOLDER_TEXT}" 그림을 넣는다. 텍스트는 그대로 둔다.</li>
 * <li>큰 그림은 화면에 그릴 크기에 맞춰 줄여서 읽는다(PDFBox subsampling).</li>
 * <li>단계를 시작할 때마다 {@link Progress}에 알린다. 메모리가 차서 자식이 끝나면 부모({@link RenderProcess})가 그 단계의 다음
 * 단계부터 다시 시작한다.</li>
 * </ul>
 */
final class PdfRendering {

	// 화면용 폭과 세로로 긴 쪽의 높이 상한(px)
	static final int DISPLAY_WIDTH = 1600;

	static final int DISPLAY_MAX_HEIGHT = 2400;

	// 첫 시도가 실패한 쪽을 다시 그릴 때의 폭과 높이 상한(px)
	static final int LOW_DISPLAY_WIDTH = 800;

	static final int LOW_DISPLAY_MAX_HEIGHT = 1200;

	// 모델 입력 이미지의 긴 변 상한(px). token 사용량을 묶는다.
	static final int MODEL_LONG_EDGE = 1024;

	static final float DISPLAY_QUALITY = 0.82f;

	static final float MODEL_QUALITY = 0.72f;

	// 첫 쪽 썸네일의 긴 변(px). 뷰어 쪽 목록(180×135)의 2배 밀도다(68_문서미리보기.md 6절).
	static final int THUMBNAIL_LONG_EDGE = 360;

	static final float THUMBNAIL_QUALITY = 0.8f;

	// 쪽당 텍스트 상한(문자)
	static final int MAX_TEXT = 20000;

	// 쪽마다 텍스트를 뽑으려고 모으는 글자 수 상한. PDFTextStripper는 쪽의 모든 글자 위치를 heap에 모은 뒤 정렬하므로, 압축된
	// 내용 stream에 글자를 수천만 개 넣은 작은 PDF 하나로 heap이 찬다. 쪽 텍스트는 어차피 MAX_TEXT자로 자른다.
	static final int MAX_GLYPHS = MAX_TEXT * 5;

	// 쪽 하나를 그리거나 텍스트를 뽑는 동안 q로 쌓아 둘 수 있는 그래픽 상태 수. q마다 PDFBox는 그래픽 상태 하나를 복사해 쌓으므로
	// 압축된 "q" 수백만 개로 heap이 찬다. PDF 사양(ISO 32000-1 부록 C)의 q 중첩 상한은 28이다.
	static final int MAX_SAVED_STATES = 10_000;

	static final String PLACEHOLDER_TEXT = "이 쪽은 표시할 수 없습니다";

	// PDF 사양의 쪽 한 변 범위(pt)
	private static final float MIN_SIDE_PT = 1f;

	private static final float MAX_SIDE_PT = 14400f;

	/** 쪽 하나를 처리하는 단계. 순서대로 진행한다. */
	enum Step {

		/** PDF를 읽고 쪽 수를 확인한다. */
		OPEN,
		/** 쪽 텍스트를 뽑는다. */
		TEXT,
		/** {@value #DISPLAY_WIDTH}px로 그린다. */
		FULL,
		/** {@value #LOW_DISPLAY_WIDTH}px로 그린다. */
		LOW,
		/** 대체 그림을 넣는다. */
		PLACEHOLDER,
		/** 문서 정보를 쓴다. */
		DOCUMENT

	}

	/** 단계를 시작하기 직전에 불린다. 쪽 번호는 1부터다. 쪽과 관계없는 단계는 0이다. */
	@FunctionalInterface
	interface Progress {

		void at(int page, Step step) throws IOException;

	}

	private final PDDocument document;

	private PdfRendering(PDDocument document) {
		this.document = document;
	}

	/**
	 * PDF를 읽고 쪽 수를 확인한다. 사용자 암호가 필요한 PDF는 거부한다.
	 */
	static PdfRendering open(Path pdf, int maxPages) throws ConversionFailure, IOException {
		if (Files.size(pdf) == 0) {
			throw new ConversionFailure("empty", "빈 파일입니다.");
		}
		PDDocument document;
		try {
			document = Loader.loadPDF(pdf.toFile());
		}
		catch (InvalidPasswordException ex) {
			throw new ConversionFailure("encrypted", "암호가 걸린 PDF입니다.");
		}
		catch (IOException | RuntimeException ex) {
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
	 * from쪽부터 끝까지 out 디렉터리에 쪽마다 NNN.json, NNN.display.jpg, NNN.model.jpg를 쓴다. 첫 쪽이면 thumbnail.jpg도
	 * 쓴다. 마지막에 document.json을 쓴다. from쪽만 render 단계부터 그리고, withText가 false면 그 쪽의 텍스트를 뽑지 않는다.
	 * 다음 쪽부터는 처음 단계부터 한다.
	 *
	 * @param render {@link Step#FULL}, {@link Step#LOW}, {@link Step#PLACEHOLDER} 가운데 하나
	 */
	void writePages(Path out, int from, Step render, boolean withText, Progress progress) throws IOException {
		try {
			PDFRenderer renderer = new BoundedRenderer(this.document);
			// 그림을 화면에 그릴 크기보다 크게 풀지 않는다. JPEG·JPEG 2000은 줄여서 풀고, 나머지는 푼 뒤 줄인다.
			renderer.setSubsamplingAllowed(true);
			PDFTextStripper stripper = new BoundedTextStripper();
			stripper.setSortByPosition(true);
			int count = pageCount();
			for (int page = from; page <= count; page++) {
				boolean first = page == from;
				String text = "";
				if (!first || withText) {
					progress.at(page, Step.TEXT);
					text = extractText(stripper, page);
				}
				boolean truncated = text.length() > MAX_TEXT;
				if (truncated) {
					text = text.substring(0, MAX_TEXT);
				}
				PDRectangle box = this.document.getPage(page - 1).getCropBox();
				PageImages images = null;
				for (Step step : new Step[] { Step.FULL, Step.LOW }) {
					if (first && step.compareTo(render) < 0) {
						continue;
					}
					progress.at(page, step);
					try {
						images = render(renderer, page, box, step);
						break;
					}
					catch (IOException | RuntimeException | StackOverflowError ex) {
						// 다음 단계로 간다. 원인은 입력 문서라 로그에 남기지 않는다.
					}
				}
				boolean unavailable = images == null;
				if (unavailable) {
					progress.at(page, Step.PLACEHOLDER);
					images = images(placeholder(box), page);
				}
				String name = "%03d".formatted(page);
				Map<String, Object> meta = new LinkedHashMap<>();
				meta.put("page", page);
				meta.put("text", text);
				meta.put("textTruncated", truncated);
				// 쪽 그림 대신 대체 그림을 넣었다. worker는 이 쪽을 모델에 그림 없이 텍스트로만 보낸다.
				meta.put("imageUnavailable", unavailable);
				Files.write(out.resolve(name + ".json"), Json.write(meta).getBytes(StandardCharsets.UTF_8));
				Files.write(out.resolve(name + ".display.jpg"), images.display());
				Files.write(out.resolve(name + ".model.jpg"), images.model());
				if (images.thumbnail() != null) {
					Files.write(out.resolve("thumbnail.jpg"), images.thumbnail());
				}
			}
			progress.at(0, Step.DOCUMENT);
			String title = this.document.getDocumentInformation().getTitle();
			Map<String, Object> doc = new LinkedHashMap<>();
			doc.put("pageCount", count);
			doc.put("title", title == null || title.isBlank() ? null : title.strip());
			Files.write(out.resolve("document.json"), Json.write(doc).getBytes(StandardCharsets.UTF_8));
		}
		finally {
			close(this.document);
		}
	}

	private PageImages render(PDFRenderer renderer, int page, PDRectangle box, Step step) throws IOException {
		int width = step == Step.FULL ? DISPLAY_WIDTH : LOW_DISPLAY_WIDTH;
		int height = step == Step.FULL ? DISPLAY_MAX_HEIGHT : LOW_DISPLAY_MAX_HEIGHT;
		float scale = Math.min(width / box.getWidth(), height / box.getHeight());
		return images(renderer.renderImage(page - 1, scale, ImageType.RGB), page);
	}

	private static PageImages images(BufferedImage display, int page) throws IOException {
		byte[] thumbnail = page == 1 ? jpeg(shrink(display, THUMBNAIL_LONG_EDGE), THUMBNAIL_QUALITY) : null;
		return new PageImages(jpeg(display, DISPLAY_QUALITY), jpeg(shrink(display, MODEL_LONG_EDGE), MODEL_QUALITY),
				thumbnail);
	}

	private record PageImages(byte[] display, byte[] model, byte[] thumbnail) {
	}

	// 쪽과 같은 비율의 회색 그림 가운데에 안내 문구를 쓴다.
	static BufferedImage placeholder(PDRectangle box) {
		float scale = Math.min(DISPLAY_WIDTH / box.getWidth(), DISPLAY_MAX_HEIGHT / box.getHeight());
		int width = Math.max(1, Math.round(box.getWidth() * scale));
		int height = Math.max(1, Math.round(box.getHeight() * scale));
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(new Color(0xF2F2F2));
			g.fillRect(0, 0, width, height);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(new Color(0x555555));
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, Math.min(width / 16, height / 4))));
			FontMetrics metrics = g.getFontMetrics();
			int x = (width - metrics.stringWidth(PLACEHOLDER_TEXT)) / 2;
			int y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
			g.drawString(PLACEHOLDER_TEXT, x, y);
		}
		finally {
			g.dispose();
		}
		return image;
	}

	private String extractText(PDFTextStripper stripper, int page) {
		stripper.setStartPage(page);
		stripper.setEndPage(page);
		try {
			return stripper.getText(this.document).strip();
		}
		catch (IOException | RuntimeException | StackOverflowError ex) {
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

	private static void close(PDDocument document) {
		try {
			document.close();
		}
		catch (IOException ex) {
			// 닫기 실패는 결과에 영향이 없다.
		}
	}

	/**
	 * 쪽마다 앞에서부터 {@link #MAX_GLYPHS}개 글자만 모으고, 그래픽 상태를 {@link #MAX_SAVED_STATES}개까지만 쌓는다. 넘는 q는
	 * 무시한다. 그런 쪽은 글자 위치가 틀어질 수 있지만 텍스트는 남는다.
	 */
	private static final class BoundedTextStripper extends PDFTextStripper {

		private int glyphs;

		@Override
		protected void startPage(PDPage page) throws IOException {
			this.glyphs = 0;
			super.startPage(page);
		}

		@Override
		protected void processTextPosition(TextPosition text) {
			if (++this.glyphs <= MAX_GLYPHS) {
				super.processTextPosition(text);
			}
		}

		@Override
		public void saveGraphicsState() {
			if (getGraphicsStackSize() < MAX_SAVED_STATES) {
				super.saveGraphicsState();
			}
		}

	}

	private static final class BoundedRenderer extends PDFRenderer {

		BoundedRenderer(PDDocument document) {
			super(document);
		}

		@Override
		protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
			return new BoundedPageDrawer(parameters);
		}

	}

	/** 그래픽 상태를 {@link #MAX_SAVED_STATES}개까지만 쌓고(넘으면 그 쪽 그리기를 멈춘다), 큰 그림을 그릴 크기에 맞게 줄여 읽는다. */
	private static final class BoundedPageDrawer extends PageDrawer {

		// 쪽 좌표 1 단위 넓이가 기기에서 차지하는 화소 수
		private double devicePixelsPerUnit = 1;

		BoundedPageDrawer(PageDrawerParameters parameters) throws IOException {
			super(parameters);
		}

		@Override
		public void drawPage(Graphics2D g, PDRectangle pageSize) throws IOException {
			this.devicePixelsPerUnit = Math.abs(g.getTransform().getDeterminant());
			super.drawPage(g, pageSize);
		}

		@Override
		public void saveGraphicsState() {
			if (getGraphicsStackSize() >= MAX_SAVED_STATES) {
				throw new IllegalStateException("그래픽 상태를 너무 많이 쌓았습니다.");
			}
			super.saveGraphicsState();
		}

		// PDFBox 3.0.8은 가로×세로를 int로 곱해 넘치면 줄이지 않고, 8배보다 더 줄이지 않는다. 그 두 경우만 고친다. 줄인 그림은 여전히
		// 그릴 크기 이상이라 화질은 같다.
		@Override
		protected int getSubsampling(PDImage image, AffineTransform at) {
			int width = image.getWidth();
			int height = image.getHeight();
			double drawn = Math.abs(at.getDeterminant()) * this.devicePixelsPerUnit;
			double ratio = Math.sqrt((double) width * height / drawn);
			if (!(ratio >= 1)) {
				return 1;
			}
			return (int) Math.min(Math.floor(Math.min(ratio, Integer.MAX_VALUE)), Math.min(width, height));
		}

	}

}
