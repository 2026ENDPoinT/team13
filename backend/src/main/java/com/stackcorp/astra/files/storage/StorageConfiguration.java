package com.stackcorp.astra.files.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {

	@Bean(destroyMethod = "close")
	S3Client s3Client(StorageProperties properties) {
		S3ClientBuilder builder = S3Client.builder()
			.region(Region.of(properties.region()))
			.forcePathStyle(properties.pathStyle());
		if (properties.endpoint() != null) {
			builder.endpointOverride(properties.endpoint());
		}
		if (StringUtils.hasText(properties.accessKey())) {
			builder.credentialsProvider(StaticCredentialsProvider
				.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
		}
		else {
			builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
		}
		return builder.build();
	}

}
