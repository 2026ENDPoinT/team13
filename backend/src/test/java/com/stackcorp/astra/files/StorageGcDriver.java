package com.stackcorp.astra.files;

import org.springframework.context.ApplicationContext;

/**
 * 다른 package의 테스트가 저장소 GC를 돌릴 때 쓴다. 테스트에서는 스케줄 대신 직접 부른다.
 */
public final class StorageGcDriver {

	private StorageGcDriver() {
	}

	/** 처리할 것이 없을 때까지 세 단계를 차례로 돌린다. */
	public static void drain(ApplicationContext context) {
		StorageGc gc = context.getBean(StorageGc.class);
		while (gc.expireUploads() > 0) {
			// 다음 묶음
		}
		while (gc.purgeDeletedFiles() > 0) {
			// 다음 묶음
		}
		while (gc.purgeBlobs() > 0) {
			// 다음 묶음
		}
	}

}
