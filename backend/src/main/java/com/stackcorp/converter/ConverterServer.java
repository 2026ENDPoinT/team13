package com.stackcorp.converter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 문서 변환 HTTP 서버.
 *
 * <ul>
 * <li>{@code POST /render?maxPages=N}: 본문은 PDF 바이트다. 성공하면 200과 zip을 돌려준다. 형식은
 * {@link PdfRendering#writeZip}에 있다. 변환할 수 없는 입력은 422와 {@code {"code","message"}}를 돌려준다.</li>
 * <li>{@code GET /health}: 200.</li>
 * </ul>
 *
 * 한 번에 한 문서만 변환한다. 실행: {@code java -Dloader.main=com.stackcorp.converter.ConverterServer -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}. 환경 변수 {@code CONVERTER_PORT}(기본 8090),
 * {@code CONVERTER_MAX_BYTES}(기본 50 MiB)를 읽는다.
 */
public final class ConverterServer implements AutoCloseable {

	public static final int MAX_PAGES = 200;

	private final HttpServer server;

	private final ExecutorService executor;

	private final long maxBytes;

	private ConverterServer(HttpServer server, ExecutorService executor, long maxBytes) {
		this.server = server;
		this.executor = executor;
		this.maxBytes = maxBytes;
	}

	public static ConverterServer start(InetSocketAddress address, long maxBytes) throws IOException {
		System.setProperty("java.awt.headless", "true");
		HttpServer server = HttpServer.create(address, 0);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		ConverterServer converter = new ConverterServer(server, executor, maxBytes);
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
		int port = Integer.parseInt(System.getenv().getOrDefault("CONVERTER_PORT", "8090"));
		long maxBytes = Long.parseLong(System.getenv().getOrDefault("CONVERTER_MAX_BYTES", String.valueOf(50L << 20)));
		ConverterServer server = start(new InetSocketAddress(port), maxBytes);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
		System.out.println("converter listening on " + server.port());
	}

	private void render(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				respond(exchange, 405, "application/json", failure("method_not_allowed", "POST만 받습니다."));
				return;
			}
			int maxPages = maxPages(exchange.getRequestURI().getRawQuery());
			byte[] pdf = readCapped(exchange.getRequestBody(), this.maxBytes);
			if (pdf == null) {
				respond(exchange, 413, "application/json", failure("too_large", "파일이 크기 상한을 넘습니다."));
				return;
			}
			PdfRendering rendering;
			try {
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

	private static int maxPages(String query) {
		if (query != null) {
			for (String part : query.split("&")) {
				if (part.startsWith("maxPages=")) {
					try {
						return Math.max(1, Math.min(MAX_PAGES, Integer.parseInt(part.substring(9))));
					}
					catch (NumberFormatException ex) {
						break;
					}
				}
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
