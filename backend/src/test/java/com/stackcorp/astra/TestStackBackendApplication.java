package com.stackcorp.astra;

import org.springframework.boot.SpringApplication;

public class TestStackBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(StackBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
