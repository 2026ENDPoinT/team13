package com.stackcorp.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * LibreOffice headless로 office 문서를 PDF로 바꾼다(ADR-0033). 입력은 악성 문서로 가정한다(FILES-12).
 * <ul>
 * <li>실행 파일은 고정한 인자 목록으로 바로 실행한다. shell 명령 문자열을 만들지 않고, 요청에서 온 값은 인자에 들어가지 않는다. 입력
 * 파일 이름은 {@code document.<확장자>}로 고정한다.</li>
 * <li>작업마다 권한 700의 임시 디렉터리를 새로 만들고 끝나면 지운다. LibreOffice 사용자 profile도 그 안에 새로 만든다. profile은
 * macro 실행, 신뢰하지 않는 위치의 link, OLE·DDE 같은 active content, 불러올 때의 link 갱신을 끈다.</li>
 * <li>환경 변수는 비우고 PATH, HOME, TMPDIR, LANG, SAL_USE_VCLPLUGIN만 넣는다. HOME과 TMPDIR은 작업 디렉터리다.</li>
 * <li>운영 launcher는 prlimit으로 주소 공간, CPU 시간, 한 파일의 크기, 열린 파일 수를 묶고 core dump를 끈다. 제한 시간이 지나면
 * process tree를 강제로 끝낸다({@link BoundedProcess}).</li>
 * <li>결과는 {@code document.pdf} 하나만 받는다. symlink가 아닌 일반 파일이고, 크기 상한 안이며, PDF header로 시작해야 한다.</li>
 * </ul>
 * 외부 network 차단은 이 class가 아니라 converter container의 network 구성이 맡는다(ADR-0019 4번).
 */
final class SofficeEngine implements OfficeEngine {

	static final String INPUT_NAME = "document";

	private static final FileAttribute<Set<PosixFilePermission>> PRIVATE = PosixFilePermissions
		.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

	private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	// LibreOffice 24.2 officecfg 기준. Writer의 Link 2와 Calc의 Link 1이 "갱신하지 않음"이다.
	private static final String PROFILE = """
			<?xml version="1.0" encoding="UTF-8"?>
			<oor:items xmlns:oor="http://openoffice.org/2001/registry" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			<item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item>
			<item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableMacrosExecution" oor:op="fuse"><value>true</value></prop></item>
			<item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="BlockUntrustedRefererLinks" oor:op="fuse"><value>true</value></prop></item>
			<item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="DisableActiveContent" oor:op="fuse"><value>true</value></prop></item>
			<item oor:path="/org.openoffice.Office.Writer/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>2</value></prop></item>
			<item oor:path="/org.openoffice.Office.Calc/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>1</value></prop></item>
			<item oor:path="/org.openoffice.Office.Common/Misc"><prop oor:name="UseLocking" oor:op="fuse"><value>false</value></prop></item>
			</oor:items>
			""";

	private final List<String> launcher;

	private final Path workRoot;

	private final Duration timeout;

	private final long maxPdfBytes;

	/**
	 * @param launcher soffice 인자 앞에 오는 고정 인자. 첫 값은 실행 파일의 절대 경로다
	 * @param workRoot 작업 디렉터리를 만들 곳
	 */
	SofficeEngine(List<String> launcher, Path workRoot, Duration timeout, long maxPdfBytes) {
		this.launcher = List.copyOf(launcher);
		this.workRoot = workRoot;
		this.timeout = timeout;
		this.maxPdfBytes = maxPdfBytes;
	}

	/**
	 * 설치한 LibreOffice를 prlimit 아래에서 부르는 엔진. 두 실행 파일이 없으면 시작하지 않는다.
	 *
	 * @param memoryBytes 주소 공간 상한
	 */
	static SofficeEngine installed(Path prlimit, Path soffice, Path workRoot, Duration timeout, long maxPdfBytes,
			long memoryBytes) {
		for (Path executable : List.of(prlimit, soffice)) {
			if (!executable.isAbsolute() || !Files.isExecutable(executable)) {
				throw new IllegalStateException("실행 파일이 없습니다: " + executable);
			}
		}
		return new SofficeEngine(launcher(prlimit, soffice, timeout, maxPdfBytes, memoryBytes), workRoot, timeout,
				maxPdfBytes);
	}

	static List<String> launcher(Path prlimit, Path soffice, Duration timeout, long maxPdfBytes, long memoryBytes) {
		return List.of(prlimit.toString(), "--as=" + memoryBytes, "--cpu=" + Math.max(1, timeout.toSeconds()),
				"--fsize=" + maxPdfBytes, "--nofile=1024", "--core=0", "--", soffice.toString());
	}

