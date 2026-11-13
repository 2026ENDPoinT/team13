package com.stackcorp.converter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.stackcorp.converter.PdfRendering.Step;

/**
 * PDF 하나를 heap과 시간을 묶은 자식 JVM({@link RenderChild})에서 변환한다. PDFBox가 악성 PDF로 heap을 다 써도 자식만 끝나고
 * converter는 계속 응답한다.
 * <ul>
 * <li>자식은 {@code -Xmx}와 {@code -XX:+ExitOnOutOfMemoryError}로 띄운다. 문서 제한 시간이 지나면 process tree를 끝내고
 * {@code timeout}으로 거부한다({@link BoundedProcess}).</li>
 * <li>자식이 메모리 부족으로 끝나거나 단계 하나가 단계 제한 시간을 넘어 끝나면, 마지막으로 시작한 쪽 단계를 읽고 그 쪽을 다음
 * 단계부터 다시 시작한다. 메모리 부족이면 텍스트 생략 → 낮은 해상도 → 대체 그림 순서이고, 그리기가 시간을 넘으면 낮은 해상도를
 * 건너뛰고 바로 대체 그림이다. 앞 쪽의 결과는 그대로 둔다. 문서를 읽는 단계에서 끝났거나 다시 시작한 횟수가
 * {@value #MAX_RESTARTS}번을 넘으면 메모리 부족이면 {@code too_large}, 시간 초과면 {@code timeout}으로 거부한다.</li>
 * <li>결과는 작업마다 새로 만든 디렉터리(권한 700)에 쌓고, 자식이 모두 끝난 뒤에만 zip으로 보낸다. 그래서 실패를 422로 알릴 수 있다.</li>
 * </ul>
 */
final class RenderProcess {

	static final int DEFAULT_HEAP_MB = 768;

