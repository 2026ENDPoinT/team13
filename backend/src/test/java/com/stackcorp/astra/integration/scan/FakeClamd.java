package com.stackcorp.astra.integration.scan;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 테스트용 clamd. loopback에서 INSTREAM 명령만 받는다. 판정은 테스트가 정한다. 기본 판정은 EICAR 문자열이 있으면 FOUND, 없으면 OK다.
 * 느린 응답, 명령만 읽고 끊기, StreamMaxLength 초과 응답을 흉내 낸다.
 */
public final class FakeClamd implements AutoCloseable {

	/** EICAR 시험 문자열. 실제 악성코드가 아니며 모든 검사기가 탐지하도록 약속된 값이다. */
	public static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

	public static final String EICAR_SIGNATURE = "Win.Test.EICAR_HDB-1";

	public enum Mode {

		/** 판정을 바로 보낸다. */
		NORMAL,
		/** 명령만 읽고 연결을 닫는다. */
		CLOSE_EARLY,
		/** 내용을 다 받은 뒤 정한 시간만큼 기다렸다가 판정을 보낸다. */
		SLOW

	}

	private final ServerSocket server;

	private final List<Session> sessions = new CopyOnWriteArrayList<>();

	private final AtomicInteger connections = new AtomicInteger();

	private volatile Mode mode;

	private volatile Duration delay;

	private volatile long streamMaxLength;

	private volatile Function<byte[], String> verdicts;

	private FakeClamd(ServerSocket server) {
		this.server = server;
		reset();
	}

	public static FakeClamd start() {
		try {
			FakeClamd clamd = new FakeClamd(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
			Thread.ofPlatform().daemon().name("fake-clamd").start(clamd::accept);
			return clamd;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public int port() {
		return this.server.getLocalPort();
	}

	/** 기본 동작으로 돌린다. 기록은 지우지 않는다. */
	public void reset() {
		this.mode = Mode.NORMAL;
		this.delay = Duration.ZERO;
		this.streamMaxLength = Long.MAX_VALUE;
		this.verdicts = FakeClamd::defaultVerdict;
	}

	public void mode(Mode mode) {
		this.mode = mode;
	}

	public void slow(Duration delay) {
		this.delay = delay;
		this.mode = Mode.SLOW;
	}

	public void streamMaxLength(long bytes) {
		this.streamMaxLength = bytes;
	}

	/** @param verdicts 받은 내용으로 NUL 앞의 응답 줄을 정한다 */
	public void respond(Function<byte[], String> verdicts) {
		this.verdicts = verdicts;
	}

	public List<Session> sessions() {
		return List.copyOf(this.sessions);
	}

	public int connections() {
		return this.connections.get();
	}

	/** @return 이 내용을 끝까지 받아 판정한 횟수 */
	public long scansOf(byte[] content) {
		return this.sessions.stream().filter((s) -> s.terminated() && Arrays.equals(s.payload(), content)).count();
	}

	@Override
	public void close() {
		try {
			this.server.close();
		}
		catch (IOException ex) {
			// 테스트 종료 중이다.
		}
	}

	private void accept() {
		while (!this.server.isClosed()) {
			try {
				Socket socket = this.server.accept();
				this.connections.incrementAndGet();
				Thread.ofVirtual().start(() -> handle(socket));
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	private void handle(Socket socket) {
		try (socket) {
			socket.setSoTimeout(30_000);
			InputStream in = new BufferedInputStream(socket.getInputStream());
			OutputStream out = socket.getOutputStream();
			String command = readCommand(in);
			if (this.mode == Mode.CLOSE_EARLY) {
				this.sessions.add(new Session(command, List.of(), false, new byte[0]));
				return;
			}
			DataInputStream data = new DataInputStream(in);
			ByteArrayOutputStream payload = new ByteArrayOutputStream();
			List<Integer> chunks = new ArrayList<>();
			for (int length; (length = data.readInt()) != 0;) {
				chunks.add(length);
				payload.write(data.readNBytes(length));
				if (payload.size() > this.streamMaxLength) {
					// clamd처럼 이유를 보내고 더 받지 않는다. client가 남은 chunk를 보내다 막히지 않게 끝까지 읽어 버린다.
					this.sessions.add(new Session(command, chunks, false, payload.toByteArray()));
					reply(out, "INSTREAM size limit exceeded. ERROR");
					socket.shutdownOutput();
					in.transferTo(OutputStream.nullOutputStream());
					return;
				}
			}
			byte[] content = payload.toByteArray();
			this.sessions.add(new Session(command, chunks, true, content));
			if (this.mode == Mode.SLOW) {
				Thread.sleep(this.delay);
			}
			reply(out, this.verdicts.apply(content));
		}
		catch (IOException ex) {
			// client가 먼저 끊었다.
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static String readCommand(InputStream in) throws IOException {
		ByteArrayOutputStream command = new ByteArrayOutputStream();
		for (int b; (b = in.read()) != -1 && b != 0;) {
			command.write(b);
		}
		return command.toString(StandardCharsets.US_ASCII);
	}

	private static void reply(OutputStream out, String line) throws IOException {
		out.write((line + "\0").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static String defaultVerdict(byte[] content) {
		return new String(content, StandardCharsets.ISO_8859_1).contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")
				? "stream: " + EICAR_SIGNATURE + " FOUND" : "stream: OK";
	}

	/**
	 * @param command NUL 앞까지 받은 명령
	 * @param chunkSizes 받은 chunk 길이. 끝 표시(0)는 넣지 않는다
	 * @param terminated 길이 0 chunk까지 받았는지
	 */
	public record Session(String command, List<Integer> chunkSizes, boolean terminated, byte[] payload) {
	}

}
