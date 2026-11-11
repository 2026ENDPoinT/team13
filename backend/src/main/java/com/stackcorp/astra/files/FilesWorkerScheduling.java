package com.stackcorp.astra.files;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * files의 worker 작업(영상 변환, 끝나지 않은 multipart upload 정리)은 worker 프로필에서만 일정에 따라 실행한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class FilesWorkerScheduling {

}
