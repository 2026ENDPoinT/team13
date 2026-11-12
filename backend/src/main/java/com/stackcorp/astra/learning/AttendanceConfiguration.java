package com.stackcorp.astra.learning;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SmartAttendanceProperties.class)
class AttendanceConfiguration {

}
