package com.stackcorp.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.stackcorp.astra.TestPdfs;
import com.stackcorp.converter.PdfRendering.Step;
import com.stackcorp.converter.RenderProcess.Resume;

/**
 * 자식 JVM 변환(B5). 자식 heap을 운영(768 MiB)보다 훨씬 작은 128 MiB로 묶어, 시험 PDF가 자식 heap을 넘기게 한다.
 */
class RenderProcessTests {

	private static final int HEAP_MB = 128;

	@TempDir
	Path work;

	@Test
	void rendersEveryPageWithTextAndFullWidthImagesInsideTheChild() throws Exception {
		Map<String, byte[]> zip = render(TestPdfs.pdf(2), Duration.ofSeconds(60));

		assertThat(zip.keySet()).containsExactly("pages/001.json", "pages/001.display.jpg", "pages/001.model.jpg",
				"pages/002.json", "pages/002.display.jpg", "pages/002.model.jpg", "thumbnail.jpg", "document.json");
		assertThat(text(zip, "pages/002.json")).contains("Page 2 sigma");
		assertThat(image(zip, "pages/001.display.jpg").getWidth()).isEqualTo(PdfRendering.DISPLAY_WIDTH);
		assertThat(text(zip, "document.json")).contains("\"pageCount\":2");
		assertWorkDirectoryIsEmpty();
	}

	@Test
	void aPageThatExhaustsTheChildHeapGetsAPlaceholderAndKeepsItsTextWhileOtherPagesRender() throws Exception {
		// 8000×8000 RGB 그림은 풀면 192 MB다. 낮은 해상도로 다시 그려도 PDFBox는 Flate를 다 푼 뒤 줄이므로 대체 그림이 된다.
		Map<String, byte[]> zip = render(BombPdfs.imageBomb(3, 2, 8000, 8000), Duration.ofSeconds(120));

		assertThat(text(zip, "document.json")).contains("\"pageCount\":3");
		for (int page = 1; page <= 3; page++) {
			assertThat(text(zip, "pages/%03d.json".formatted(page))).contains("Page " + page + " caption");
		}
		assertThat(isPlaceholder(image(zip, "pages/002.display.jpg"))).isTrue();
		assertThat(image(zip, "pages/002.display.jpg").getWidth()).isEqualTo(PdfRendering.DISPLAY_WIDTH);
		assertThat(isPlaceholder(image(zip, "pages/001.display.jpg"))).isFalse();
		assertThat(isPlaceholder(image(zip, "pages/003.display.jpg"))).isFalse();
		assertThat(image(zip, "pages/002.model.jpg").getHeight()).isLessThanOrEqualTo(PdfRendering.MODEL_LONG_EDGE);
		assertThat(text(zip, "pages/002.json")).contains("\"imageUnavailable\":true");
		assertThat(text(zip, "pages/001.json")).contains("\"imageUnavailable\":false");
		assertThat(text(zip, "pages/003.json")).contains("\"imageUnavailable\":false");
		// 대체 그림도 보통 JPEG다. worker의 JPEG 검사(B3: FF D8 FF로 시작)를 그대로 통과한다.
		assertThat(zip.get("pages/002.display.jpg")).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
		assertThat(zip.get("pages/002.model.jpg")).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
		assertWorkDirectoryIsEmpty();
	}

	@Test
	void aDocumentThatExhaustsTheChildHeapWhileLoadingIsTooLarge() throws Exception {
		assertThatThrownBy(() -> render(BombPdfs.xrefBomb(), Duration.ofSeconds(60)))
			.isInstanceOfSatisfying(ConversionFailure.class, (ex) -> assertThat(ex.code()).isEqualTo("too_large"));
		assertWorkDirectoryIsEmpty();
	}

	@Test
	void aChildThatRunsPastTheTimeLimitIsKilledAndReportedAsTimeout() throws Exception {
		assertThatThrownBy(() -> render(TestPdfs.pdf(1), Duration.ofMillis(1)))
			.isInstanceOfSatisfying(ConversionFailure.class, (ex) -> assertThat(ex.code()).isEqualTo("timeout"));
		assertWorkDirectoryIsEmpty();
	}

	@Test
	void inputFailuresFromTheChildKeepTheirCode() throws Exception {
		assertThatThrownBy(() -> render("not a pdf".getBytes(StandardCharsets.US_ASCII), Duration.ofSeconds(60)))
			.isInstanceOfSatisfying(ConversionFailure.class, (ex) -> assertThat(ex.code()).isEqualTo("invalid_pdf"));
		assertWorkDirectoryIsEmpty();
	}

	@Test
	void millionsOfSavedGraphicsStatesFallBackToAPlaceholderWithoutKillingTheChild() throws Exception {
		Map<String, byte[]> zip = render(BombPdfs.savedStateBomb(), Duration.ofSeconds(60));

		assertThat(text(zip, "pages/001.json")).contains("Page 1 caption", "\"imageUnavailable\":true");
		assertThat(isPlaceholder(image(zip, "pages/001.display.jpg"))).isTrue();
	}

	@Test
	void aPageWithMillionsOfGlyphsKeepsTextExtractionWithinTheHeap() throws Exception {
		Map<String, byte[]> zip = render(BombPdfs.glyphBomb(), Duration.ofSeconds(120));

		assertThat(text(zip, "pages/001.json")).contains("\"textTruncated\":true");
	}

