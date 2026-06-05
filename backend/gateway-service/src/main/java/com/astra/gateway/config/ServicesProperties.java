package com.astra.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "services")
public class ServicesProperties {

    private Observability observability = new Observability();
    private Routing routing = new Routing();
    private Auth auth = new Auth();

    @Data
    public static class Observability {
        private String url = "http://observability-service:8086";
    }

    @Data
    public static class Routing {
        private String url = "http://routing-engine:8084";
        private int timeoutMs = 500;
    }

    @Data
    public static class Auth {
        private String url = "http://auth-service:8083";
        private int timeoutMs = 1000;
        private boolean enabled = true; // set false for local dev without auth-service
    }
}
