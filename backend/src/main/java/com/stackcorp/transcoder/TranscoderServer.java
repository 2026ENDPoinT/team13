package com.stackcorp.transcoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 영상 변환 HTTP 서버.
 *
 * <ul>
 * <li>{@code POST /transcode?container=mp4|mov|webm|mkv}: 본문은 원본 영상 바이트이고 Content-Length가 있어야 한다. 성공하면 200과
 * zip을 돌려준다. zip에는 {@code index.m3u8}(암호화하지 않은 재생 목록)이 먼저, 그다음 {@code seg_NNNNN.ts}가 순서대로 있다. 변환할
 * 수 없는 입력은 422와 {@code {"code","message"}}를 돌려준다. code: invalid_media, too_long, too_large, timeout.</li>
 * <li>{@code GET /health}: 200.</li>
 * </ul>
 *
 * 한 번에 영상 하나만 변환한다. 원본과 결과는 요청마다 만드는 작업 directory(소유자만 접근)에만 두고 응답을 보낸 뒤 지운다. 실행:
 * {@code java -Dloader.main=com.stackcorp.transcoder.TranscoderServer -cp app.jar
 * org.springframework.boot.loader.launch.PropertiesLauncher}. 환경 변수는 {@link Limits#fromEnvironment}에 있다.
 */
public final class TranscoderServer implements AutoCloseable {

	private static final Pattern CONTAINER = Pattern.compile("container=([a-z0-9]{1,8})");

	private static final Pattern SEGMENT = Pattern.compile("seg_[0-9]{5}\\.ts");

	private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(2);

	private final HttpServer server;

	private final ExecutorService executor;

	private final Limits limits;

	private final MediaTools tools;

	private TranscoderServer(HttpServer server, ExecutorService executor, Limits limits) {
		this.server = server;
		this.executor = executor;
		this.limits = limits;
		this.tools = new MediaTools(limits.ffmpeg(), limits.ffprobe(), limits.threads());
	}

	public static TranscoderServer start(InetSocketAddress address, Limits limits) throws IOException {
		Files.createDirectories(limits.workRoot());
		HttpServer server = HttpServer.create(address, 0);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		TranscoderServer transcoder = new TranscoderServer(server, executor, limits);
		server.createContext("/transcode", transcoder::transcode);
		server.createContext("/health", (exchange) -> respond(exchange, 200, "application/json", "{}"));
		server.setExecutor(executor);
		server.start();
		return transcoder;
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
		int port = Integer.parseInt(env.getOrDefault("TRANSCODER_PORT", "8091"));
		TranscoderServer server = start(new InetSocketAddress(port), Limits.fromEnvironment(env));
		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
		System.out.println("transcoder listening on " + server.port());
	}

	private void transcode(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				respond(exchange, 405, "application/json", failure("method_not_allowed", "POST만 받습니다."));
				return;
			}
			String query = exchange.getRequestURI().getRawQuery();
			var container = query == null ? null : CONTAINER.matcher(query);
			if (container == null || !container.matches() || !MediaTools.supported(container.group(1))) {
				respond(exchange, 400, "application/json", failure("invalid_request", "container 값이 올바르지 않습니다."));
				return;
			}
			// 크기는 본문을 읽기 전에 판정한다.
			String length = exchange.getRequestHeaders().getFirst("Content-Length");
			if (length == null || !length.matches("[0-9]{1,19}")) {
				respond(exchange, 411, "application/json", failure("length_required", "Content-Length가 필요합니다."));
				return;
			}
			long size = Long.parseLong(length);
			if (size > this.limits.maxInputBytes()) {
				respond(exchange, 413, "application/json", failure("too_large", "파일이 크기 상한을 넘습니다."));
				return;
			}
			if (size == 0) {
				respond(exchange, 422, "application/json", failure("invalid_media", "빈 파일입니다."));
				return;
			}
			Path workDir = Files.createTempDirectory(this.limits.workRoot(), "transcode-",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			try {
				List<Path> outputs;
				try {
					outputs = run(exchange.getRequestBody(), size, container.group(1), workDir);
				}
				catch (TranscodeFailure ex) {
					respond(exchange, 422, "application/json", failure(ex.code(), ex.getMessage()));
					return;
				}
				catch (IOException ex) {
					// 작업 directory나 ffmpeg 실행에 실패했다. 입력 탓이 아니므로 다시 시도할 수 있다.
					respond(exchange, 500, "application/json", failure("internal", "변환을 실행하지 못했습니다."));
					return;
				}
				exchange.getResponseHeaders().set("content-type", "application/zip");
				exchange.sendResponseHeaders(200, 0);
				try (ZipOutputStream zip = new ZipOutputStream(exchange.getResponseBody())) {
					zip.setLevel(0);
					for (Path output : outputs) {
						zip.putNextEntry(new ZipEntry(output.getFileName().toString()));
						Files.copy(output, zip);
						zip.closeEntry();
					}
				}
			}
			finally {
				deleteRecursively(workDir);
			}
		}
	}

	/** @return 재생 목록, 그다음 segment 순서의 결과 파일 */
	private List<Path> run(InputStream body, long size, String container, Path workDir)
			throws IOException, TranscodeFailure {
		Path input = workDir.resolve("input");
		try (OutputStream out = Files.newOutputStream(input)) {
			long copied = body.transferTo(out);
			if (copied != size) {
				throw new TranscodeFailure("invalid_media", "받은 크기가 Content-Length와 다릅니다.");
			}
		}
		OptionalDouble duration = this.tools.probeDuration(workDir, input, container, PROBE_TIMEOUT);
		if (duration.isPresent() && duration.getAsDouble() > this.limits.maxDurationSeconds()) {
			throw new TranscodeFailure("too_long", "재생 길이가 상한을 넘습니다.");
		}
		Path outDir = Files.createDirectory(workDir.resolve("out"));
		this.tools.segment(workDir, input, container, outDir, this.limits.maxDurationSeconds(), this.limits.timeout());
		Path playlist = outDir.resolve(MediaTools.PLAYLIST);
		List<Path> segments;
		try (Stream<Path> files = Files.list(outDir)) {
			segments = files.filter((p) -> SEGMENT.matcher(p.getFileName().toString()).matches()).sorted().toList();
		}
		if (!Files.isRegularFile(playlist) || segments.isEmpty()) {
			throw new TranscodeFailure("invalid_media", "영상을 변환하지 못했습니다.");
		}
		long total = Files.size(playlist);
		for (Path segment : segments) {
			total += Files.size(segment);
		}
		if (total > this.limits.maxOutputBytes()) {
			throw new TranscodeFailure("too_large", "변환 결과가 크기 상한을 넘습니다.");
		}
		return Stream.concat(Stream.of(playlist), segments.stream()).toList();
	}

	private static void deleteRecursively(Path dir) {
		try (Stream<Path> paths = Files.walk(dir)) {
			paths.sorted(Comparator.reverseOrder()).forEach((p) -> {
				try {
					Files.deleteIfExists(p);
				}
				catch (IOException ex) {
					// 남은 파일은 container를 다시 만들 때 사라진다. 다음 요청에는 새 directory를 쓴다.
				}
			});
		}
		catch (IOException ex) {
			// 위와 같다.
		}
	}

	private static String failure(String code, String message) {
		return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("content-type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/**
	 * @param maxInputBytes 원본 크기 상한. 환경 변수 TRANSCODER_MAX_BYTES(기본 2 GiB)
	 * @param maxOutputBytes 결과 전체 크기 상한. TRANSCODER_MAX_OUTPUT_BYTES(기본 4 GiB)
	 * @param timeout ffmpeg 실행 제한 시간. TRANSCODER_TIMEOUT_SECONDS(기본 7200)
	 * @param maxDurationSeconds 재생 길이 상한. TRANSCODER_MAX_DURATION_SECONDS(기본 14400)
	 * @param threads ffmpeg encoder thread 수. TRANSCODER_THREADS(기본 2)
	 * @param workRoot 작업 directory를 만들 곳. TRANSCODER_WORK_DIR(기본 java.io.tmpdir)
	 * @param ffmpeg ffmpeg 실행 파일. TRANSCODER_FFMPEG(기본 PATH의 ffmpeg)
	 * @param ffprobe ffprobe 실행 파일. TRANSCODER_FFPROBE(기본 PATH의 ffprobe)
	 */
	public record Limits(long maxInputBytes, long maxOutputBytes, Duration timeout, long maxDurationSeconds,
			int threads, Path workRoot, String ffmpeg, String ffprobe) {

		public static Limits fromEnvironment(Map<String, String> env) {
			return new Limits(Long.parseLong(env.getOrDefault("TRANSCODER_MAX_BYTES", String.valueOf(2L << 30))),
					Long.parseLong(env.getOrDefault("TRANSCODER_MAX_OUTPUT_BYTES", String.valueOf(4L << 30))),
					Duration.ofSeconds(Long.parseLong(env.getOrDefault("TRANSCODER_TIMEOUT_SECONDS", "7200"))),
					Long.parseLong(env.getOrDefault("TRANSCODER_MAX_DURATION_SECONDS", "14400")),
					Integer.parseInt(env.getOrDefault("TRANSCODER_THREADS", "2")),
					Path.of(env.getOrDefault("TRANSCODER_WORK_DIR", System.getProperty("java.io.tmpdir"))),
					env.getOrDefault("TRANSCODER_FFMPEG", "ffmpeg"), env.getOrDefault("TRANSCODER_FFPROBE", "ffprobe"));
		}

	}

}