	List<String> command(Path input, Path outDir, Path profile) {
		// Path.toUri는 있는 디렉터리에 끝 '/'를 붙인다. 디렉터리가 있는지와 관계없이 같은 값을 넘긴다.
		String profileUri = profile.toAbsolutePath().toUri().toString().replaceAll("/$", "");
		List<String> command = new ArrayList<>(this.launcher);
		command.addAll(List.of("-env:UserInstallation=" + profileUri, "--headless", "--invisible", "--nologo",
				"--nodefault", "--norestore", "--nolockcheck", "--convert-to", "pdf", "--outdir", outDir.toString(),
				input.toString()));
		return List.copyOf(command);
	}

	static Map<String, String> environment(Path job) {
		return Map.of("PATH", "/usr/bin:/bin", "HOME", job.toString(), "TMPDIR", job.resolve("tmp").toString(), "LANG",
				"C.UTF-8", "SAL_USE_VCLPLUGIN", "svp");
	}

	@Override
	public byte[] toPdf(byte[] document, OfficeFormat format) throws ConversionFailure {
		Path job;
		try {
			job = Files.createTempDirectory(this.workRoot, "office-", PRIVATE);
		}
		catch (IOException ex) {
			throw new ConversionFailure("convert_failed", "작업 디렉터리를 만들지 못했습니다.");
		}
		try {
			Path in = Files.createDirectory(job.resolve("in"), PRIVATE);
			Path out = Files.createDirectory(job.resolve("out"), PRIVATE);
			Files.createDirectory(job.resolve("tmp"), PRIVATE);
			Path profile = Files.createDirectory(job.resolve("profile"), PRIVATE);
			Files.writeString(Files.createDirectory(profile.resolve("user"), PRIVATE).resolve("registrymodifications.xcu"),
					PROFILE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			Path input = in.resolve(INPUT_NAME + "." + format.extension());
			Files.write(input, document, StandardOpenOption.CREATE_NEW);
			BoundedProcess.Outcome outcome = BoundedProcess.run(command(input, out, profile), environment(job), job,
					this.timeout);
			if (outcome.timedOut()) {
				throw new ConversionFailure("timeout", "문서 변환이 제한 시간을 넘었습니다.");
			}
			if (outcome.exitCode() != 0) {
				throw new ConversionFailure("convert_failed", "문서를 PDF로 바꾸지 못했습니다.");
			}
			return output(out, this.maxPdfBytes);
		}
		catch (IOException ex) {
			throw new ConversionFailure("convert_failed", "문서를 PDF로 바꾸지 못했습니다.");
		}
		finally {
			deleteTree(job);
		}
	}

	/** 변환 결과 디렉터리에서 document.pdf 하나를 읽는다. 다른 파일이 있거나 형식이 맞지 않으면 거부한다. */
	static byte[] output(Path outDir, long maxBytes) throws ConversionFailure, IOException {
		List<Path> entries;
		try (Stream<Path> listing = Files.list(outDir)) {
			entries = listing.toList();
		}
		if (entries.isEmpty()) {
			throw new ConversionFailure("convert_failed", "문서를 PDF로 바꾸지 못했습니다.");
		}
		Path pdf = outDir.resolve(INPUT_NAME + ".pdf");
		if (entries.size() != 1 || !entries.getFirst().equals(pdf) || !Files.isRegularFile(pdf, LinkOption.NOFOLLOW_LINKS)) {
			throw new ConversionFailure("invalid_output", "변환 결과가 예상한 형식이 아닙니다.");
		}
		long size = Files.size(pdf);
		if (size > maxBytes) {
			throw new ConversionFailure("output_too_large", "변환 결과가 크기 상한을 넘습니다.");
		}
		byte[] bytes = Files.readAllBytes(pdf);
		if (bytes.length < PDF_HEADER.length || !Arrays.equals(bytes, 0, PDF_HEADER.length, PDF_HEADER, 0,
				PDF_HEADER.length)) {
			throw new ConversionFailure("invalid_output", "변환 결과가 PDF가 아닙니다.");
		}
		return bytes;
	}

	// symlink는 따라가지 않고 link 자체를 지운다. 지우지 못한 항목이 있어도 변환 결과를 바꾸지 않는다.
	static void deleteTree(Path root) {
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException ex) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}

			});
		}
		catch (IOException ex) {
			System.err.println("converter could not wipe a job directory error=" + ex.getClass().getSimpleName());
		}
	}

}
