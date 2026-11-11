package com.stackcorp.astra.integration.transcoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.stackcorp.astra.files.api.TranscodeException;
import com.stackcorp.astra.files.api.TranscodeException.Code;
import com.stackcorp.astra.files.api.VideoTranscoder;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * transcoder process(com.stackcorp.transcoder.TranscoderServer)를 HTTP로 부른다(ADR-0016). 원본은 stream으로 보내고, 응답
 * zip은 크기, 이름, 순서를 검사하며 읽어 segment마다 넘긴다. 재생 목록은 segment 길이만 읽고 그대로 쓰지 않는다. 제한 시간이 지나면
 * 연결을 끊는다. redirect는 따르지 않는다.
 */
final class HttpVideoTranscoder implements VideoTranscoder, AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Set<String> CONTAINERS = Set.of("mp4", "mov", "webm", "mkv");

	private static final String PLAYLIST = "index.m3u8";

	private static final int ERROR_BODY_BYTES = 4000;

	private final TranscoderProperties properties;

	private final URI endpoint;

	private final HttpClient http;

	private final ScheduledExecutorService watchdog;

	HttpVideoTranscoder(TranscoderProperties properties) {
		this.properties = properties;
		String base = properties.url().toString();
		this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/transcode");
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		this.watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
	}

	@Override
	public Transcoded transcode(VideoSource source, SegmentSink sink, BooleanSupplier cancelled) {
		if (!CONTAINERS.contains(source.container())) {
			throw new TranscodeException(Code.INVALID_MEDIA, "지원하지 않는 컨테이너입니다.");
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(this.endpoint + "?container=" + source.container()))
			.header("content-type", "application/octet-stream")
			.timeout(this.properties.timeout())
			.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
				try {
					return source.opener().open();
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}), source.sizeBytes()))
			.build();
		HttpResponse<InputStream> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (ConnectException ex) {
			throw new TranscodeException(Code.UNAVAILABLE, "transcoder에 연결하지 못했습니다.", ex);
		}
		catch (HttpTimeoutException ex) {
			throw new TranscodeException(Code.FAILED, "transcoder가 제한 시간 안에 응답하지 않았습니다.", ex);
		}
		catch (IOException | UncheckedIOException ex) {
			throw new TranscodeException(Code.UNAVAILABLE, "transcoder 호출이 실패했습니다.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new TranscodeException(Code.FAILED, "변환을 기다리다 중단되었습니다.", ex);
		}
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw failure(response.statusCode(), body);
			}
			AtomicBoolean expired = new AtomicBoolean();
			ScheduledFuture<?> deadline = this.watchdog.schedule(() -> {
				expired.set(true);
				closeQuietly(body);
			}, this.properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
			try {
				return readZip(body, sink, cancelled);
			}
			catch (IOException ex) {
				throw new TranscodeException(Code.FAILED,
						expired.get() ? "변환이 제한 시간을 넘었습니다." : "transcoder 응답을 읽지 못했습니다.", ex);
			}
			finally {
				deadline.cancel(false);
			}
		}
		catch (IOException ex) {
			throw new TranscodeException(Code.FAILED, "transcoder 응답을 닫지 못했습니다.", ex);
		}
	}

	@Override
	public void close() {
		this.watchdog.shutdownNow();
		this.http.close();
	}

	private Transcoded readZip(InputStream body, SegmentSink sink, BooleanSupplier cancelled) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(body)) {
			ZipEntry entry = zip.getNextEntry();
			if (entry == null || !PLAYLIST.equals(entry.getName())) {
				throw invalid("응답이 재생 목록으로 시작하지 않습니다.");
			}
			List<Double> durations = parsePlaylist(read(zip, this.properties.maxPlaylistBytes().toBytes()));
			double total = 0;
			for (int index = 0; index < durations.size(); index++) {
				if (cancelled.getAsBoolean()) {
					throw new TranscodeException(Code.CANCELLED, "변환이 취소되었습니다.");
				}
				entry = zip.getNextEntry();
				if (entry == null || !segmentName(index).equals(entry.getName())) {
					throw invalid("segment 순서가 재생 목록과 맞지 않습니다.");
				}
				byte[] segment = read(zip, this.properties.maxSegmentBytes().toBytes());
				if (segment.length == 0) {
					throw invalid("빈 segment입니다.");
				}
				sink.accept(index, durations.get(index), segment);
				total += durations.get(index);
			}
			if (zip.getNextEntry() != null) {
				throw invalid("재생 목록에 없는 항목이 있습니다.");
			}
			return new Transcoded(durations.size(), total);
		}
	}

	/** 재생 목록에서 segment 길이만 읽는다. segment 이름은 0부터 이어지는 seg_NNNNN.ts여야 한다. */
	private List<Double> parsePlaylist(byte[] content) {
		List<Double> durations = new ArrayList<>();
		String[] lines = new String(content, StandardCharsets.US_ASCII).split("\r?\n");
		if (lines.length == 0 || !"#EXTM3U".equals(lines[0].strip())) {
			throw invalid("재생 목록 형식이 아닙니다.");
		}
		Double pending = null;
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith("#EXTINF:")) {
				int comma = line.indexOf(',');
				String value = line.substring("#EXTINF:".length(), comma < 0 ? line.length() : comma);
				try {
					pending = Double.parseDouble(value);
				}
				catch (NumberFormatException ex) {
					throw invalid("segment 길이를 읽지 못했습니다.");
				}
				if (!(pending > 0 && pending <= 60)) {
					throw invalid("segment 길이가 범위를 벗어납니다.");
				}
			}
			else if (!line.startsWith("#")) {
				if (pending == null || !segmentName(durations.size()).equals(line)) {
					throw invalid("재생 목록의 segment 이름이 올바르지 않습니다.");
				}
				durations.add(pending);
				pending = null;
				if (durations.size() > this.properties.maxSegments()) {
					throw invalid("segment 수가 상한을 넘었습니다.");
				}
			}
		}
		if (durations.isEmpty()) {
			throw invalid("재생 목록에 segment가 없습니다.");
		}
		return durations;
	}

	private static String segmentName(int index) {
		return "seg_%05d.ts".formatted(index);
	}

	private static byte[] read(ZipInputStream zip, long max) throws IOException {
		byte[] content = zip.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
		if (content.length > max) {
			throw invalid("응답 항목이 크기 상한을 넘었습니다.");
		}
		return content;
	}

	private TranscodeException failure(int status, InputStream body) throws IOException {
		byte[] bytes = body.readNBytes(ERROR_BODY_BYTES);
		String code = "";
		try {
			code = JSON.readTree(bytes).path("code").asString("");
		}
		catch (JacksonException ex) {
			// 본문이 JSON이 아니면 상태 코드만 쓴다.
		}
		Code mapped = switch (code) {
			case "invalid_media" -> Code.INVALID_MEDIA;
			case "too_large" -> Code.TOO_LARGE;
			case "too_long" -> Code.TOO_LONG;
			case "timeout" -> Code.TIMEOUT;
			default -> status >= 500 ? Code.UNAVAILABLE : Code.FAILED;
		};
		return new TranscodeException(mapped, "transcoder가 변환하지 못했습니다 (HTTP " + status + ", " + mapped.value() + ")");
	}

	private static TranscodeException invalid(String message) {
		return new TranscodeException(Code.FAILED, message);
	}

	private static void closeQuietly(InputStream in) {
		try {
			in.close();
		}
		catch (IOException ex) {
			// 제한 시간으로 끊는 중이다.
		}
	}

}
