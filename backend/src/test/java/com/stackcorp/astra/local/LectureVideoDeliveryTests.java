package com.stackcorp.astra.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestVideos;
import com.stackcorp.astra.TestcontainersConfiguration;
import com.stackcorp.astra.files.VideoRunnerDriver;
import com.stackcorp.astra.identity.session.DevLoginConfiguration;
import com.stackcorp.astra.summary.FakeLlm;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * CDN 없이 앱이 /vod를 응답하는 구성(ADR-0009, LocalRunTests와 같은 설정)에서 변환한 강의를 끝까지 재생한다. 재생 목록과
 * segment는 그 강의의 전달 cookie와 session이 함께 있어야 열리고, key는 볼 수 있는 사람에게만 나가며, 그 key와 재생 목록의 IV로
 * segment를 풀면 MPEG-TS가 나온다(ADR-0007 8번, ADR-0016).
 */
@SpringBootTest(properties = { "astra.summary.poll-interval=1h", "astra.summary.lease-renewal=1h",
		"astra.summary.lease=2h", "astra.transcoder.worker.poll-interval=1h" })
@AutoConfigureMockMvc
@ActiveProfiles({ "local", "worker" })
@Import({ TestcontainersConfiguration.class, FakeLlm.Configuration.class, DevLoginConfiguration.class, LocalSeed.class })
class LectureVideoDeliveryTests {

	private static final Pattern KEY_TAG = Pattern.compile("#EXT-X-KEY:METHOD=AES-128,URI=\"([^\"]+)\",IV=0x([0-9a-f]{32})\n#EXTINF:[0-9.]+,\n(seg_[0-9]{5}\\.ts)");

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	ApplicationContext context;

	@Test
	void playsTheTranscodedLectureOnlyWithTheDeliveryCookieSessionAndKey() throws Exception {
		VideoRunnerDriver.drain(this.context);
		long course = this.data.course();
		long professorId = this.data.user("PROFESSOR");
		long studentId = this.data.user("STUDENT");
		this.data.instructor(course, professorId);
		this.data.enroll(course, studentId);
		String professorSession = this.data.session(professorId);
		Cookie student = new Cookie("ASTRA_SESSION", this.data.session(studentId));
		long uploadId = TestVideos.upload(this.mvc, professorSession, course, "week5.mp4", TestVideos.clip(3, "mp4"));
		long itemId = ApiClient.json(new ApiClient(this.mvc, professorSession).postJson(
				"/api/courses/" + course + "/lectures",
				"{\"weekNo\":5,\"title\":\"5주차\",\"items\":[{\"title\":\"강의\",\"uploadId\":" + uploadId + "}]}"))
			.get("items")
			.get(0)
			.get("itemId")
			.asLong();
		assertThat(VideoRunnerDriver.runOnce(this.context)).isTrue();

		MockHttpServletResponse playback = new ApiClient(this.mvc, student.getValue())
			.post("/api/courses/" + course + "/lecture-items/" + itemId + "/playback")
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		JsonNode body = ApiClient.json(playback);
		String manifest = body.get("manifestUrl").asString();
		String base = manifest.substring(0, manifest.lastIndexOf('/') + 1);
		Cookie delivery = playback.getCookie("ASTRA-Delivery");
		assertThat(delivery.getPath()).isEqualTo(base);
		assertThat(delivery.isHttpOnly()).isTrue();

		String playlist = this.mvc.perform(get(manifest).cookie(student, delivery))
			.andExpect(status().isOk())
			.andExpect(content().contentType("application/vnd.apple.mpegurl"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.US_ASCII);
		Matcher first = KEY_TAG.matcher(playlist);
		assertThat(first.find()).as(playlist).isTrue();
		String keyUri = first.group(1);
		byte[] iv = HexFormat.of().parseHex(first.group(2));
		String segmentUrl = base + first.group(3);
		assertThat(keyUri).isEqualTo("/api/courses/" + course + "/lecture-items/" + itemId + "/hls-key");

		byte[] encrypted = this.mvc.perform(get(segmentUrl).cookie(student, delivery))
			.andExpect(status().isOk())
			.andExpect(content().contentType("video/mp2t"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		// 전달 cookie나 session 중 하나라도 없으면 열리지 않는다.
		this.mvc.perform(get(segmentUrl).cookie(student)).andExpect(status().isForbidden());
		this.mvc.perform(get(manifest).cookie(student)).andExpect(status().isForbidden());
		this.mvc.perform(get(segmentUrl).cookie(delivery)).andExpect(status().isUnauthorized());

		byte[] key = this.mvc.perform(get(keyUri).cookie(student))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		Cookie outsider = new Cookie("ASTRA_SESSION", this.data.session(this.data.user("STUDENT")));
		this.mvc.perform(get(keyUri).cookie(outsider)).andExpect(status().isNotFound());

		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
		byte[] ts = cipher.doFinal(encrypted);
		assertThat(ts.length % 188).isZero();
		for (int packet = 0; packet < ts.length; packet += 188) {
			assertThat(ts[packet]).as("sync byte at %d", packet).isEqualTo((byte) 0x47);
		}
		assertThat(encrypted).isNotEqualTo(ts);
	}

}
