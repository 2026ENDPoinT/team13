package com.stackcorp.converter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/**
 * SofficeEngineTests가 LibreOffice 자리에 실행하는 process. soffice와 같은 인자를 받는다. 입력 파일의 첫 줄이 동작을 정한다.
 * <ul>
 * <li>{@code PDF}: document.pdf를 쓰고 끝난다</li>
 * <li>{@code RECORD <path>}: 받은 인자, 환경 변수 이름, 작업 디렉터리를 path에 쓰고 document.pdf를 쓴다</li>
 * <li>{@code HANG <path>}: 자식 process(sleep)를 만들고 그 pid를 path에 쓴 뒤 끝나지 않는다</li>
 * <li>{@code ORPHAN <path>}: 자식 process(sleep)를 만들고 pid를 쓴 뒤 document.pdf를 쓰고 바로 끝난다</li>
 * <li>{@code NOT_PDF}, {@code EXTRA}, {@code LINK}, {@code HUGE <bytes>}, {@code EXIT <code>}, {@code NOTHING}: 이상한 결과</li>
 * </ul>
 */
public final class FakeSoffice {

	private FakeSoffice() {
	}

	public static void main(String[] args) throws Exception {
		List<String> argv = List.of(args);
		Path out = Path.of(argv.get(argv.indexOf("--outdir") + 1));
		Path input = Path.of(argv.getLast());
		String[] command = Files.readString(input, StandardCharsets.UTF_8).lines().findFirst().orElse("").split(" ");
		Path pdf = out.resolve("document.pdf");
		switch (command[0]) {
			case "PDF" -> writePdf(pdf);
			case "RECORD" -> {
				String record = String.join("\n", argv) + "\n--env--\n"
						+ String.join("\n", new TreeSet<>(System.getenv().keySet())) + "\n--cwd--\n"
						+ Path.of("").toAbsolutePath() + "\n";
				Files.writeString(Path.of(command[1]), record, StandardCharsets.UTF_8);
				writePdf(pdf);
			}
			case "HANG" -> {
				child(command[1]);
				Thread.sleep(600_000);
			}
			case "ORPHAN" -> {
				child(command[1]);
				// 엔진이 자식을 한 번은 볼 수 있게 잠시 기다린다.
				Thread.sleep(500);
				writePdf(pdf);
			}
			case "NOT_PDF" -> Files.writeString(pdf, "<html>not a pdf</html>");
			case "EXTRA" -> {
				writePdf(pdf);
				Files.writeString(out.resolve("other.txt"), "extra");
			}
			case "LINK" -> Files.createSymbolicLink(pdf, input);
			case "HUGE" -> Files.write(pdf, huge(Integer.parseInt(command[1])));
			case "EXIT" -> System.exit(Integer.parseInt(command[1]));
			default -> {
				// NOTHING: 아무것도 쓰지 않고 정상 종료한다.
			}
		}
	}

	private static void child(String pidFile) throws IOException {
		Process sleep = new ProcessBuilder("/bin/sleep", "600").start();
		Files.writeString(Path.of(pidFile), Long.toString(sleep.pid()));
	}

	private static void writePdf(Path pdf) throws IOException {
		Files.writeString(pdf, "%PDF-1.7\n% fake\n%%EOF\n", StandardCharsets.US_ASCII);
	}

	private static byte[] huge(int size) {
		byte[] bytes = new byte[size];
		System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 5);
		return bytes;
	}

}
