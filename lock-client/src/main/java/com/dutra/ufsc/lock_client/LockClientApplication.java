package com.dutra.ufsc.lock_client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LockClientApplication {

	public static void main(String[] args) {
		SpringApplication.run(LockClientApplication.class, args);
	}

}
