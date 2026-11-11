package com.stackcorp.astra.files.api;

/**
 * 강의 영상 asset(ADR-0016). 첨부를 확정한 영상 파일을 HLS AES-128로 변환하도록 예약한다. worker가 변환을 마치면
 * {@link VideoAssetListener}로 부모 모듈에 알린다. 권한 판정은 부르는 쪽이 한다.
 */
public interface VideoAssets {

	/**
	 * 영상 파일의 asset을 만들고 변환 작업을 대기열에 넣는다. 부모 저장과 같은 transaction 안에서 부른다.
	 *
	 * @param keyUri 재생 목록의 HLS key URI. 같은 origin의 절대 경로이고 그 API가 요청마다 권한을 판정한다
	 * @return asset ID
	 * @throws AttachmentException 검사를 통과한 영상 파일이 아니거나 이미 asset이 있을 때. 부모 transaction을 되돌려야 한다
	 */
	long register(long fileId, String keyUri);

}
