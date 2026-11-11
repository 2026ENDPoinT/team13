package com.stackcorp.astra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 테스트용 짧은 영상. 저장소에 바이너리를 두지 않고 ffmpeg의 lavfi 입력(색 막대와 사인파)으로 만든다. ffmpeg가 PATH에 있어야 한다.
 */
public final class TestVideos {

	private TestVideos() {
	}

	/** @param format H.264/AAC를 담는 컨테이너: mp4, mov, mkv */
	public static byte[] clip(int seconds, String format) {
		try {
			Path dir = Files.createTempDirectory("test-video-");
			Path out = dir.resolve("clip." + format);
			try {
				Process process = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-f",
						"lavfi", "-i", "testsrc=duration=" + seconds + ":size=320x240:rate=25", "-f", "lavfi", "-i",
						"sine=frequency=440:duration=" + seconds, "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt",
						"yuv420p", "-c:a", "aac", "-shortest", out.toString())
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.start();
				if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
					process.destroyForcibly();
					throw new IllegalStateException("ffmpeg로 시험 영상을 만들지 못했습니다.");
				}
				return Files.readAllBytes(out);
			}
			finally {
				Files.deleteIfExists(out);
				Files.deleteIfExists(dir);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/** ftyp box로 시작해 형식 검사는 통과하지만 영상으로 읽을 수 없는 내용. */
	public static byte[] brokenMp4() {
		byte[] head = { 0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'i', 's',
				'o', '2', 'a', 'v', 'c', '1', 'm', 'p', '4', '1' };
		byte[] content = new byte[4096];
		System.arraycopy(head, 0, content, 0, head.length);
		for (int i = head.length; i < content.length; i++) {
			content[i] = (byte) (i * 31);
		}
		return content;
	}

	/**
	 * 본문 스트림 업로드(POST /api/uploads/stream)로 lecture_video를 올리고 uploadId를 돌려준다.
	 *
	 * @param sessionToken 올리는 사람의 session 쿠키 값
	 */
	public static long upload(MockMvc mvc, String sessionToken, long courseId, String name, byte[] body)
			throws Exception {
		Cookie csrf = mvc.perform(MockMvcRequestBuilders.get("/api/auth/csrf"))
			.andReturn()
			.getResponse()
			.getCookie("XSRF-TOKEN");
		MockHttpServletResponse response = mvc
			.perform(MockMvcRequestBuilders.post("/api/uploads/stream")
				.queryParam("courseId", Long.toString(courseId))
				.queryParam("purpose", "lecture_video")
				.queryParam("name", name)
				.contentType(MediaType.APPLICATION_OCTET_STREAM)
				.header("Content-Length", Integer.toString(body.length))
				.content(body)
				.cookie(csrf, new Cookie("ASTRA_SESSION", sessionToken))
				.header("X-XSRF-TOKEN", csrf.getValue()))
			.andReturn()
			.getResponse();
		if (response.getStatus() != 201) {
			throw new IllegalStateException("영상 업로드가 실패했습니다: HTTP " + response.getStatus());
		}
		return ApiClient.json(response).get("uploadId").asLong();
	}

}
