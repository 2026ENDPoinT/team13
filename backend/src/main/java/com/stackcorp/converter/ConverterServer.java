package com.stackcorp.converter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 문서 변환 HTTP 서버.
 *
 * <ul>
 * <li>{@code POST /render?maxPages=N&format=F}: 본문은 문서 바이트다. format은 {@code pdf}(기본)이거나 {@link OfficeFormat}의 값이다.
 * office 형식은 {@link OfficeEngine}이 PDF로 바꾼 뒤 PDF와 같은 길로 처리한다. PDF는 heap과 시간을 묶은 자식 JVM이 쪽 그림과
 * 텍스트로 바꾼다({@link RenderProcess}). 자식이 모두 끝난 뒤에만 200과 zip을 돌려준다. 형식은 {@link RenderProcess.Rendered#writeZip}에
 * 있다. 변환할 수 없는 입력은 422와 {@code {"code","message"}}를 돌려준다. 받지 않는 형식이거나 엔진이 없는 converter면 code는
 * {@code unsupported_format}, 자식의 메모리 상한을 넘으면 {@code too_large}, 제한 시간을 넘으면 {@code timeout}이다.</li>
 * <li>{@code POST /avatar}: 본문은 PNG나 JPEG 바이트다. 성공하면 200과 {@value AvatarImage#SIZE}px 정사각형 JPEG를 돌려준다
 * ({@link AvatarImage}). 이미지가 아니면 422 {@code not_image}, 상한을 넘으면 413·422 {@code too_large}다.</li>
 * <li>{@code GET /health}: 200. 변환 port가 아니라 따로 연 상태 확인 port에서만 답한다.</li>
 * </ul>
 *
 * 한 번에 한 문서만 변환한다({@code /render}와 {@code /avatar}는 같은 잠금으로 직렬화). {@code /health}는 변환 서버와 thread를
 * 나누지 않는 상태 확인 서버가 답한다. 본문을 보내지 않거나 응답을 읽지 않는 client가 변환 thread를 모두 붙잡아도 healthcheck는
 * 바로 답을 받는다. 실행: {@code java -Dloader.main=com.stackcorp.converter.ConverterServer -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}. 환경 변수 {@code CONVERTER_PORT}(기본 8090),
 * {@code CONVERTER_HEALTH_PORT}(기본 8092, 127.0.0.1에만 연다),
 * {@code CONVERTER_MAX_BYTES}(기본 50 MiB)를 읽는다. {@code CONVERTER_SOFFICE}에 LibreOffice 실행 파일의 절대 경로가 있으면
 * office 형식을 받는다. 그때 {@code CONVERTER_PRLIMIT}(기본 /usr/bin/prlimit), {@code CONVERTER_OFFICE_TIMEOUT_SECONDS}(기본 120),
 * {@code CONVERTER_OFFICE_MAX_PDF_BYTES}(기본 100 MiB), {@code CONVERTER_OFFICE_MEMORY_BYTES}(기본 3 GiB)를 읽는다. PDF 변환 자식의
 * heap 상한은 {@code CONVERTER_RENDER_HEAP_MB}(기본 768), 문서 하나의 제한 시간은 {@code CONVERTER_RENDER_TIMEOUT_SECONDS}(기본
 * 300), 쪽 하나의 텍스트 뽑기나 그리기 한 번의 제한 시간은 {@code CONVERTER_RENDER_STEP_TIMEOUT_SECONDS}(기본 20)이다.
 */
public final class ConverterServer implements AutoCloseable {

	public static final int MAX_PAGES = 200;

	// 변환 하나가 도는 동안 줄 서서 기다릴 수 있는 요청 수. 넘으면 바로 503 busy로 돌려보낸다.
	static final int MAX_WAITING = 8;

	// HTTP 스레드 수. 변환 1 + 대기 MAX_WAITING을 다 채워도 503 응답에 쓸 스레드가 남는다. 본문을 보내지 않는 client는 이 스레드를
	// 모두 붙잡을 수 있으므로 /health는 이 스레드를 쓰지 않는다.
	static final int THREADS = 2 * (MAX_WAITING + 1);

	private final HttpServer server;

	private final ExecutorService executor;

	// GET /health만 답한다. 변환 서버와 스레드를 나눠 쓰지 않는다.
	private final HttpServer health;

	private final ExecutorService healthExecutor;

	private final long maxBytes;

	// 변환 작업(/render, /avatar)을 한 번에 하나만 돌린다.
	private final Semaphore slot = new Semaphore(1, true);

	// slot을 쥐었거나 기다리는 요청 수의 상한. 스레드가 대기 요청으로 다 차 503 응답을 굶기지 않게 한다.
	private final Semaphore admission = new Semaphore(MAX_WAITING + 1);

	private final OfficeEngine office;

	private final RenderProcess renderer;

	private ConverterServer(HttpServer server, ExecutorService executor, HttpServer health,
			ExecutorService healthExecutor, long maxBytes, OfficeEngine office, RenderProcess renderer) {
		this.server = server;
		this.executor = executor;
		this.health = health;
		this.healthExecutor = healthExecutor;
		this.maxBytes = maxBytes;
		this.office = office;
		this.renderer = renderer;
	}

	/** PDF만 받는 converter. */
	public static ConverterServer start(InetSocketAddress address, long maxBytes) throws IOException {
		return start(address, maxBytes, null);
	}

	/**
	 * 상태 확인 서버는 127.0.0.1의 빈 port에 연다({@link #healthPort}).
	 *
	 * @param office office 형식을 PDF로 바꾸는 엔진. null이면 office 형식은 unsupported_format으로 거부한다
	 */
	public static ConverterServer start(InetSocketAddress address, long maxBytes, OfficeEngine office)
			throws IOException {
		return start(address, new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), maxBytes, office,
				RenderProcess.sameJvm(Path.of(System.getProperty("java.io.tmpdir")), RenderProcess.DEFAULT_HEAP_MB,
						RenderProcess.DEFAULT_TIMEOUT, RenderProcess.DEFAULT_STEP_TIMEOUT));
	}

	/**
	 * @param healthAddress {@code GET /health}만 답하는 상태 확인 서버의 주소
	 */
	static ConverterServer start(InetSocketAddress address, InetSocketAddress healthAddress, long maxBytes,
			OfficeEngine office, RenderProcess renderer) throws IOException {
		System.setProperty("java.awt.headless", "true");
		HttpServer server = HttpServer.create(address, 0);
		HttpServer health;
		try {
			health = HttpServer.create(healthAddress, 0);
		}
		catch (IOException ex) {
			server.stop(0);
			throw ex;
		}
		// /avatar의 Java2D 처리와 자식 JVM을 기다리는 요청은 thread를 오래 잡는다. virtual thread면 carrier를 붙잡아 다른 요청까지
		// 굶길 수 있어 개수가 정해진 platform thread를 쓴다.
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		// healthcheck는 container 안에서 10초마다 하나씩 온다. 하나가 끊기지 않고 남아도 다음 확인이 답을 받도록 둘을 둔다.
		ExecutorService healthExecutor = Executors.newFixedThreadPool(2);
		ConverterServer converter = new ConverterServer(server, executor, health, healthExecutor, maxBytes, office,
				renderer);
		server.createContext("/render", (exchange) -> converter.serialized(exchange, converter::render));
		server.createContext("/avatar", (exchange) -> converter.serialized(exchange, converter::avatar));
		server.setExecutor(executor);
		health.createContext("/health", (exchange) -> {
			try (exchange) {
				respond(exchange, 200, "application/json", "{}");
			}
		});
		health.setExecutor(healthExecutor);
		server.start();
		health.start();
		return converter;
	}

	public int port() {
		return this.server.getAddress().getPort();
	}

	public int healthPort() {
		return this.health.getAddress().getPort();
	}

	@Override
	public void close() {
		this.health.stop(0);
		this.server.stop(0);
		this.healthExecutor.shutdownNow();
		this.executor.shutdownNow();
	}

	public static void main(String[] args) throws IOException {
		Map<String, String> env = System.getenv();
		int port = Integer.parseInt(env.getOrDefault("CONVERTER_PORT", "8090"));
		int healthPort = Integer.parseInt(env.getOrDefault("CONVERTER_HEALTH_PORT", "8092"));
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
		RenderProcess renderer = RenderProcess.sameJvm(Path.of(System.getProperty("java.io.tmpdir")),
				Integer.parseInt(env.getOrDefault("CONVERTER_RENDER_HEAP_MB", String.valueOf(RenderProcess.DEFAULT_HEAP_MB))),
				Duration.ofSeconds(Long.parseLong(env.getOrDefault("CONVERTER_RENDER_TIMEOUT_SECONDS",
						String.valueOf(RenderProcess.DEFAULT_TIMEOUT.toSeconds())))),
				Duration.ofSeconds(Long.parseLong(env.getOrDefault("CONVERTER_RENDER_STEP_TIMEOUT_SECONDS",
						String.valueOf(RenderProcess.DEFAULT_STEP_TIMEOUT.toSeconds())))));
		// 상태 확인은 container 안의 healthcheck만 부른다. worker가 닿는 network에는 열지 않는다(ADR-0019 3번의 관리 port와 같다).
		ConverterServer server = start(new InetSocketAddress(port),
				new InetSocketAddress(InetAddress.getLoopbackAddress(), healthPort), maxBytes, office, renderer);
		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
		System.out.println("converter listening on " + server.port() + " health=" + server.healthPort() + " office="
				+ (office != null ? "on" : "off"));
	}

	private void serialized(HttpExchange exchange, HttpHandler handler) throws IOException {
		if (!this.admission.tryAcquire()) {
			try (exchange) {
				respond(exchange, 503, "application/json", failure("busy", "converter가 다른 변환으로 바쁩니다."));
			}
			return;
		}
		try {
			try {
				this.slot.acquire();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				try (exchange) {
					respond(exchange, 503, "application/json", failure("unavailable", "converter가 멈추는 중입니다."));
				}
				return;
			}
			try {
				handler.handle(exchange);
			}
			finally {
				this.slot.release();
			}
		}
		finally {
			this.admission.release();
		}
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
			RenderProcess.Rendered rendered;
			try {
				byte[] pdf = officeFormat.isPresent() ? this.office.toPdf(body, officeFormat.get()) : body;
				rendered = this.renderer.render(pdf, maxPages);
			}
			catch (ConversionFailure ex) {
				respond(exchange, 422, "application/json", failure(ex.code(), ex.getMessage()));
				return;
			}
			try (rendered) {
				exchange.getResponseHeaders().set("content-type", "application/zip");
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream out = exchange.getResponseBody()) {
					rendered.writeZip(out);
				}
			}
		}
	}

	private void avatar(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				respond(exchange, 405, "application/json", failure("method_not_allowed", "POST만 받습니다."));
				return;
			}
			byte[] body = readCapped(exchange.getRequestBody(), Math.min(this.maxBytes, AvatarImage.MAX_BYTES));
			if (body == null) {
				respond(exchange, 413, "application/json", failure("too_large", "이미지가 크기 상한을 넘습니다."));
				return;
			}
			byte[] jpeg;
			try {
				jpeg = AvatarImage.normalize(body);
			}
			catch (ConversionFailure ex) {
				respond(exchange, 422, "application/json", failure(ex.code(), ex.getMessage()));
				return;
			}
			exchange.getResponseHeaders().set("content-type", "image/jpeg");
			exchange.sendResponseHeaders(200, jpeg.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(jpeg);
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
