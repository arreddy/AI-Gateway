package com.astra.a2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class A2AServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(A2AServiceApplication.class, args);
    }
}
