package com.stackcorp.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * LibreOffice 호출 경계(ADR-0033, FILES-12). 빌드 환경에 LibreOffice가 없으므로 soffice 자리에 같은 인자를 받는 Java process
 * ({@link FakeSoffice})를 실행해 엔진의 인자, 환경, 임시 디렉터리, 제한 시간과 강제 종료, 결과 검사를 확인한다.
 */
class SofficeEngineTests {

	private static final long MAX_PDF = 64 * 1024;

	@TempDir
	Path work;

	@TempDir
	Path probe;

	@Test
	void runsPrlimitThenSofficeWithAFixedArgumentListAndNoShell() {
		SofficeEngine engine = new SofficeEngine(SofficeEngine.launcher(Path.of("/usr/bin/prlimit"),
				Path.of("/usr/lib/libreoffice/program/soffice"), Duration.ofSeconds(120), 100L << 20, 3L << 30),
				this.work, Duration.ofSeconds(120), 100L << 20);
		Path job = this.work.resolve("office-1");

		List<String> command = engine.command(job.resolve("in/document.pptx"), job.resolve("out"), job.resolve("profile"));

		assertThat(command).containsExactly("/usr/bin/prlimit", "--as=3221225472", "--cpu=120", "--fsize=104857600",
				"--nofile=1024", "--core=0", "--", "/usr/lib/libreoffice/program/soffice",
				"-env:UserInstallation=" + job.resolve("profile").toUri(), "--headless", "--invisible", "--nologo",
				"--nodefault", "--norestore", "--nolockcheck", "--convert-to", "pdf", "--outdir",
				job.resolve("out").toString(), job.resolve("in/document.pptx").toString());
		assertThat(command).noneMatch((arg) -> arg.equals("-c") || arg.endsWith("/sh") || arg.endsWith("/bash"));
	}

	@Test
	void refusesToStartWithoutTheInstalledExecutables() {
		assertThatIllegalStateException().isThrownBy(() -> SofficeEngine.installed(Path.of("/nonexistent/prlimit"),
				Path.of("/nonexistent/soffice"), this.work, Duration.ofSeconds(5), MAX_PDF, 1L << 30));
	}

	@Test
	void invokesTheEngineInAPrivateJobDirectoryWithAClearedEnvironmentAndWipesIt() throws Exception {
		Path record = this.probe.resolve("record.txt");

		byte[] pdf = engine(Duration.ofSeconds(30)).toPdf(input("RECORD " + record), OfficeFormat.DOCX);

		assertThat(new String(pdf, StandardCharsets.US_ASCII)).startsWith("%PDF-");
		String[] sections = Files.readString(record).split("\n--env--\n|\n--cwd--\n");
		List<String> args = sections[0].lines().toList();
		Path job = Path.of(sections[2].strip());
		assertThat(job.getParent()).isEqualTo(this.work);
		assertThat(job.getFileName().toString()).startsWith("office-");
		assertThat(args).containsSubsequence("-env:UserInstallation=" + job.resolve("profile").toUri(), "--headless",
				"--convert-to", "pdf", "--outdir", job.resolve("out").toString(),
				job.resolve("in/document.docx").toString());
		assertThat(sections[1].lines().toList()).containsExactly("HOME", "LANG", "PATH", "SAL_USE_VCLPLUGIN", "TMPDIR");
		// 작업 디렉터리는 변환이 끝나면 남지 않는다.
		assertThat(Files.exists(job)).isFalse();
		assertThat(listing(this.work)).isEmpty();
	}

	@Test
	void writesAProfileThatDisablesMacrosLinksAndActiveContent() throws Exception {
		Path copy = this.probe.resolve("profile.xcu");
		// 엔진이 변환 중에 쓴 profile을 soffice 자리의 process가 복사해 둔다.
		SofficeEngine engine = new SofficeEngine(List.of(javaPath(), "-cp", System.getProperty("java.class.path"),
				ProfileCopier.class.getName(), copy.toString()), this.work, Duration.ofSeconds(30), MAX_PDF);

		engine.toPdf(input("PDF"), OfficeFormat.XLSX);

		String profile = Files.readString(copy);
		assertThat(profile).contains("\"DisableMacrosExecution\" oor:op=\"fuse\"><value>true</value>",
				"\"MacroSecurityLevel\" oor:op=\"fuse\"><value>3</value>",
				"\"BlockUntrustedRefererLinks\" oor:op=\"fuse\"><value>true</value>",
				"\"DisableActiveContent\" oor:op=\"fuse\"><value>true</value>",
				"Office.Writer/Content/Update\"><prop oor:name=\"Link\" oor:op=\"fuse\"><value>2</value>",
				"Office.Calc/Content/Update\"><prop oor:name=\"Link\" oor:op=\"fuse\"><value>1</value>");
	}

