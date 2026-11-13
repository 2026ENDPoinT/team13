/**
 * 업로드 lifecycle, 첨부, 파일함, 미리보기, 문서 rendition, 영상 asset, 파일 파생물 저장. S3를 읽고 쓰는 유일한 모듈이다.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "catalog :: api", "identity :: api", "shared" })
package com.stackcorp.astra.files;
