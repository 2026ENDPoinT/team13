package com.stackcorp.astra.files;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

/**
 * transcoder가 넘긴 segment를 HLS AES-128(AES-128-CBC, PKCS#7)로 암호화해 delivery bucket의 vod/{assetKey}/에 쓰고, 마지막에
 * 재생 목록을 쓴다(ADR-0007 8번, ADR-0016). segment마다 IV를 새로 만들어 그 segment 앞의 EXT-X-KEY에 적는다. 재생 목록은
 * transcoder 출력이 아니라 이 class가 segment 길이로 다시 만든다. key는 로그와 파일에 남기지 않는다.
 * <p>
 * 이름은 delivery 경로 검사(DeliveryObjectReader)가 받는 형식이다: seg_NNNNN.ts, index.m3u8.
 */
final class HlsPackager {

	static final String PLAYLIST = "index.m3u8";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ObjectStore store;

	private final String prefix;

	private final SecretKeySpec key;

	private final String keyUri;

	private final List<Entry> entries = new ArrayList<>();

	private long bytes;

	HlsPackager(ObjectStore store, String assetKey, byte[] key, String keyUri) {
		if (key.length != 16) {
			throw new IllegalArgumentException("HLS AES-128 key는 16 byte입니다.");
		}
		this.store = store;
		this.prefix = prefix(assetKey);
		this.key = new SecretKeySpec(key, "AES");
		this.keyUri = keyUri;
	}

	static String prefix(String assetKey) {
		return "vod/" + assetKey + "/";
	}

	void accept(int index, double durationSeconds, byte[] mpegTs) {
		if (index != this.entries.size()) {
			throw new IllegalStateException("segment 순서가 맞지 않습니다.");
		}
		if (!(durationSeconds > 0 && durationSeconds <= 60)) {
			throw new IllegalStateException("segment 길이가 범위를 벗어납니다.");
		}
		byte[] iv = new byte[16];
		RANDOM.nextBytes(iv);
		byte[] encrypted = encrypt(mpegTs, iv);
		String name = "seg_%05d.ts".formatted(index);
		this.store.put(Bucket.DELIVERY, this.prefix + name, encrypted, "video/mp2t");
		this.entries.add(new Entry(name, durationSeconds, iv));
		this.bytes += encrypted.length;
	}

	/** 재생 목록을 쓴다. segment를 모두 쓴 뒤에만 부른다. */
	Published publish() {
		if (this.entries.isEmpty()) {
			throw new IllegalStateException("segment가 없습니다.");
		}
		double max = 0;
		double total = 0;
		for (Entry entry : this.entries) {
			max = Math.max(max, entry.durationSeconds());
			total += entry.durationSeconds();
		}
		StringBuilder playlist = new StringBuilder();
		playlist.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n")
			.append("#EXT-X-TARGETDURATION:")
			.append((long) Math.ceil(max))
			.append("\n#EXT-X-MEDIA-SEQUENCE:0\n");
		for (Entry entry : this.entries) {
			playlist.append("#EXT-X-KEY:METHOD=AES-128,URI=\"")
				.append(this.keyUri)
				.append("\",IV=0x")
				.append(HexFormat.of().formatHex(entry.iv()))
				.append('\n')
				.append(String.format(Locale.ROOT, "#EXTINF:%.6f,\n", entry.durationSeconds()))
				.append(entry.name())
				.append('\n');
		}
		playlist.append("#EXT-X-ENDLIST\n");
		byte[] content = playlist.toString().getBytes(StandardCharsets.US_ASCII);
		this.store.put(Bucket.DELIVERY, this.prefix + PLAYLIST, content, "application/vnd.apple.mpegurl");
		return new Published(this.prefix + PLAYLIST, (int) Math.round(total), this.entries.size(),
				this.bytes + content.length);
	}

	private byte[] encrypt(byte[] plain, byte[] iv) {
		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new IvParameterSpec(iv));
			return cipher.doFinal(plain);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("segment를 암호화하지 못했습니다.", ex);
		}
	}

	record Published(String playlistPath, int durationSec, int segmentCount, long outputBytes) {
	}

	private record Entry(String name, double durationSeconds, byte[] iv) {
	}

}