	static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);

	// 쪽 하나의 텍스트 뽑기나 그리기 한 번에 쓸 수 있는 시간. 측정한 정상 쪽은 단계 하나가 2초를 넘지 않았다(B5 corpus, CPU 1개).
	// 그 10배를 두고, 악성 쪽 하나가 converter를 붙잡는 시간은 이 값과 자식을 다시 띄우는 시간으로 묶는다.
	static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(20);

	// 한 문서에서 자식을 다시 띄우는 횟수의 상한. 메모리가 무거운 쪽 셋이 각각 두 단계씩 실패할 수 있는 수다.
	static final int MAX_RESTARTS = 6;

	// -XX:+ExitOnOutOfMemoryError의 종료 코드
	private static final int OUT_OF_MEMORY_EXIT = 3;

	// 128 + SIGKILL. container 메모리가 차서 커널이 자식을 끝냈다.
	private static final int KILLED_EXIT = 137;

	private static final FileAttribute<?>[] PRIVATE = FileSystems.getDefault()
		.supportedFileAttributeViews()
		.contains("posix")
			? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")) }
			: new FileAttribute<?>[0];

	private final Path java;

	private final List<String> options;

	private final String classpath;

	private final List<String> main;

	private final Path workRoot;

	private final Duration timeout;

	private final Duration stepTimeout;

	private RenderProcess(Path java, List<String> options, String classpath, List<String> main, Path workRoot,
			Duration timeout, Duration stepTimeout) {
		this.java = java;
		this.options = List.copyOf(options);
		this.classpath = classpath;
		this.main = List.copyOf(main);
		this.workRoot = workRoot;
		this.timeout = timeout;
		this.stepTimeout = stepTimeout;
	}

	/**
	 * 지금 JVM의 java 실행 파일과 classpath로 자식을 띄운다. converter를 Spring Boot PropertiesLauncher로 띄웠으면 자식도 그렇게
	 * 띄운다.
	 *
	 * @param heapMb 자식 JVM의 heap 상한(MiB)
	 * @param timeout 문서 하나의 제한 시간. 다시 띄운 자식의 시간도 합친다
	 * @param stepTimeout 쪽 하나의 텍스트 뽑기나 그리기 한 번의 제한 시간
	 */
	static RenderProcess sameJvm(Path workRoot, int heapMb, Duration timeout, Duration stepTimeout) {
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		List<String> options = new ArrayList<>(List.of("-Xmx" + heapMb + "m", "-XX:+ExitOnOutOfMemoryError",
				"-XX:+UseSerialGC", "-XX:-UsePerfData", "-Djava.awt.headless=true"));
		String fontCache = System.getProperty("pdfbox.fontcache");
		if (fontCache != null) {
			options.add("-Dpdfbox.fontcache=" + fontCache);
		}
		List<String> main = System.getProperty("loader.main") != null
				? List.of("-Dloader.main=" + RenderChild.class.getName(),
						"org.springframework.boot.loader.launch.PropertiesLauncher")
				: List.of(RenderChild.class.getName());
		String classpath = List.of(System.getProperty("java.class.path").split(File.pathSeparator))
			.stream()
			.map((entry) -> Path.of(entry).toAbsolutePath().toString())
			.collect(Collectors.joining(File.pathSeparator));
		return new RenderProcess(java, options, classpath, main, workRoot, timeout, stepTimeout);
	}

	/**
	 * PDF를 쪽 파일로 바꾼다. 돌려받은 결과는 닫아야 작업 디렉터리가 지워진다.
	 *
	 * @throws ConversionFailure 변환할 수 없는 입력이거나 상한을 넘었을 때
	 */
	Rendered render(byte[] pdf, int maxPages) throws ConversionFailure {
		Path job;
		try {
			job = Files.createTempDirectory(this.workRoot, "render-", PRIVATE);
		}
		catch (IOException ex) {
			throw failed();
		}
		boolean done = false;
		try {
			Files.createDirectory(job.resolve(RenderChild.OUTPUT), PRIVATE);
			Files.createDirectory(job.resolve("tmp"), PRIVATE);
			Files.write(job.resolve(RenderChild.INPUT), pdf, StandardOpenOption.CREATE_NEW);
			// classpath는 Windows 명령줄 길이 상한을 넘을 수 있어 java @argfile로 넘긴다.
			Files.writeString(job.resolve("classpath"),
					"-cp \"" + this.classpath.replace("\\", "\\\\") + "\"\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW);
			long deadline = System.nanoTime() + this.timeout.toNanos();
			Resume resume = new Resume(1, Step.FULL, true);
			for (int restarts = 0;; restarts++) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					throw timeout();
				}
				BoundedProcess.Outcome outcome = BoundedProcess.run(command(job, maxPages, resume), environment(job), job,
						Duration.ofNanos(left));
				if (outcome.timedOut()) {
					throw timeout();
				}
				int exit = outcome.exitCode();
				if (exit == 0) {
					Rendered rendered = Rendered.check(job);
					done = true;
					return rendered;
				}
				if (exit == RenderChild.FAILURE_EXIT) {
					throw failure(job);
				}
				boolean slow = exit == RenderChild.SLOW_EXIT;
				if (!slow && exit != OUT_OF_MEMORY_EXIT && exit != KILLED_EXIT) {
					// 자식의 출력은 문서 내용이 섞일 수 있어 버리므로 종료 코드만 남긴다.
					System.err.println("converter render child failed exit=" + exit);
					throw failed();
				}
				Path progress = job.resolve(RenderChild.PROGRESS);
				Resume next = Files.exists(progress)
						? resume.after(Files.readString(progress, StandardCharsets.US_ASCII), slow) : null;
				System.err.println("converter render child stopped exit=" + exit + " restarts=" + restarts + " next=" + next);
				if (next == null || restarts >= MAX_RESTARTS) {
					throw slow ? timeout()
							: new ConversionFailure("too_large", "문서를 변환하는 데 필요한 메모리가 상한을 넘습니다.");
				}
				resume = next;
			}
		}
		catch (IOException ex) {
			throw failed();
		}
		finally {
			if (!done) {
				SofficeEngine.deleteTree(job);
			}
		}
	}

	private List<String> command(Path job, int maxPages, Resume resume) {
		List<String> command = new ArrayList<>();
		command.add(this.java.toString());
		command.addAll(this.options);
		command.add("-Djava.io.tmpdir=" + job.resolve("tmp"));
		command.add("@" + job.resolve("classpath"));
		command.addAll(this.main);
		command.addAll(List.of(job.toString(), String.valueOf(maxPages), String.valueOf(resume.page()),
				resume.render().name(), String.valueOf(resume.withText()), String.valueOf(this.stepTimeout.toMillis())));
		return command;
	}

	// soffice와 같이 환경 변수를 비우고 필요한 것만 넣는다. Windows의 JVM은 SystemRoot가 있어야 뜬다.
	private static Map<String, String> environment(Path job) {
		Map<String, String> env = new HashMap<>(Map.of("LANG", "C.UTF-8", "HOME", job.toString(), "TMPDIR",
				job.resolve("tmp").toString()));
		String systemRoot = System.getenv("SystemRoot");
		if (systemRoot != null) {
			env.put("SystemRoot", systemRoot);
		}
		return env;
	}

	private static ConversionFailure failure(Path job) throws IOException {
		String[] lines = Files.readString(job.resolve(RenderChild.FAILURE), StandardCharsets.UTF_8).split("\n", 2);
		return new ConversionFailure(lines[0], lines.length > 1 ? lines[1] : "");
	}

	private static ConversionFailure failed() {
		return new ConversionFailure("failed", "쪽을 변환하지 못했습니다.");
	}

	private static ConversionFailure timeout() {
		return new ConversionFailure("timeout", "문서 변환이 제한 시간을 넘었습니다.");
	}

	/**
	 * 자식을 시작할 쪽과 그 쪽의 단계.
	 *
	 * @param render 그 쪽을 그리기 시작할 단계
	 * @param withText 그 쪽의 텍스트를 뽑을지
	 */
	record Resume(int page, Step render, boolean withText) {

		/**
		 * 자식이 마지막으로 시작한 단계("쪽 단계")에서 끝났을 때 다음에 시작할 곳. 다시 시작해도 소용없거나, 파일을 쓰는 도중에 끝나
		 * 내용을 읽을 수 없으면 null.
		 *
		 * @param slow 단계 제한 시간을 넘어 끝났는지. 그리기가 오래 걸리는 쪽(메시 셰이딩, 그리기 명령 수백만 개)은 해상도를 낮춰도
		 * 거의 그대로 오래 걸리므로, 낮은 해상도로 다시 그리지 않고 바로 대체 그림으로 간다. 낮은 해상도는 메모리 부족일 때만 쓴다
		 */
		Resume after(String progress, boolean slow) {
			String[] parts = progress.strip().split(" ");
			int at;
			Step step;
			try {
				at = Integer.parseInt(parts[0]);
				step = Step.valueOf(parts[1]);
			}
			catch (RuntimeException ex) {
				return null;
			}
			boolean text = at != this.page || this.withText;
			return switch (step) {
				case TEXT -> new Resume(at, Step.FULL, false);
				case FULL -> new Resume(at, slow ? Step.PLACEHOLDER : Step.LOW, text);
				case LOW -> new Resume(at, Step.PLACEHOLDER, text);
				default -> null;
			};
		}

	}

	/** 자식이 쓴 쪽 파일. 닫으면 작업 디렉터리를 지운다. */
	static final class Rendered implements AutoCloseable {

		private final Path job;

		private final int pages;

		private Rendered(Path job, int pages) {
			this.job = job;
			this.pages = pages;
		}

		static Rendered check(Path job) throws IOException {
			Path out = job.resolve(RenderChild.OUTPUT);
			int pages = 0;
			while (Files.exists(out.resolve("%03d.json".formatted(pages + 1)))) {
				pages++;
			}
			for (String name : List.of("thumbnail.jpg", "document.json")) {
				if (!Files.isRegularFile(out.resolve(name))) {
					throw new NoSuchFileException(name);
				}
			}
			return new Rendered(job, pages);
		}

		/**
		 * zip으로 쪽마다 pages/NNN.json, pages/NNN.display.jpg, pages/NNN.model.jpg를 순서대로 쓰고, 첫 쪽 썸네일
		 * thumbnail.jpg와 마지막에 document.json을 쓴다. NNN.json은 {@code page}, {@code text}, {@code textTruncated},
		 * {@code imageUnavailable}(쪽 그림 대신 대체 그림을 넣었는지)이다.
		 */
		void writeZip(OutputStream out) throws IOException {
			Path dir = this.job.resolve(RenderChild.OUTPUT);
			try (ZipOutputStream zip = new ZipOutputStream(out)) {
				zip.setLevel(0);
				for (int page = 1; page <= this.pages; page++) {
					String name = "%03d".formatted(page);
					for (String suffix : List.of(".json", ".display.jpg", ".model.jpg")) {
						entry(zip, "pages/" + name + suffix, dir.resolve(name + suffix));
					}
				}
				entry(zip, "thumbnail.jpg", dir.resolve("thumbnail.jpg"));
				entry(zip, "document.json", dir.resolve("document.json"));
			}
		}

		@Override
		public void close() {
			SofficeEngine.deleteTree(this.job);
		}

		private static void entry(ZipOutputStream zip, String name, Path file) throws IOException {
			zip.putNextEntry(new ZipEntry(name));
			Files.copy(file, zip);
			zip.closeEntry();
		}

	}

}
