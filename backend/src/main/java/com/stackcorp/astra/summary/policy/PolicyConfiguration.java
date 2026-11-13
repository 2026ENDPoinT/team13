package com.stackcorp.astra.summary.policy;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmPolicyProperties.class)
class PolicyConfiguration {

}
