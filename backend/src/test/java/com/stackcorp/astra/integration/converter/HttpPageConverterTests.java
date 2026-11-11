package com.stackcorp.astra.integration.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.files.api.ConversionException.Code;
import com.stackcorp.astra.files.api.PageConverter.ConvertedDocument;
import com.stackcorp.astra.files.api.PageConverter.ConvertedPage;
import com.stackcorp.converter.ConverterServer;

import static com.stackcorp.astra.TestPdfs.pdf;

class HttpPageConverterTests {

	private static ConverterServer server;

	private static HttpPageConverter converter;

	@BeforeAll
	static void start() throws IOException {
		server = ConverterServer.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1L << 20);
		converter = new HttpPageConverter(new ConverterProperties(URI.create("http://127.0.0.1:" + server.port()),
				Duration.ofSeconds(60), DataSize.ofMegabytes(64), DataSize.ofMegabytes(8)));
	}

	@AfterAll
	static void stop() {
		converter.close();
		server.close();
	}

	@Test
	void rendersPagesInOrderWithTextAndBothImageSizes() throws IOException {
		List<ConvertedPage> pages = new ArrayList<>();

		ConvertedDocument doc = converter.convert(pdf(3, null, "Relational Algebra"), 200, pages::add);

		assertThat(doc).isEqualTo(new ConvertedDocument(3, "Relational Algebra"));
		assertThat(pages).extracting(ConvertedPage::page).containsExactly(1, 2, 3);
		assertThat(pages.get(1).text()).contains("Page 2 sigma");
		assertThat(pages.get(1).textTruncated()).isFalse();
		var display = ImageIO.read(new ByteArrayInputStream(pages.getFirst().displayJpeg()));
		var model = ImageIO.read(new ByteArrayInputStream(pages.getFirst().modelJpeg()));
		assertThat(display.getWidth()).isEqualTo(1600);
		assertThat(Math.max(model.getWidth(), model.getHeight())).isLessThanOrEqualTo(1024);
	}

	@Test
	void classifiesInputsItCannotConvert() throws IOException {
		assertThat(failure(pdf(1, "user-pw", null), 200)).isEqualTo(Code.ENCRYPTED);
		assertThat(failure("hello, not a pdf".getBytes(), 200)).isEqualTo(Code.INVALID_PDF);
		assertThat(failure(pdf(4, null, null), 3)).isEqualTo(Code.TOO_MANY_PAGES);
		assertThat(failure(new byte[0], 200)).isEqualTo(Code.EMPTY);
		assertThat(failure(new byte[(1 << 20) + 1], 200)).isEqualTo(Code.TOO_LARGE);
	}

	@Test
	void acceptsOwnerPasswordOnlyPdf() throws IOException {
		List<ConvertedPage> pages = new ArrayList<>();

		converter.convert(pdf(1, "", null), 200, pages::add);

		assertThat(pages).singleElement().satisfies((p) -> assertThat(p.text()).contains("Page 1"));
	}

	@Test
	void reportsUnreachableConverter() {
		try (HttpPageConverter down = new HttpPageConverter(new ConverterProperties(URI.create("http://127.0.0.1:1"),
				Duration.ofSeconds(5), DataSize.ofMegabytes(1), DataSize.ofMegabytes(1)))) {
			ConversionException ex = catchThrowableOfType(ConversionException.class,
					() -> down.convert(new byte[] { 1 }, 200, (p) -> {
					}));
			assertThat(ex.code()).isEqualTo(Code.UNAVAILABLE);
		}
	}

	private static Code failure(byte[] pdf, int maxPages) {
		ConversionException ex = catchThrowableOfType(ConversionException.class,
				() -> converter.convert(pdf, maxPages, (p) -> {
				}));
		return ex.code();
	}

}