	@Test
	void aLargePhotoIsReadAtTheSizeItIsDrawnSoItFitsASmallHeap() throws Exception {
		// 24 MP JPEG를 그대로 풀면 RGB raster만 약 96 MB다. 쪽 폭 1600px에 맞춰 1/3로 줄여 읽는다.
		Map<String, byte[]> zip = render(BombPdfs.jpegPhoto(6000, 4000), Duration.ofSeconds(120));

		BufferedImage display = image(zip, "pages/001.display.jpg");
		assertThat(display.getWidth()).isEqualTo(PdfRendering.DISPLAY_WIDTH);
		assertThat(isPlaceholder(display)).isFalse();
	}

	@Test
	void aPageThatDrawsTooLongGetsAPlaceholderWithoutALowResolutionRetry() throws Exception {
		Duration step = Duration.ofSeconds(8);
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream err = System.err;
		long started = System.nanoTime();
		Map<String, byte[]> zip;
		System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
		try {
			zip = render(BombPdfs.strokeBomb(), Duration.ofSeconds(120), step);
		}
		finally {
			System.setErr(err);
		}

		Duration took = Duration.ofNanos(System.nanoTime() - started);
		assertThat(text(zip, "document.json")).contains("\"pageCount\":1");
		assertThat(isPlaceholder(image(zip, "pages/001.display.jpg"))).isTrue();
		assertThat(text(zip, "pages/001.json")).contains("Page 1 caption", "\"imageUnavailable\":true");
		// 1600px 그리기에서 단계 제한 시간을 한 번 쓰고(exit 4) 자식을 한 번만 다시 띄워 바로 대체 그림을 넣는다.
		assertThat(log.toString(StandardCharsets.UTF_8)).containsOnlyOnce("render child stopped exit=4")
			.contains("render=PLACEHOLDER")
			.doesNotContain("render=LOW");
		// 800px로 다시 그렸다면 단계 제한 시간을 두 번 썼다.
		assertThat(took).isLessThan(step.multipliedBy(2));
	}

	@Test
	void resumesTheCrashedPageAtTheNextStep() {
		Resume start = new Resume(1, Step.FULL, true);

		// 메모리 부족: 텍스트 생략 → 낮은 해상도 → 대체 그림
		assertThat(start.after("4 TEXT", false)).isEqualTo(new Resume(4, Step.FULL, false));
		assertThat(start.after("4 FULL", false)).isEqualTo(new Resume(4, Step.LOW, true));
		assertThat(new Resume(4, Step.FULL, false).after("4 FULL", false)).isEqualTo(new Resume(4, Step.LOW, false));
		assertThat(new Resume(4, Step.LOW, false).after("4 LOW", false)).isEqualTo(new Resume(4, Step.PLACEHOLDER, false));
		assertThat(new Resume(4, Step.LOW, false).after("6 FULL", false)).isEqualTo(new Resume(6, Step.LOW, true));
		// 단계 제한 시간 초과: 그리기가 오래 걸린 쪽은 낮은 해상도를 건너뛴다. 텍스트가 오래 걸린 쪽은 그림을 그려 본다.
		assertThat(start.after("4 FULL", true)).isEqualTo(new Resume(4, Step.PLACEHOLDER, true));
		assertThat(new Resume(4, Step.FULL, false).after("4 FULL", true)).isEqualTo(new Resume(4, Step.PLACEHOLDER, false));
		assertThat(new Resume(4, Step.LOW, false).after("4 LOW", true)).isEqualTo(new Resume(4, Step.PLACEHOLDER, false));
		assertThat(start.after("4 TEXT", true)).isEqualTo(new Resume(4, Step.FULL, false));
		for (boolean slow : new boolean[] { false, true }) {
			assertThat(start.after("0 OPEN", slow)).isNull();
			assertThat(start.after("4 PLACEHOLDER", slow)).isNull();
			assertThat(start.after("0 DOCUMENT", slow)).isNull();
			// 커널이 파일을 쓰는 도중에 자식을 끝낸 경우
			assertThat(start.after("", slow)).isNull();
			assertThat(start.after("4", slow)).isNull();
		}
	}

	private Map<String, byte[]> render(byte[] pdf, Duration timeout) throws ConversionFailure, IOException {
		return render(pdf, timeout, RenderProcess.DEFAULT_STEP_TIMEOUT);
	}

	private Map<String, byte[]> render(byte[] pdf, Duration timeout, Duration stepTimeout)
			throws ConversionFailure, IOException {
		RenderProcess process = RenderProcess.sameJvm(this.work, HEAP_MB, timeout, stepTimeout);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (RenderProcess.Rendered rendered = process.render(pdf, ConverterServer.MAX_PAGES)) {
			rendered.writeZip(bytes);
		}
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		return entries;
	}

	private void assertWorkDirectoryIsEmpty() throws IOException {
		try (Stream<Path> left = Files.list(this.work)) {
			assertThat(left).isEmpty();
		}
	}

	private static String text(Map<String, byte[]> zip, String name) {
		return new String(zip.get(name), StandardCharsets.UTF_8);
	}

	private static BufferedImage image(Map<String, byte[]> zip, String name) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(zip.get(name)));
	}

	// 대체 그림은 왼쪽 위 구석이 밝은 회색(#F2F2F2)이다. 시험 문서의 그림과 흰 쪽은 그렇지 않다.
	private static boolean isPlaceholder(BufferedImage image) {
		int rgb = image.getRGB(2, 2);
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return Math.abs(r - 0xF2) <= 4 && Math.abs(g - 0xF2) <= 4 && Math.abs(b - 0xF2) <= 4;
	}

}
