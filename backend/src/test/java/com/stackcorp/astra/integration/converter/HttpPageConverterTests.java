package com.stackcorp.astra.integration.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.TestOfficeFiles;
import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.files.api.ConversionException.Code;
import com.stackcorp.astra.files.api.PageConverter.ConvertedDocument;
import com.stackcorp.astra.files.api.PageConverter.ConvertedPage;
import com.stackcorp.astra.files.api.PageConverter.SourceFormat;
import com.stackcorp.converter.ConverterServer;
import com.stackcorp.converter.FakeOfficeEngine;
import com.sun.net.httpserver.HttpServer;

import static com.stackcorp.astra.TestPdfs.pdf;

class HttpPageConverterTests {

	private static ConverterServer server;

	private static ConverterServer pdfOnly;

	private static HttpPageConverter converter;

	private static HttpPageConverter pdfOnlyConverter;

	@BeforeAll
	static void start() throws IOException {
		server = ConverterServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1L << 20,
				new FakeOfficeEngine());
		pdfOnly = ConverterServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1L << 20);
		converter = client(server.port());
		pdfOnlyConverter = client(pdfOnly.port());
	}

	@AfterAll
	static void stop() {
		converter.close();
		pdfOnlyConverter.close();
		server.close();
		pdfOnly.close();
	}

	@Test
	void rendersPagesInOrderWithTextBothImageSizesAndAFirstPageThumbnail() throws IOException {
		List<ConvertedPage> pages = new ArrayList<>();

		ConvertedDocument doc = converter.convert(pdf(3, null, "Relational Algebra"), SourceFormat.PDF, 200, pages::add);

		assertThat(doc.pageCount()).isEqualTo(3);
		assertThat(doc.title()).isEqualTo("Relational Algebra");
		assertThat(pages).extracting(ConvertedPage::page).containsExactly(1, 2, 3);
		assertThat(pages.get(1).text()).contains("Page 2 sigma");
		assertThat(pages.get(1).textTruncated()).isFalse();
		assertThat(pages).extracting(ConvertedPage::imageUnavailable).containsOnly(false);
		var display = ImageIO.read(new ByteArrayInputStream(pages.getFirst().displayJpeg()));
		var model = ImageIO.read(new ByteArrayInputStream(pages.getFirst().modelJpeg()));
		var thumbnail = ImageIO.read(new ByteArrayInputStream(doc.thumbnailJpeg()));
		assertThat(display.getWidth()).isEqualTo(1600);
		assertThat(Math.max(model.getWidth(), model.getHeight())).isLessThanOrEqualTo(1024);
		assertThat(Math.max(thumbnail.getWidth(), thumbnail.getHeight())).isEqualTo(360);
	}

	@Test
	void convertsOfficeDocumentsToPdfInsideTheConverterAndRendersThemTheSameWay() {
		List<ConvertedPage> pages = new ArrayList<>();

		ConvertedDocument doc = converter.convert(TestOfficeFiles.pptx(2, null), SourceFormat.PPTX, 200, pages::add);

		assertThat(doc.pageCount()).isEqualTo(2);
		assertThat(pages).extracting(ConvertedPage::text).containsExactly("Slide 1", "Slide 2");
		assertThat(doc.thumbnailJpeg()).isNotEmpty();
	}

	@Test
	void reportsOfficeEngineFailuresAndPageLimitsOfTheConvertedPdf() {
		assertThat(failure(converter, TestOfficeFiles.pptx(1, "astra-test/fail"), SourceFormat.PPTX, 200))
			.isEqualTo(Code.FAILED);
		assertThat(failure(converter, TestOfficeFiles.pptx(1, "astra-test/timeout"), SourceFormat.PPTX, 200))
			.isEqualTo(Code.TIMEOUT);
		assertThat(failure(converter, TestOfficeFiles.pptx(4, null), SourceFormat.PPTX, 3)).isEqualTo(Code.TOO_MANY_PAGES);
		assertThat(failure(converter, "not a zip".getBytes(), SourceFormat.DOCX, 200)).isEqualTo(Code.FAILED);
	}

	@Test
	void aConverterWithoutAnOfficeEngineAnswersUnsupportedFormat() {
		assertThat(failure(pdfOnlyConverter, TestOfficeFiles.docx(), SourceFormat.DOCX, 200))
			.isEqualTo(Code.UNSUPPORTED_FORMAT);
	}

	@Test
	void classifiesInputsItCannotConvert() throws IOException {
		assertThat(failure(converter, pdf(1, "user-pw", null), SourceFormat.PDF, 200)).isEqualTo(Code.ENCRYPTED);
		assertThat(failure(converter, "hello, not a pdf".getBytes(), SourceFormat.PDF, 200)).isEqualTo(Code.INVALID_PDF);
		assertThat(failure(converter, pdf(4, null, null), SourceFormat.PDF, 3)).isEqualTo(Code.TOO_MANY_PAGES);
		assertThat(failure(converter, new byte[0], SourceFormat.PDF, 200)).isEqualTo(Code.EMPTY);
		assertThat(failure(converter, new byte[(1 << 20) + 1], SourceFormat.PDF, 200)).isEqualTo(Code.TOO_LARGE);
		assertThat(failure(converter, new byte[(1 << 20) + 1], SourceFormat.PPTX, 200)).isEqualTo(Code.TOO_LARGE);
	}

	@Test
	void acceptsOwnerPasswordOnlyPdf() throws IOException {
		List<ConvertedPage> pages = new ArrayList<>();

		converter.convert(pdf(1, "", null), SourceFormat.PDF, 200, pages::add);

		assertThat(pages).singleElement().satisfies((p) -> assertThat(p.text()).contains("Page 1"));
	}

	@Test
	void reportsUnreachableConverter() {
		try (HttpPageConverter down = new HttpPageConverter(new ConverterProperties(URI.create("http://127.0.0.1:1"),
				Duration.ofSeconds(5), DataSize.ofMegabytes(1), DataSize.ofMegabytes(1)))) {
			ConversionException ex = catchThrowableOfType(ConversionException.class,
					() -> down.convert(new byte[] { 1 }, SourceFormat.PDF, 200, (p) -> {
					}));
			assertThat(ex.code()).isEqualTo(Code.UNAVAILABLE);
		}
	}

	@Test
	void rejectsResponsesWithoutAThumbnailOrWithPagesAfterIt() throws IOException {
		byte[] page = "{\"page\":1,\"text\":\"\"}".getBytes();
		byte[] doc = "{\"pageCount\":1,\"title\":null}".getBytes();
		assertThat(failureFrom(zip("pages/001.json", page, "pages/001.display.jpg", page, "pages/001.model.jpg", page,
				"document.json", doc))).isEqualTo(Code.FAILED);
		assertThat(failureFrom(zip("pages/001.json", page, "pages/001.display.jpg", page, "pages/001.model.jpg", page,
				"thumbnail.jpg", page, "pages/002.json", page, "document.json", doc))).isEqualTo(Code.FAILED);
		assertThat(failureFrom(zip("thumbnail.jpg", page, "document.json", "{\"pageCount\":0}".getBytes())))
			.isEqualTo(Code.FAILED);
	}

	@Test
	void readsWhichPagesCarryAPlaceholderInsteadOfTheirImage() throws IOException {
		byte[] jpeg = "jpg".getBytes();
		List<ConvertedPage> pages = new ArrayList<>();

		// 2쪽은 이 값을 넣기 전의 converter 응답처럼 imageUnavailable이 없다.
		serve(zip("pages/001.json", "{\"page\":1,\"text\":\"a\",\"textTruncated\":false,\"imageUnavailable\":true}".getBytes(),
				"pages/001.display.jpg", jpeg, "pages/001.model.jpg", jpeg, "pages/002.json",
				"{\"page\":2,\"text\":\"b\"}".getBytes(), "pages/002.display.jpg", jpeg, "pages/002.model.jpg", jpeg,
				"thumbnail.jpg", jpeg, "document.json", "{\"pageCount\":2,\"title\":null}".getBytes()),
				(target) -> target.convert(new byte[] { 1 }, SourceFormat.PDF, 200, pages::add));

		assertThat(pages).extracting(ConvertedPage::imageUnavailable).containsExactly(true, false);
	}

	private static HttpPageConverter client(int port) {
		return new HttpPageConverter(new ConverterProperties(URI.create("http://127.0.0.1:" + port),
				Duration.ofSeconds(60), DataSize.ofMegabytes(64), DataSize.ofMegabytes(8)));
	}

	// 정해진 zip을 돌려주는 converter 자리의 HTTP 서버로 응답 검사를 확인한다.
	private static Code failureFrom(byte[] zip) throws IOException {
		return serve(zip, (target) -> failure(target, new byte[] { 1 }, SourceFormat.PDF, 200));
	}

	private static <T> T serve(byte[] zip, Function<HttpPageConverter, T> call) throws IOException {
		HttpServer fake = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		fake.createContext("/render", (exchange) -> {
			exchange.getRequestBody().readAllBytes();
			exchange.getResponseHeaders().set("content-type", "application/zip");
			exchange.sendResponseHeaders(200, zip.length);
			try (var out = exchange.getResponseBody()) {
				out.write(zip);
			}
		});
		fake.start();
		try (HttpPageConverter target = client(fake.getAddress().getPort())) {
			return call.apply(target);
		}
		finally {
			fake.stop(0);
		}
	}

	private static byte[] zip(Object... nameAndContent) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (int i = 0; i < nameAndContent.length; i += 2) {
				zip.putNextEntry(new ZipEntry((String) nameAndContent[i]));
				zip.write((byte[]) nameAndContent[i + 1]);
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private static Code failure(HttpPageConverter target, byte[] document, SourceFormat format, int maxPages) {
		ConversionException ex = catchThrowableOfType(ConversionException.class,
				() -> target.convert(document, format, maxPages, (p) -> {
				}));
		return ex.code();
	}

}
