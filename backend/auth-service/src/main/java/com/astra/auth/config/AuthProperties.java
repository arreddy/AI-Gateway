package com.astra.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private Jwt jwt = new Jwt();

    @Data
    public static class Jwt {
        private String secret = "astra-gateway-dev-secret-key-minimum-256-bits-long-change-in-production";
        private long expirationMs = 86_400_000L; // 24 hours
    }
}
