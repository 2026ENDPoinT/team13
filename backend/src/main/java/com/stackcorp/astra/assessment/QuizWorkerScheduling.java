package com.stackcorp.astra.assessment;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** worker 프로필에서 마감이 지난 응시를 닫는 주기 작업({@link QuizAttemptCloser})을 켠다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class QuizWorkerScheduling {

}
