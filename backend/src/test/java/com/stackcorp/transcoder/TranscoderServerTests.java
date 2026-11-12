package com.stackcorp.transcoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.stackcorp.astra.TestVideos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * transcoder process는 입력을 신뢰하지 않는다. 컨테이너를 서버가 정한 형식으로 고정해 다른 파일이나 주소를 참조하는 입력을 열지 않고,
 * 크기 상한은 본문을 받기 전에 판정하고, 작업 파일을 남기지 않는다(ADR-0016, SC-08).
 */
class TranscoderServerTests {

	@TempDir
	Path workRoot;

	TranscoderServer server;

	HttpClient http = HttpClient.newHttpClient();

	@BeforeEach
	void start() throws IOException {
		this.server = TranscoderServer.start(new InetSocketAddress("127.0.0.1", 0), new TranscoderServer.Limits(
				8L << 20, 64L << 20, Duration.ofMinutes(2), 60, 1, this.workRoot, "ffmpeg", "ffprobe"));
	}

	@AfterEach
	void stop() {
		this.server.close();
		this.http.close();
	}

	@Test
	void segmentsAVideoIntoAPlaylistAndOrderedSegmentsAndKeepsNoFiles() throws Exception {
		HttpResponse<byte[]> response = post("mp4", TestVideos.clip(2, "mp4"));

		assertThat(response.statusCode()).isEqualTo(200);
		List<String> names = new ArrayList<>();
		String playlist = null;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
				names.add(entry.getName());
				byte[] content = zip.readAllBytes();
				if (entry.getName().equals("index.m3u8")) {
					playlist = new String(content, StandardCharsets.US_ASCII);
				}
				else {
					// 암호화하지 않은 MPEG-TS다. key는 이 process에 오지 않는다.
					assertThat(content[0]).isEqualTo((byte) 0x47);
				}
			}
		}
		assertThat(names).first().isEqualTo("index.m3u8");
		assertThat(names).contains("seg_00000.ts");
		assertThat(playlist).contains("#EXTINF:").doesNotContain("EXT-X-KEY");
		assertThat(workFiles()).isEmpty();
	}

	@Test
	void refusesInputsThatReferenceOtherFilesOrAreNotOfTheDeclaredContainer() throws Exception {
		Path secret = Files.writeString(this.workRoot.resolve("secret.txt"), "secret");
		byte[] playlist = ("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nfile://" + secret + "\n#EXT-X-ENDLIST\n")
			.getBytes(StandardCharsets.US_ASCII);
		byte[] concat = ("ffconcat version 1.0\nfile '" + secret + "'\n").getBytes(StandardCharsets.US_ASCII);
		byte[] mkv = TestVideos.clip(1, "mkv");

		for (var attempt : List.of(Map.entry("mp4", playlist), Map.entry("mkv", concat), Map.entry("mp4", mkv))) {
			HttpResponse<byte[]> response = post(attempt.getKey(), attempt.getValue());
			assertThat(response.statusCode()).isEqualTo(422);
			assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("\"code\":\"invalid_media\"");
		}
		assertThat(workFiles()).containsExactly(secret);
	}

	@Test
	void refusesOversizedBodiesAndUnknownContainers() throws Exception {
		try (TranscoderServer small = TranscoderServer.start(new InetSocketAddress("127.0.0.1", 0),
				new TranscoderServer.Limits(1000, 64L << 20, Duration.ofMinutes(1), 60, 1, this.workRoot, "ffmpeg",
						"ffprobe"))) {
			HttpResponse<byte[]> tooLarge = this.http.send(
					HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + small.port() + "/transcode?container=mp4"))
						.POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1001]))
						.build(),
					HttpResponse.BodyHandlers.ofByteArray());
			assertThat(tooLarge.statusCode()).isEqualTo(413);
		}
		assertThat(post("avi", new byte[] { 1 }).statusCode()).isEqualTo(400);
		assertThat(post("mp4&container=mkv", new byte[] { 1 }).statusCode()).isEqualTo(400);
		assertThat(workFiles()).isEmpty();
	}

	@Test
	void removesJobDirectoriesLeftByAnInterruptedRunOnStartup() throws Exception {
		this.server.close();
		Path stale = Files.createDirectory(this.workRoot.resolve("transcode-123"));
		Files.write(stale.resolve("input"), new byte[] { 1, 2, 3 });
		Path unrelated = Files.createDirectory(this.workRoot.resolve("other"));
		start();
		assertThat(stale).doesNotExist();
		assertThat(unrelated).exists();
	}

	@Test
	void startsAndRemovesOtherLeftoversWhenALeftoverCannotBeFullyDeleted() throws Exception {
		// 남은 작업 directory 안에 열 수 없는 하위 directory가 있으면 Files.walk가 UncheckedIOException을 던진다.
		Path stuck = Files.createDirectories(this.workRoot.resolve("transcode-1").resolve("out"));
		Path sibling = Files.createDirectory(this.workRoot.resolve("transcode-2"));
		Files.write(sibling.resolve("input"), new byte[] { 1, 2, 3 });
		try (Closeable restore = denyListing(stuck)) {
			assumeTrue(listingFails(stuck), "권한을 빼도 directory를 열 수 있는 사용자다(예: root). 지우기 실패를 만들 수 없다");
			this.server.close();
			start();
		}
		assertThat(stuck).exists();
		assertThat(sibling).doesNotExist();
	}

	private HttpResponse<byte[]> post(String container, byte[] body) throws Exception {
		return this.http.send(HttpRequest.newBuilder(uri(container)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
				HttpResponse.BodyHandlers.ofByteArray());
	}

	private URI uri(String container) {
		return URI.create("http://127.0.0.1:" + this.server.port() + "/transcode?container=" + container);
	}

	private List<Path> workFiles() throws IOException {
		try (Stream<Path> files = Files.list(this.workRoot)) {
			return files.toList();
		}
	}

	/** 현재 사용자가 dir 목록을 읽지 못하게 한다. 닫으면 권한을 되돌린다. POSIX와 Windows(ACL) 둘 다 쓴다. */
	private static Closeable denyListing(Path dir) throws IOException {
		PosixFileAttributeView posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
		if (posix != null) {
			Set<PosixFilePermission> before = posix.readAttributes().permissions();
			posix.setPermissions(Set.of());
			return () -> posix.setPermissions(before);
		}
		AclFileAttributeView acl = Files.getFileAttributeView(dir, AclFileAttributeView.class);
		assumeTrue(acl != null, "POSIX 권한도 ACL도 없는 file system이다");
		List<AclEntry> before = acl.getAcl();
		UserPrincipal user = dir.getFileSystem()
			.getUserPrincipalLookupService()
			.lookupPrincipalByName(System.getProperty("user.name"));
		List<AclEntry> denied = new ArrayList<>();
		denied.add(AclEntry.newBuilder()
			.setType(AclEntryType.DENY)
			.setPrincipal(user)
			.setPermissions(AclEntryPermission.LIST_DIRECTORY)
			.build());
		denied.addAll(before);
		acl.setAcl(denied);
		return () -> acl.setAcl(before);
	}

	private static boolean listingFails(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return false;
		}
		catch (IOException ex) {
			return true;
		}
	}

}