	@Test
	void killsTheProcessTreeWhenTheConversionRunsPastTheTimeout() throws Exception {
		Path pidFile = this.probe.resolve("child.pid");
		long started = System.nanoTime();

		ConversionFailure failure = catchThrowableOfType(ConversionFailure.class,
				() -> engine(Duration.ofSeconds(8)).toPdf(input("HANG " + pidFile), OfficeFormat.PPTX));

		assertThat(failure.code()).isEqualTo("timeout");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
		long child = Long.parseLong(Files.readString(pidFile).strip());
		assertThat(terminated(child)).isTrue();
		assertThat(listing(this.work)).isEmpty();
	}

	@Test
	void killsChildrenLeftBehindAfterANormalExit() throws Exception {
		Path pidFile = this.probe.resolve("orphan.pid");

		engine(Duration.ofSeconds(30)).toPdf(input("ORPHAN " + pidFile), OfficeFormat.PPTX);

		long child = Long.parseLong(Files.readString(pidFile).strip());
		assertThat(terminated(child)).isTrue();
	}

	@Test
	void rejectsOutputThatIsNotASinglePdfWithinTheSizeLimit() throws Exception {
		assertThat(failure("NOT_PDF")).isEqualTo("invalid_output");
		assertThat(failure("EXTRA")).isEqualTo("invalid_output");
		assertThat(failure("LINK")).isEqualTo("invalid_output");
		assertThat(failure("HUGE " + (MAX_PDF + 1))).isEqualTo("output_too_large");
		assertThat(failure("EXIT 3")).isEqualTo("convert_failed");
		assertThat(failure("NOTHING")).isEqualTo("convert_failed");
		assertThat(listing(this.work)).isEmpty();
	}

	@Test
	void acceptsOutputAtTheSizeLimit() throws Exception {
		byte[] pdf = engine(Duration.ofSeconds(30)).toPdf(input("HUGE " + MAX_PDF), OfficeFormat.DOCX);

		assertThat(pdf).hasSize((int) MAX_PDF);
	}

	private String failure(String behaviour) throws IOException {
		return catchThrowableOfType(ConversionFailure.class,
				() -> engine(Duration.ofSeconds(30)).toPdf(input(behaviour), OfficeFormat.DOCX)).code();
	}

	private SofficeEngine engine(Duration timeout) {
		return new SofficeEngine(List.of(javaPath(), "-cp", System.getProperty("java.class.path"),
				FakeSoffice.class.getName()), this.work, timeout, MAX_PDF);
	}

	private static byte[] input(String behaviour) {
		return (behaviour + "\n").getBytes(StandardCharsets.UTF_8);
	}

	// 끝난 process이거나, 강제로 끝낸 뒤 부모가 아직 거두지 않은 zombie면 true
	private static boolean terminated(long pid) {
		Path stat = Path.of("/proc/" + pid + "/stat");
		if (!Files.exists(stat)) {
			return ProcessHandle.of(pid).map((p) -> !p.isAlive()).orElse(true);
		}
		String content;
		try {
			content = Files.readString(stat);
		}
		catch (IOException ex) {
			return true;
		}
		// 형식: pid (comm) state ... comm에 공백과 괄호가 있을 수 있어 마지막 ')' 뒤를 본다.
		return content.substring(content.lastIndexOf(')') + 2).startsWith("Z");
	}

	private static String javaPath() {
		return ProcessHandle.current().info().command().orElseThrow();
	}

	private static List<Path> listing(Path dir) throws IOException {
		try (var entries = Files.list(dir)) {
			return new ArrayList<>(entries.toList());
		}
	}

	/** profile을 검사하려고 변환 중에 엔진이 쓴 profile을 복사하고 PDF를 쓴다. 첫 인자가 복사할 곳이다. */
	public static final class ProfileCopier {

		private ProfileCopier() {
		}

		public static void main(String[] args) throws IOException {
			List<String> argv = List.of(args);
			String profileUri = argv.stream()
				.filter((a) -> a.startsWith("-env:UserInstallation="))
				.findFirst()
				.orElseThrow()
				.substring("-env:UserInstallation=".length());
			Path profile = Path.of(java.net.URI.create(profileUri)).resolve("user/registrymodifications.xcu");
			Files.copy(profile, Path.of(args[0]));
			Path out = Path.of(argv.get(argv.indexOf("--outdir") + 1));
			Files.writeString(out.resolve("document.pdf"), "%PDF-1.7\n%%EOF\n");
		}

	}

}
