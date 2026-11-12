package com.stackcorp.transcoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;

/**
 * ffprobe와 ffmpeg 실행. 인자는 고정 목록이고 shell을 거치지 않는다. 입력은 신뢰하지 않는다.
 * <ul>
 * <li>demuxer를 서버가 판정한 컨테이너로 고정한다. 내용을 보고 다른 형식(HLS, concat, image2 등)으로 해석하지 않는다.</li>
 * <li>{@code -protocol_whitelist file}: 입력이 다른 파일이나 network 주소를 참조해도 열지 않는다. mov의 data reference와 절대
 * 경로 참조도 끈다.</li>
 * <li>환경 변수는 PATH만 넘긴다. 이 process에 credential이 있어도 자식 process는 받지 않는다.</li>
 * <li>실행 시간, thread 수, 재생 길이를 제한하고 시간을 넘기면 자식까지 강제로 끝낸다.</li>
 * <li>표준 오류는 버린다. 영상 metadata가 로그에 섞이지 않게 한다.</li>
 * </ul>
 */
final class MediaTools {

	/** 서버가 판정한 컨테이너와 ffmpeg demuxer 옵션. */
	private static final Map<String, List<String>> DEMUXERS = Map.of("mp4", movDemuxer(), "mov", movDemuxer(), "webm",
			List.of("-f", "matroska"), "mkv", List.of("-f", "matroska"));

	static final String PLAYLIST = "index.m3u8";

	static final String SEGMENT_PATTERN = "seg_%05d.ts";

	// 세그먼트 길이(초). 6초는 HLS 저작 지침의 권장값이다.
	private static final int SEGMENT_SECONDS = 6;

	private final String ffmpeg;

	private final String ffprobe;

	private final int threads;

	MediaTools(String ffmpeg, String ffprobe, int threads) {
		this.ffmpeg = ffmpeg;
		this.ffprobe = ffprobe;
		this.threads = threads;
	}

	static boolean supported(String container) {
		return DEMUXERS.containsKey(container);
	}

	/**
	 * @return 컨테이너가 알린 재생 길이(초). 길이를 알리지 않는 파일이면 비어 있는 값
	 * @throws TranscodeFailure 영상으로 읽을 수 없을 때
	 */
	OptionalDouble probeDuration(Path workDir, Path input, String container, Duration timeout)
			throws IOException, TranscodeFailure {
		Path out = workDir.resolve("probe.txt");
		List<String> args = new ArrayList<>(List.of(this.ffprobe, "-v", "error", "-protocol_whitelist", "file"));
		args.addAll(DEMUXERS.get(container));
		args.addAll(List.of("-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
				input.toString()));
		int exit = run(args, workDir, out, timeout);
		if (exit != 0) {
			throw new TranscodeFailure("invalid_media", "영상으로 읽을 수 없는 파일입니다.");
		}
		byte[] bytes = Files.readAllBytes(out);
		String value = new String(bytes, 0, Math.min(bytes.length, 64), StandardCharsets.US_ASCII).strip();
		try {
			double seconds = Double.parseDouble(value);
			return Double.isFinite(seconds) && seconds > 0 ? OptionalDouble.of(seconds) : OptionalDouble.empty();
		}
		catch (NumberFormatException ex) {
			return OptionalDouble.empty();
		}
	}

	/**
	 * 첫 영상 stream과 첫 음성 stream(있으면)을 720p 이하 H.264/AAC MPEG-TS segment로 나눠 outDir에 쓴다.
	 *
	 * @throws TranscodeFailure 변환하지 못했거나 시간을 넘겼을 때
	 */
	void segment(Path workDir, Path input, String container, Path outDir, long maxDurationSeconds, Duration timeout)
			throws IOException, TranscodeFailure {
		List<String> args = new ArrayList<>(List.of(this.ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
				"-nostats", "-protocol_whitelist", "file"));
		args.addAll(DEMUXERS.get(container));
		args.addAll(List.of("-i", input.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata",
				"-1", "-map_chapters", "-1", "-t", Long.toString(maxDurationSeconds), "-vf",
				"scale=-2:'min(720,trunc(ih/2)*2)'", "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main",
				"-crf", "23", "-maxrate", "3M", "-bufsize", "6M", "-pix_fmt", "yuv420p", "-force_key_frames",
				"expr:gte(t,n_forced*" + SEGMENT_SECONDS + ")", "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-threads",
				Integer.toString(this.threads), "-filter_threads", "1", "-f", "hls", "-hls_time",
				Integer.toString(SEGMENT_SECONDS), "-hls_playlist_type", "vod", "-hls_list_size", "0",
				"-hls_segment_type", "mpegts", "-hls_segment_filename", outDir.resolve(SEGMENT_PATTERN).toString(),
				outDir.resolve(PLAYLIST).toString()));
		int exit = run(args, workDir, null, timeout);
		if (exit != 0) {
			throw new TranscodeFailure("invalid_media", "영상을 변환하지 못했습니다.");
		}
	}

	private static List<String> movDemuxer() {
		return List.of("-f", "mov", "-enable_drefs", "0", "-use_absolute_path", "0");
	}

	// stdout은 파일로만 받는다. pipe를 읽다가 자식이 멈추면 제한 시간을 지킬 수 없기 때문이다.
	private static int run(List<String> args, Path workDir, Path stdout, Duration timeout)
			throws IOException, TranscodeFailure {
		ProcessBuilder builder = new ProcessBuilder(args).directory(workDir.toFile())
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.redirectOutput(stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));
		String path = System.getenv("PATH");
		builder.environment().clear();
		if (path != null) {
			builder.environment().put("PATH", path);
		}
		Process process = builder.start();
		process.getOutputStream().close();
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				kill(process);
				throw new TranscodeFailure("timeout", "변환이 제한 시간을 넘었습니다.");
			}
			return process.exitValue();
		}
		catch (InterruptedException ex) {
			kill(process);
			Thread.currentThread().interrupt();
			throw new TranscodeFailure("timeout", "변환이 중단되었습니다.");
		}
	}

	private static void kill(Process process) {
		process.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
	}

}
