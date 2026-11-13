package com.stackcorp.astra.integration.scan;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.stackcorp.astra.files.api.ContentScanner;

/**
 * clamd의 INSTREAM 명령으로 검사한다(ADR-0018). 설정한 host와 port 하나에만 TCP로 연결하고, process나 shell을 띄우지 않는다.
 * <p>
 * 형식: {@code zINSTREAM\0}을 보낸 뒤 [4 byte big-endian 길이][내용] chunk를 이어 보내고 길이 0으로 끝낸다. 응답은 NUL로 끝나는 한
 * 줄이다. {@code stream: OK}, {@code stream: <이름> FOUND}, {@code ... ERROR} 중 하나다. clamd는 StreamMaxLength를 넘으면
 * {@code INSTREAM size limit exceeded. ERROR}를 보내고 연결을 닫는다.
 */
class ClamdScanner implements ContentScanner, AutoCloseable {

	private static final byte[] COMMAND = "zINSTREAM\0".getBytes(StandardCharsets.US_ASCII);

	private static final int MAX_RESPONSE_BYTES = 1024;

	private static final int MAX_SIGNATURE_LENGTH = 100;

	private static final Pattern FOUND = Pattern.compile("stream: (.+) FOUND");

	private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._:/-]");

	private final ClamdProperties properties;

	private final ScheduledExecutorService watchdog = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("clamd-watchdog").factory());

	ClamdScanner(ClamdProperties properties) {
		this.properties = properties;
	}

	@Override
	public Verdict scan(InputStream content, long sizeBytes) {
		long max = this.properties.maxStreamBytes().toBytes();
		if (sizeBytes > max) {
			return new Unavailable("size_limit");
		}
		Socket socket = new Socket();
		AtomicBoolean expired = new AtomicBoolean();
		// 소켓 쓰기에는 제한 시간이 없다. clamd가 읽지 않고 멈추면 전체 제한 시간에 소켓을 닫아 풀어 준다.
		ScheduledFuture<?> deadline = this.watchdog.schedule(() -> {
			expired.set(true);
			closeQuietly(socket);
		}, this.properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
		try {
			try {
				socket.connect(new InetSocketAddress(this.properties.host(), this.properties.port()),
						(int) this.properties.connectTimeout().toMillis());
			}
			catch (IOException ex) {
				return new Unavailable(expired.get() ? "timeout" : "connect");
			}
			socket.setSoTimeout((int) this.properties.timeout().toMillis());
			InputStream in = socket.getInputStream();
			Verdict sent;
			try {
				sent = send(content, sizeBytes, max, socket);
			}
			catch (ContentReadException ex) {
				return new Unavailable("io");
			}
			catch (IOException ex) {
				if (expired.get()) {
					return new Unavailable("timeout");
				}
				// 상한을 넘기면 clamd가 이유를 쓰고 연결을 닫는다. 끝까지 보내지 못했으므로 clean으로 보지 않는다.
				Verdict early = interpret(readResponse(in));
				return early instanceof Clean ? new Unavailable("io") : early;
			}
			if (sent != null) {
				return sent;
			}
			return interpret(readResponse(in));
		}
		catch (SocketTimeoutException ex) {
			return new Unavailable("timeout");
		}
		catch (IOException ex) {
			return new Unavailable(expired.get() ? "timeout" : "io");
		}
		finally {
			deadline.cancel(false);
			closeQuietly(socket);
		}
	}

	@Override
	public void close() {
		this.watchdog.shutdownNow();
	}

	/** @return 보내기 전에 끝낸 판정. 끝까지 보냈으면 null */
	private Verdict send(InputStream content, long sizeBytes, long max, Socket socket) throws IOException {
		int chunk = (int) this.properties.chunkSize().toBytes();
		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), chunk + 4));
		out.write(COMMAND);
		byte[] buffer = new byte[chunk];
		long total = 0;
		for (int read; (read = readChunk(content, buffer)) > 0;) {
			total += read;
			if (total > max) {
				return new Unavailable("size_limit");
			}
			out.writeInt(read);
			out.write(buffer, 0, read);
		}
		if (total != sizeBytes) {
			// 기록한 크기와 다른 객체를 검사 결과로 확정하지 않는다.
			return new Unavailable("io");
		}
		out.writeInt(0);
		out.flush();
		return null;
	}

	private static int readChunk(InputStream content, byte[] buffer) {
		try {
			return content.readNBytes(buffer, 0, buffer.length);
		}
		catch (IOException ex) {
			throw new ContentReadException(ex);
		}
	}

	/** NUL이나 연결 종료까지 읽는다. 상한을 넘는 응답은 버린다. */
	private static String readResponse(InputStream in) throws IOException {
		ByteArrayOutputStream response = new ByteArrayOutputStream();
		for (int b; (b = in.read()) != -1 && b != 0;) {
			if (response.size() >= MAX_RESPONSE_BYTES) {
				return "";
			}
			response.write(b);
		}
		return response.toString(StandardCharsets.UTF_8).strip();
	}

	private static Verdict interpret(String response) {
		if ("stream: OK".equals(response)) {
			return new Clean();
		}
		Matcher found = FOUND.matcher(response);
		if (found.matches()) {
			return new Infected(signature(found.group(1)));
		}
		if (response.contains("size limit exceeded")) {
			return new Unavailable("size_limit");
		}
		if (response.endsWith("ERROR")) {
			return new Unavailable("error");
		}
		return new Unavailable("protocol");
	}

	private static String signature(String reported) {
		String safe = UNSAFE.matcher(reported.strip()).replaceAll("_");
		if (safe.isEmpty()) {
			return "unknown";
		}
		return safe.length() <= MAX_SIGNATURE_LENGTH ? safe : safe.substring(0, MAX_SIGNATURE_LENGTH);
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		}
		catch (IOException ex) {
			// 닫는 중의 오류는 판정에 영향이 없다.
		}
	}

	/** 검사할 내용을 읽지 못했다. 소켓 오류와 구분한다. */
	private static final class ContentReadException extends RuntimeException {

		ContentReadException(IOException cause) {
			super(cause);
		}

	}

}
