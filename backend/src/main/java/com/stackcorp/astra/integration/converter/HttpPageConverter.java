package com.stackcorp.astra.integration.converter;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.files.api.ConversionException.Code;
import com.stackcorp.astra.files.api.PageConverter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * converter process(com.stackcorp.converter.ConverterServer)를 HTTP로 부른다. 응답 zip은 크기와 순서를 검사하며 읽고, 쪽이
 * 완성될 때마다 넘긴다. 제한 시간이 지나면 연결을 끊는다.
 */
final class HttpPageConverter implements PageConverter, AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern PAGE_ENTRY = Pattern.compile("pages/(\\d{3})\\.(json|display\\.jpg|model\\.jpg)");

	private static final int ERROR_BODY_BYTES = 4000;

	private final ConverterProperties properties;

	private final URI endpoint;

	private final HttpClient http;

	private final ScheduledExecutorService watchdog;

	HttpPageConverter(ConverterProperties properties) {
		this.properties = properties;
		String base = properties.url().toString();
		this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/render");
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(5))
			.build();
		this.watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
	}

	@Override
	public ConvertedDocument convert(byte[] pdf, int maxPages, Consumer<ConvertedPage> pages) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(this.endpoint + "?maxPages=" + maxPages))
			.header("content-type", "application/pdf")
			.timeout(this.properties.timeout())
			.POST(HttpRequest.BodyPublishers.ofByteArray(pdf))
			.build();
		HttpResponse<InputStream> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (ConnectException ex) {
			throw new ConversionException(Code.UNAVAILABLE, "converter에 연결하지 못했습니다.", ex);
		}
		catch (HttpTimeoutException ex) {
			throw new ConversionException(Code.FAILED, "converter가 제한 시간 안에 응답하지 않았습니다.", ex);
		}
		catch (IOException ex) {
			throw new ConversionException(Code.UNAVAILABLE, "converter 호출이 실패했습니다.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ConversionException(Code.FAILED, "변환을 기다리다 중단되었습니다.", ex);
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
				return readZip(body, maxPages, pages);
			}
			catch (IOException ex) {
				if (expired.get()) {
					throw new ConversionException(Code.FAILED, "변환이 제한 시간을 넘었습니다.", ex);
				}
				throw new ConversionException(Code.FAILED, "converter 응답을 읽지 못했습니다.", ex);
			}
			finally {
				deadline.cancel(false);
			}
		}
		catch (IOException ex) {
			throw new ConversionException(Code.FAILED, "converter 응답을 닫지 못했습니다.", ex);
		}
	}

	@Override
	public void close() {
		this.watchdog.shutdownNow();
		this.http.close();
	}

	private ConvertedDocument readZip(InputStream body, int maxPages, Consumer<ConvertedPage> pages)
			throws IOException {
		long maxTotal = this.properties.maxResponseBytes().toBytes();
		long maxEntry = this.properties.maxEntryBytes().toBytes();
		long total = 0;
		int expected = 1;
		JsonNode meta = null;
		byte[] display = null;
		try (ZipInputStream zip = new ZipInputStream(body)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				byte[] content = zip.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxEntry + 1));
				if (content.length > maxEntry) {
					throw invalid("zip 항목이 크기 상한을 넘었습니다.");
				}
				total += content.length;
				if (total > maxTotal) {
					throw invalid("응답이 크기 상한을 넘었습니다.");
				}
				String name = entry.getName();
				if (name.equals("error.json")) {
					throw new ConversionException(Code.FAILED, "converter가 쪽을 변환하지 못했습니다.");
				}
				if (name.equals("document.json")) {
					JsonNode doc = json(content);
					int count = doc.path("pageCount").asInt(-1);
					if (meta != null || display != null || count != expected - 1) {
						throw invalid("쪽 수가 맞지 않습니다.");
					}
					JsonNode title = doc.path("title");
					return new ConvertedDocument(count, title.isString() ? title.stringValue() : null);
				}
				Matcher m = PAGE_ENTRY.matcher(name);
				if (!m.matches() || Integer.parseInt(m.group(1)) != expected || expected > maxPages) {
					throw invalid("예상하지 않은 zip 항목입니다: " + name);
				}
				switch (m.group(2)) {
					case "json" -> {
						if (meta != null) {
							throw invalid("쪽 정보가 두 번 나왔습니다.");
						}
						meta = json(content);
					}
					case "display.jpg" -> {
						if (meta == null || display != null) {
							throw invalid("쪽 항목 순서가 맞지 않습니다.");
						}
						display = content;
					}
					default -> {
						if (meta == null || display == null) {
							throw invalid("쪽 항목 순서가 맞지 않습니다.");
						}
						JsonNode text = meta.path("text");
						pages.accept(new ConvertedPage(expected, text.isString() ? text.stringValue() : "",
								meta.path("textTruncated").asBoolean(false), display, content));
						expected++;
						meta = null;
						display = null;
					}
				}
			}
		}
		throw invalid("응답에 document.json이 없습니다.");
	}

	private ConversionException failure(int status, InputStream body) throws IOException {
		byte[] bytes = body.readNBytes(ERROR_BODY_BYTES);
		String code = "";
		String message = "converter가 요청을 거부했습니다 (HTTP " + status + ")";
		try {
			JsonNode node = JSON.readTree(bytes);
			code = node.path("code").asString("");
			if (node.path("message").isString()) {
				message = node.path("message").stringValue();
			}
		}
		catch (JacksonException ex) {
			// 본문이 JSON이 아니면 상태 코드만 쓴다.
		}
		Code mapped = switch (code) {
			case "encrypted" -> Code.ENCRYPTED;
			case "invalid_pdf" -> Code.INVALID_PDF;
			case "too_many_pages" -> Code.TOO_MANY_PAGES;
			case "empty" -> Code.EMPTY;
			case "too_large" -> Code.TOO_LARGE;
			default -> status >= 500 ? Code.UNAVAILABLE : Code.FAILED;
		};
		return new ConversionException(mapped, message);
	}

	private static JsonNode json(byte[] content) {
		try {
			return JSON.readTree(content);
		}
		catch (JacksonException ex) {
			throw invalid("zip 안 JSON을 읽지 못했습니다.");
		}
	}

	private static ConversionException invalid(String message) {
		return new ConversionException(Code.FAILED, message);
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
