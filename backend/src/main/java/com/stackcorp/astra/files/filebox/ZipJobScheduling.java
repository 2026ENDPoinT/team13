package com.stackcorp.astra.files.filebox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** ZIP worker의 poll은 worker 프로필에서만 돈다. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class ZipJobScheduling {

}
