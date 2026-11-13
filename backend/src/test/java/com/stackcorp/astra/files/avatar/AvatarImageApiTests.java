package com.stackcorp.astra.files.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.catalog.api.CourseMates;
import com.stackcorp.astra.files.api.ContentScanner;
import com.stackcorp.astra.files.api.ImageNormalizer;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.identity.api.ProfileImages;

import jakarta.servlet.http.Cookie;

import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * 아바타 사진(ADR-0038). 올린 사진은 worker가 검사하고 격리한 converter가 256px JPEG로 다시 만든 뒤에만 보인다. 원본은 내보내지
 * 않는다. 보는 사람은 본인과 폐강하지 않은 과목을 함께하는 사람뿐이다. 테스트는 worker 처리를 직접 부른다.
 */
class AvatarImageApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	@Autowired
	AvatarImages avatars;

	@Autowired
	ProfileImages profileImages;

	@Autowired
	ObjectStore store;

	@Autowired
	CourseMates mates;

	@Autowired
	ImageNormalizer normalizer;

	long course;

	long me;

	ApiClient owner;

	ApiClient classmate;

	ApiClient instructor;

	ApiClient outsider;

	@BeforeEach
	void users() {
		this.course = this.data.course();
		this.me = this.data.user("STUDENT");
		long mate = this.data.user("STUDENT");
		long prof = this.data.user("PROFESSOR");
		this.data.enroll(this.course, this.me);
		this.data.enroll(this.course, mate);
		this.data.instructor(this.course, prof);
		long otherCourse = this.data.course();
		long out = this.data.user("STUDENT");
		this.data.enroll(otherCourse, out);
		this.owner = new ApiClient(this.mvc, this.data.session(this.me));
		this.classmate = new ApiClient(this.mvc, this.data.session(mate));
		this.instructor = new ApiClient(this.mvc, this.data.session(prof));
		this.outsider = new ApiClient(this.mvc, this.data.session(out));
		drain();
	}

	@Test
	void uploadedPhotoIsReencodedAndServedOnlyAfterProcessing() throws Exception {
		byte[] original = png(600, 400, Color.RED);
		upload(this.owner, original).andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("processing"));
		this.owner.get("/api/me/profile")
			.andExpect(jsonPath("$.avatar.type").value("color"))
			.andExpect(jsonPath("$.avatar.photo.state").value("processing"))
			.andExpect(jsonPath("$.avatar.photo.url").doesNotExist());
		// 첫 사진이 처리 중이면 볼 수 있는 사람의 404에만 Retry-After가 붙는다.
		this.owner.get(avatar(this.me))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("not_found"))
			.andExpect(header().string("Retry-After", "5"));
		this.classmate.get(avatar(this.me)).andExpect(status().isNotFound()).andExpect(header().string("Retry-After", "5"));
		this.outsider.get(avatar(this.me)).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Retry-After"));

		drain();

		JsonNode profile = ApiClient.json(this.owner.get("/api/me/profile")
			.andExpect(jsonPath("$.avatar.type").value("image"))
			.andExpect(jsonPath("$.avatar.photo.state").value("ready"))
			.andExpect(jsonPath("$.avatar.photo.url").value(avatar(this.me))));
		var response = this.owner.get(avatar(this.me))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
			.andExpect(header().string("Cache-Control", "no-cache, private"))
			.andReturn()
			.getResponse();
		byte[] served = response.getContentAsByteArray();
		assertThat(served).isNotEqualTo(original);
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(served));
		assertThat(image.getWidth()).isEqualTo(ImageNormalizer.AVATAR_SIZE);
		assertThat(image.getHeight()).isEqualTo(ImageNormalizer.AVATAR_SIZE);
		String etag = response.getHeader("ETag");
		assertThat(etag).startsWith("\"");
		this.owner.send(MockMvcRequestBuilders.get(avatar(this.me)).header("If-None-Match", etag))
			.andExpect(status().isNotModified())
			.andExpect(header().string("ETag", etag));
		// 올린 원본은 처리 뒤에 지운다.
		assertThat(objects("avatar-sources/")).noneMatch((key) -> key.contains(sourceKeySuffix()));
		assertThat(profile.get("avatar").get("version").asInt()).isGreaterThan(0);
	}

	@Test
	void onlyPeopleSharingACourseSeeThePhoto() throws Exception {
		upload(this.owner, png(300, 300, Color.BLUE)).andExpect(status().isAccepted());
		drain();

		this.owner.get(avatar(this.me)).andExpect(status().isOk());
		this.classmate.get(avatar(this.me)).andExpect(status().isOk());
		this.instructor.get(avatar(this.me)).andExpect(status().isOk());
		this.outsider.get(avatar(this.me)).andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(avatar(this.me)).andExpect(status().isUnauthorized());
		// 사진이 없는 사람과 볼 수 없는 사람은 같은 404다.
		this.owner.get(avatar(this.data.user("STUDENT"))).andExpect(status().isNotFound());
		// 폐강한 과목에는 구성원이 없다.
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", this.course).update();
		this.classmate.get(avatar(this.me)).andExpect(status().isNotFound());
		this.owner.get(avatar(this.me)).andExpect(status().isOk());
	}

	@Test
	void rejectsWhatIsNotAPngOrJpegBeforeStoringIt() throws Exception {
		upload(this.owner, "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("not_image"));
		byte[] huge = new byte[(5 << 20) + 1];
		System.arraycopy(png(2, 2, Color.RED), 0, huge, 0, 8);
		upload(this.owner, huge).andExpect(status().is(413)).andExpect(jsonPath("$.code").value("too_large"));
		this.mvc.perform(MockMvcRequestBuilders.multipart("/api/me/profile/image")
			.file(new MockMultipartFile("file", "a.png", "image/png", png(10, 10, Color.RED)))
			.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.me)))).andExpect(status().isForbidden());
		new ApiClient(this.mvc, null).send(MockMvcRequestBuilders.multipart("/api/me/profile/image")
			.file(new MockMultipartFile("file", "a.png", "image/png", png(10, 10, Color.RED))))
			.andExpect(status().isUnauthorized());
		assertThat(rows(this.me)).isZero();
	}

	@Test
	void converterFailuresEndInADocumentedCode() throws Exception {
		// signature는 PNG지만 그림이 아니다.
		byte[] fake = png(4, 4, Color.RED);
		byte[] broken = new byte[64];
		System.arraycopy(fake, 0, broken, 0, 8);
		upload(this.owner, broken).andExpect(status().isAccepted());
		drain();
		this.owner.get("/api/me/profile")
			.andExpect(jsonPath("$.avatar.type").value("color"))
			.andExpect(jsonPath("$.avatar.photo.state").value("failed"))
			.andExpect(jsonPath("$.avatar.photo.errorCode").value("not_image"));

		upload(this.owner, png(5000, 8, Color.GREEN)).andExpect(status().isAccepted());
		drain();
		this.owner.get("/api/me/profile").andExpect(jsonPath("$.avatar.photo.errorCode").value("too_large"));
		this.owner.get(avatar(this.me)).andExpect(status().isNotFound()).andExpect(header().doesNotExist("Retry-After"));
	}

	@Test
	void scannerVerdictIsAppliedBeforeDecoding() throws Exception {
		ContentScanner infected = (content, size) -> new ContentScanner.Infected("Eicar-Test-Signature");
		AvatarImages scanning = new AvatarImages(this.profileImages, this.store, this.mates, this.normalizer,
				Optional.of(infected));
		upload(this.owner, png(64, 64, Color.RED)).andExpect(status().isAccepted());
		while (scanning.processNext()) {
			// 대기 사진을 모두 처리한다.
		}
		this.owner.get("/api/me/profile")
			.andExpect(jsonPath("$.avatar.photo.state").value("failed"))
			.andExpect(jsonPath("$.avatar.photo.errorCode").value("rejected"));

		ContentScanner down = (content, size) -> new ContentScanner.Unavailable("connect");
		AvatarImages waiting = new AvatarImages(this.profileImages, this.store, this.mates, this.normalizer,
				Optional.of(down));
		upload(this.owner, png(64, 64, Color.RED)).andExpect(status().isAccepted());
		assertThat(waiting.processNext()).isTrue();
		// 검사기에 닿지 못하면 다시 시도할 때까지 처리 중이다.
		this.owner.get("/api/me/profile").andExpect(jsonPath("$.avatar.photo.state").value("processing"));
		assertThat(this.jdbc.sql("SELECT attempt_count FROM profile_images WHERE user_id = :u AND image_status = 'TEMP'")
			.param("u", this.me)
			.query(Integer.class)
			.single()).isOne();
	}

	@Test
	void replacingAndRemovingDiscardThePreviousPhotoAndItsObjects() throws Exception {
		upload(this.owner, png(300, 300, Color.RED)).andExpect(status().isAccepted());
		drain();
		String first = activeKey(this.me);
		String etag = this.owner.get(avatar(this.me)).andReturn().getResponse().getHeader("ETag");

		// 새 사진을 올리는 동안에는 앞 사진이 그대로 보인다.
		upload(this.owner, png(300, 300, Color.BLUE)).andExpect(status().isAccepted());
		this.owner.get("/api/me/profile")
			.andExpect(jsonPath("$.avatar.type").value("image"))
			.andExpect(jsonPath("$.avatar.photo.state").value("processing"))
			.andExpect(jsonPath("$.avatar.photo.url").value(avatar(this.me)));
		this.owner.get(avatar(this.me))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", etag))
			.andExpect(header().doesNotExist("Retry-After"));
		drain();
		assertThat(this.owner.get(avatar(this.me)).andReturn().getResponse().getHeader("ETag")).isNotEqualTo(etag);
		assertThat(exists(first)).isFalse();

		// 처리 중인 사진은 색상 저장이 버린다.
		upload(this.owner, png(300, 300, Color.GREEN)).andExpect(status().isAccepted());
		int version = ApiClient.json(this.owner.get("/api/me/profile")).get("avatar").get("version").asInt();
		this.owner.send(MockMvcRequestBuilders.put("/api/me/profile")
			.contentType("application/json")
			.content("{\"avatarColor\":\"cobalt\",\"shortName\":\"AB\",\"version\":" + version + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar.type").value("color"))
			.andExpect(jsonPath("$.avatar.photo").doesNotExist());
		drain();
		this.owner.get(avatar(this.me)).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM profile_images WHERE user_id = :u AND image_status <> 'DELETED'")
			.param("u", this.me)
			.query(Integer.class)
			.single()).isZero();

		// 지우기(EP-0278)는 색상형으로 돌아가고 객체도 지운다.
		upload(this.owner, png(300, 300, Color.RED)).andExpect(status().isAccepted());
		drain();
		String last = activeKey(this.me);
		this.owner.delete("/api/me/profile/image")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar.type").value("color"))
			.andExpect(jsonPath("$.avatar.color").value("cobalt"))
			.andExpect(jsonPath("$.avatar.photo").doesNotExist());
		this.avatars.sweep();
		assertThat(exists(last)).isFalse();
		this.classmate.get(avatar(this.me)).andExpect(status().isNotFound());
		this.owner.delete("/api/me/profile/image").andExpect(status().isOk());
		this.mvc.perform(MockMvcRequestBuilders.delete("/api/me/profile/image")
			.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.me)))).andExpect(status().isForbidden());
	}

	@Test
	void aPendingPhotoIsClaimedByOneWorkerAtATime() throws Exception {
		upload(this.owner, png(32, 32, Color.RED)).andExpect(status().isAccepted());
		Optional<ProfileImages.Pending> first = this.profileImages.claim("worker-a", Duration.ofMinutes(5));
		assertThat(first).isPresent();
		assertThat(this.profileImages.claim("worker-b", Duration.ofMinutes(5))).isEmpty();
		// lease를 가진 쪽만 결과를 쓴다.
		assertThat(this.profileImages.fail(first.get().imageId(), "worker-b", "failed")).isFalse();
		assertThat(this.profileImages.activate(first.get().imageId(), "worker-b", "00", 1, 1, 1)).isFalse();
		this.jdbc.sql("UPDATE profile_images SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE profile_image_id = :i")
			.param("i", first.get().imageId())
			.update();
		this.profileImages.recoverExpired(AvatarImages.MAX_ATTEMPTS);
		drain();
		this.owner.get("/api/me/profile").andExpect(jsonPath("$.avatar.photo.state").value("ready"));
	}

	private ResultActions upload(ApiClient client, byte[] content) throws Exception {
		return client.send(MockMvcRequestBuilders.multipart(HttpMethod.POST, "/api/me/profile/image")
			.file(new MockMultipartFile("file", "photo.png", "image/png", content)));
	}

	private void drain() {
		while (this.avatars.processNext()) {
			// 대기 사진을 모두 처리한다.
		}
		this.avatars.sweep();
	}

	private int rows(long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM profile_images WHERE user_id = :u")
			.param("u", userId)
			.query(Integer.class)
			.single();
	}

	private String activeKey(long userId) {
		return this.jdbc.sql("SELECT storage_key FROM profile_images WHERE user_id = :u AND image_status = 'ACTIVE'")
			.param("u", userId)
			.query(String.class)
			.single();
	}

	// 이 사용자가 올린 원본 key의 끝부분. 처리한 원본은 지워져 source_key가 비어 있다.
	private String sourceKeySuffix() {
		String key = this.jdbc.sql("SELECT storage_key FROM profile_images WHERE user_id = :u AND image_status = 'ACTIVE'")
			.param("u", this.me)
			.query(String.class)
			.single();
		return key.substring("avatars/".length(), key.length() - ".jpg".length());
	}

	private boolean exists(String key) {
		return !this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(key)).contents().isEmpty();
	}

	private List<String> objects(String prefix) {
		return this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(prefix))
			.contents()
			.stream()
			.map((o) -> o.key())
			.toList();
	}

	private static String avatar(long userId) {
		return "/api/users/" + userId + "/avatar";
	}

	private static byte[] png(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

}
