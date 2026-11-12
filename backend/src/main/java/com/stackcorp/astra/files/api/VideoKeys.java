package com.stackcorp.astra.files.api;

import java.util.Optional;

/**
 * HLS AES-128 key(ADR-0007 8번). 영상 asset마다 16 byte key 하나를 content bucket의 vod-keys/{assetKey}.key에 둔다.
 * key는 권한을 판정한 요청에만 내보낸다.
 */
public interface VideoKeys {

	/** asset key의 형식. 영문 소문자·숫자·하이픈 8~64자. */
	String ASSET_KEY_PATTERN = "[a-z0-9][a-z0-9-]{7,63}";

	/** key가 없으면 만든다. 이미 있으면 그 key를 돌려준다. 변환 worker가 segment를 암호화할 때 쓴다(ADR-0016). */
	byte[] ensure(String assetKey);

	Optional<byte[]> find(String assetKey);

}
