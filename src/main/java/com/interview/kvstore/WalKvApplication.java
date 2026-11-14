package com.interview.kvstore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Spring Boot application. Lives in the ROOT package
 * (com.interview.kvstore) so that @SpringBootApplication's default
 * component scan covers every sub-package -- api, core, wal, recovery,
 * exception, tools -- without needing an explicit scanBasePackages.
 */
@SpringBootApplication
public class WalKvApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalKvApplication.class, args);
    }
}
