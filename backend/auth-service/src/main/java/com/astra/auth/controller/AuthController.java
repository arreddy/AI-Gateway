package com.astra.auth.controller;

import com.astra.auth.service.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "auth-service"));
    }

    @PostMapping("/verify")
    public ResponseEntity<Object> verifyToken(@RequestBody TokenVerifyRequest request) {
        log.info("Verifying token");
        Map<String, Object> result = authService.verifyToken(request.token);
        boolean valid = Boolean.TRUE.equals(result.get("valid"));
        return valid ? ResponseEntity.ok(result) : ResponseEntity.status(401).body(result);
    }

    @PostMapping("/api-key/validate")
    public ResponseEntity<Object> validateApiKey(@RequestHeader("Authorization") String apiKey) {
        log.info("Validating API key");
        Map<String, Object> result = authService.validateApiKey(apiKey);
        boolean valid = Boolean.TRUE.equals(result.get("valid"));
        return valid ? ResponseEntity.ok(result) : ResponseEntity.status(401).body(result);
    }

    static class TokenVerifyRequest {
        public String token;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
