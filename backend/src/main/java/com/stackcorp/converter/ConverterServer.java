package com.stackcorp.converter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 문서 변환 HTTP 서버.
 *
 * <ul>
 * <li>{@code POST /render?maxPages=N&format=F}: 본문은 문서 바이트다. format은 {@code pdf}(기본)이거나 {@link OfficeFormat}의 값이다.
 * office 형식은 {@link OfficeEngine}이 PDF로 바꾼 뒤 PDF와 같은 길로 처리한다. 성공하면 200과 zip을 돌려준다. 형식은
 * {@link PdfRendering#writeZip}에 있다. 변환할 수 없는 입력은 422와 {@code {"code","message"}}를 돌려준다. 받지 않는 형식이거나
 * 엔진이 없는 converter면 code는 {@code unsupported_format}이다.</li>
 * <li>{@code GET /health}: 200.</li>
 * </ul>
 *
 * 한 번에 한 문서만 변환한다. 실행: {@code java -Dloader.main=com.stackcorp.converter.ConverterServer -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}. 환경 변수 {@code CONVERTER_PORT}(기본 8090),
 * {@code CONVERTER_MAX_BYTES}(기본 50 MiB)를 읽는다. {@code CONVERTER_SOFFICE}에 LibreOffice 실행 파일의 절대 경로가 있으면
 * office 형식을 받는다. 그때 {@code CONVERTER_PRLIMIT}(기본 /usr/bin/prlimit), {@code CONVERTER_OFFICE_TIMEOUT_SECONDS}(기본 120),
 * {@code CONVERTER_OFFICE_MAX_PDF_BYTES}(기본 100 MiB), {@code CONVERTER_OFFICE_MEMORY_BYTES}(기본 3 GiB)를 읽는다.
 */
public final class ConverterServer implements AutoCloseable {

	public static final int MAX_PAGES = 200;

	private final HttpServer server;

	private final ExecutorService executor;

	private final long maxBytes;

	private final OfficeEngine office;

	private ConverterServer(HttpServer server, ExecutorService executor, long maxBytes, OfficeEngine office) {
		this.server = server;
		this.executor = executor;
		this.maxBytes = maxBytes;
		this.office = office;
	}

	/** PDF만 받는 converter. */
	public static ConverterServer start(InetSocketAddress address, long maxBytes) throws IOException {
		return start(address, maxBytes, null);
	}

	/**
	 * @param office office 형식을 PDF로 바꾸는 엔진. null이면 office 형식은 unsupported_format으로 거부한다
	 */
	public static ConverterServer start(InetSocketAddress address, long maxBytes, OfficeEngine office)
			throws IOException {
		System.setProperty("java.awt.headless", "true");
		HttpServer server = HttpServer.create(address, 0);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ConverterServer converter = new ConverterServer(server, executor, maxBytes, office);
		server.createContext("/render", converter::render);
		server.createContext("/health", (exchange) -> respond(exchange, 200, "application/json", "{}"));
		server.setExecutor(executor);
		server.start();
		return converter;
	}

	public int port() {
		return this.server.getAddress().getPort();
	}

	@Override
	public void close() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	public static void main(String[] args) throws IOException {
		Map<String, String> env = System.getenv();
		int port = Integer.parseInt(env.getOrDefault("CONVERTER_PORT", "8090"));
		long maxBytes = Long.parseLong(env.getOrDefault("CONVERTER_MAX_BYTES", String.valueOf(50L << 20)));
		String soffice = env.get("CONVERTER_SOFFICE");
		OfficeEngine office = null;
		if (soffice != null && !soffice.isBlank()) {
			office = SofficeEngine.installed(Path.of(env.getOrDefault("CONVERTER_PRLIMIT", "/usr/bin/prlimit")),
					Path.of(soffice), Path.of(System.getProperty("java.io.tmpdir")),
					Duration.ofSeconds(Long.parseLong(env.getOrDefault("CONVERTER_OFFICE_TIMEOUT_SECONDS", "120"))),
					Long.parseLong(env.getOrDefault("CONVERTER_OFFICE_MAX_PDF_BYTES", String.valueOf(100L << 20))),
					Long.parseLong(env.getOrDefault("CONVERTER_OFFICE_MEMORY_BYTES", String.valueOf(3L << 30))));
		}
		ConverterServer server = start(new InetSocketAddress(port), maxBytes, office);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
		System.out.println("converter listening on " + server.port() + " office=" + (office != null ? "on" : "off"));
	}

	private void render(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				respond(exchange, 405, "application/json", failure("method_not_allowed", "POST만 받습니다."));
				return;
			}
			Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
			int maxPages = maxPages(query.get("maxPages"));
			// 본문을 먼저 받는다. 다 받기 전에 응답하면 보내는 쪽이 연결 오류로 본다.
			byte[] body = readCapped(exchange.getRequestBody(), this.maxBytes);
			if (body == null) {
				respond(exchange, 413, "application/json", failure("too_large", "파일이 크기 상한을 넘습니다."));
				return;
			}
			String format = query.getOrDefault("format", "pdf");
			Optional<OfficeFormat> officeFormat = OfficeFormat.of(format);
			if (!"pdf".equals(format) && (officeFormat.isEmpty() || this.office == null)) {
				respond(exchange, 422, "application/json",
						failure("unsupported_format", "이 converter가 받지 않는 형식입니다."));
				return;
			}
			PdfRendering rendering;
			try {
				byte[] pdf = officeFormat.isPresent() ? this.office.toPdf(body, officeFormat.get()) : body;
				rendering = PdfRendering.open(pdf, maxPages);
			}
			catch (ConversionFailure ex) {
				respond(exchange, 422, "application/json", failure(ex.code(), ex.getMessage()));
				return;
			}
			exchange.getResponseHeaders().set("content-type", "application/zip");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				rendering.writeZip(out);
			}
		}
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> values = new HashMap<>();
		if (raw != null) {
			for (String part : raw.split("&")) {
				int eq = part.indexOf('=');
				if (eq > 0) {
					values.putIfAbsent(part.substring(0, eq), part.substring(eq + 1));
				}
			}
		}
		return values;
	}

	private static int maxPages(String value) {
		if (value != null) {
			try {
				return Math.max(1, Math.min(MAX_PAGES, Integer.parseInt(value)));
			}
			catch (NumberFormatException ex) {
				// 기본값을 쓴다.
			}
		}
		return MAX_PAGES;
	}

	// 상한을 넘으면 null
	private static byte[] readCapped(InputStream in, long max) throws IOException {
		byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
		return bytes.length > max ? null : bytes;
	}

	private static String failure(String code, String message) {
		return Json.write(Map.of("code", code, "message", message));
	}

	private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

}
