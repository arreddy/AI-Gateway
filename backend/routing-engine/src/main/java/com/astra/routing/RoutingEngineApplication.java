package com.astra.routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class RoutingEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoutingEngineApplication.class, args);
    }
}
