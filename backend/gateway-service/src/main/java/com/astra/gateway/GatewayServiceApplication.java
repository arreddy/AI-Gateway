package com.astra.gateway;

import com.astra.gateway.a2a.service.A2aAgentRegistry;
import com.astra.gateway.mcp.service.McpServerRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableScheduling
@EnableAsync
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

    @Bean
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /** Warm in-memory caches from PostgreSQL on startup. */
    @Bean
    org.springframework.boot.ApplicationRunner registryLoader(
            McpServerRegistry mcpRegistry, A2aAgentRegistry a2aRegistry) {
        return args -> {
            mcpRegistry.loadFromDatabase();
            a2aRegistry.loadFromDatabase();
        };
    }
}
