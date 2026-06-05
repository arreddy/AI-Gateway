package com.astra.gateway.client;

import com.astra.gateway.config.ServicesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class AuthClient {

    /** Returned to the interceptor so tenant context can be threaded downstream. */
    public record ValidationResult(boolean valid, String tenantId, String reason) {
        static ValidationResult ok(String tenantId) {
            return new ValidationResult(true, tenantId, null);
        }
        static ValidationResult denied(String reason) {
            return new ValidationResult(false, null, reason);
        }
    }

    private final RestTemplate http;
    private final ServicesProperties services;

    public AuthClient(ServicesProperties services) {
        this.services = services;
        int ms = services.getAuth().getTimeoutMs();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(ms);
        factory.setReadTimeout(ms);
        this.http = new RestTemplate(factory);
    }

    public ValidationResult validate(String authorizationHeader) {
        if (!services.getAuth().isEnabled()) {
            // Auth disabled — pass through with a synthetic tenant ID for local dev
            return ValidationResult.ok("dev-tenant");
        }

        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return ValidationResult.denied("missing_authorization_header");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);

            ResponseEntity<Map<String, Object>> response = http.exchange(
                services.getAuth().getUrl() + "/v1/auth/api-key/validate",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body != null && Boolean.TRUE.equals(body.get("valid"))) {
                String tenantId = body.containsKey("tenant_id")
                    ? String.valueOf(body.get("tenant_id"))
                    : "unknown";
                return ValidationResult.ok(tenantId);
            }

            String reason = body != null && body.containsKey("reason")
                ? body.get("reason").toString() : "invalid_key";
            return ValidationResult.denied(reason);

        } catch (HttpClientErrorException.Unauthorized e) {
            return ValidationResult.denied("invalid_api_key");
        } catch (Exception e) {
            log.warn("Auth-service unreachable: {}", e.getMessage());
            // Fail-open: allow request but log it — change to denied() for fail-closed
            return ValidationResult.ok("unknown-tenant");
        }
    }
}
