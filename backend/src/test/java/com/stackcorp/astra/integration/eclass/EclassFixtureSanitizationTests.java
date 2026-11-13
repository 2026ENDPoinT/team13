package com.stackcorp.astra.integration.eclass;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 커밋한 e-Class fixture에 실제 계정 값이 남지 않았는지 본다. 화면에서 뜬 fixture는 글자·번호를 합성 값으로 바꿔 커밋하는데, 링크 안의
 * base64 token이나 영상 동시 시청 확인(accessone) 호출의 사용자 token처럼 눈에 띄지 않는 값이 남은 적이 있다.
 * <p>
 * 규칙. fixture 본문과, 본문 안의 base64 token(표준·URL 문자, 12자 이상) 중 풀면 출력 가능한 ASCII 글자가 되는 것 모두에서:
 * <ul>
 * <li>사용자 자리({@link #USER_FIELDS}: {@code ud}, {@code USER_ID} 등)의 값은 {@link #USERS} 중 하나여야 한다. 값은 따옴표 안의 값과
 * query string 꼴({@code ud=값})이다. 따옴표 없는 스크립트 변수(예 {@code USER_ID : user_id})는 값이 아니다.</li>
 * <li>과목 키 자리({@code ky}, {@code KJKEY}, {@code _KJKEY}, {@code SCH_KJKEY}, {@code name="_KJKEY" value=})의 값은
 * {@link #COURSE_KEYS} 중 하나여야 한다.</li>
 * <li>푼 base64 글자에는 8자리 이상 숫자가 없어야 한다(합성 token은 글자다).</li>
 * </ul>
 * 그래서 새로 뜬 화면은 그 값들을 아래 목록의 합성 값으로 바꾸기 전까지 실패한다. 합성 값을 새로 쓰면 목록에 더한다. 실패 메시지에는
 * 파일 이름과 자리 이름만 남기고 값은 남기지 않는다.
 */
class EclassFixtureSanitizationTests {

	/** 사용자 자리의 합성 값. 학번과 accessone 사용자 token. */
	static final Set<String> USERS = Set.of("20269999", "SYNTHETICACCESSONEUSER0001");

	/** 과목 키 자리의 합성 값. */
	static final Set<String> COURSE_KEYS = Set.of("A202639999900002", "A202639999900003", "A202639999900004",
			"A202639999904001");

	static final Set<String> USER_FIELDS = Set.of("ud", "user_id", "usr_id", "student_id", "std_id");

	private static final Pattern FIELD = Pattern.compile(
			"(?i)\\b(ud|user_id|usr_id|student_id|std_id|ky|_?kjkey|sch_kjkey)\\b([\"']?\\s*[:=]\\s*)([\"']?)([A-Za-z0-9_]*)");

	private static final Pattern KEY_INPUT = Pattern.compile("(?i)name=\"_?kjkey\"\\s+value=\"([A-Za-z0-9]*)\"");

	private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{12,}={0,2}");

	private static final Pattern DIGITS = Pattern.compile("[0-9]{8,}");

	@Test
	void committedFixturesCarryOnlySyntheticAccountValues() throws IOException, URISyntaxException {
		Path dir = Path.of(SchoolContentPagesTests.class.getResource("room-regular.html").toURI()).getParent();
		List<Path> fixtures;
		try (Stream<Path> files = Files.list(dir)) {
			fixtures = files.filter((p) -> p.getFileName().toString().endsWith(".html")).sorted().toList();
		}
		assertThat(fixtures).hasSizeGreaterThan(10);
		List<String> problems = new ArrayList<>();
		for (Path fixture : fixtures) {
			scan(fixture.getFileName().toString(), Files.readString(fixture, StandardCharsets.UTF_8), problems);
		}
		assertThat(problems).isEmpty();
	}

	@Test
	void theRuleCatchesRealValuesInFieldsAndInsideTokens() {
		String token = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString("FILE_SEQ=X&ky=A2026310000000001&ud=20990001&pf_st_flag=2".getBytes(StandardCharsets.US_ASCII));
		List<String> leaked = new ArrayList<>();

		scan("sample", "<a href=\"/ilos/co/efile_preview_check_form.acl?FPP=" + token + "\">", leaked);
		scan("sample", "data: { osms : \"stech\", ud : \"ABCDEFGHIJKLMNOPQRSTUVWX23\" }", leaked);

		// 토큰 안의 과목 키, 학번, 긴 숫자, 그리고 accessone 사용자 token.
		assertThat(leaked).hasSize(4);
		List<String> clean = new ArrayList<>();
		scan("sample", "ud : \"20269999\", ky=A202639999904001, USER_ID : user_id || '', user_id = data, "
				+ "FPP=U1lOVEhFVElD", clean);
		assertThat(clean).isEmpty();
	}

	private static void scan(String file, String body, List<String> problems) {
		check(file, "raw", body, problems);
		Matcher token = TOKEN.matcher(body);
		while (token.find()) {
			String decoded = decoded(token.group());
			if (decoded == null) {
				continue;
			}
			check(file, "base64", decoded, problems);
			if (DIGITS.matcher(decoded).find()) {
				problems.add(file + ": base64 token with a long digit run");
			}
		}
	}

	private static void check(String file, String where, String text, List<String> problems) {
		Matcher field = FIELD.matcher(text);
		while (field.find()) {
			String name = field.group(1).toLowerCase(Locale.ROOT);
			String separator = field.group(2);
			boolean quoted = !field.group(3).isEmpty();
			String value = field.group(4);
			boolean query = separator.equals("=");
			if (value.isEmpty() || (!quoted && !query)) {
				continue;
			}
			Set<String> allowed = USER_FIELDS.contains(name) ? USERS : COURSE_KEYS;
			if (!allowed.contains(value)) {
				problems.add(file + ": " + where + " " + name + " is not a listed synthetic value");
			}
		}
		Matcher input = KEY_INPUT.matcher(text);
		while (input.find()) {
			if (!input.group(1).isEmpty() && !COURSE_KEYS.contains(input.group(1))) {
				problems.add(file + ": " + where + " _kjkey input is not a listed synthetic value");
			}
		}
	}

	/** 출력 가능한 ASCII로 풀리는 base64 token이면 푼 글자, 아니면 null. */
	private static String decoded(String token) {
		String standard = token.replace('-', '+').replace('_', '/').replace("=", "");
		standard = standard + "=".repeat((4 - standard.length() % 4) % 4);
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(standard);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		if (bytes.length < 6) {
			return null;
		}
		for (byte b : bytes) {
			if (b < 32 || b > 126) {
				return null;
			}
		}
		return new String(bytes, StandardCharsets.US_ASCII);
	}

}
