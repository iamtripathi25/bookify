package com.iamtripathi25.bookify;

import org.springframework.boot.SpringApplication;

public class TestBookifyApplication {

	public static void main(String[] args) {
		SpringApplication.from(BookifyApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
