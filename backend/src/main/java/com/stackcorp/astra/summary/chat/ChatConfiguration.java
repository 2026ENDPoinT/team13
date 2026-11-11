package com.stackcorp.astra.summary.chat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StudyChatProperties.class)
class ChatConfiguration {

}
