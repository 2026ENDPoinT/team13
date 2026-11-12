package com.stackcorp.astra.files;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
@EnableConfigurationProperties(StorageGcProperties.class)
class StorageGcConfiguration {

}
