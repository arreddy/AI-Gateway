package com.astra.auth.controller;

import com.astra.auth.entity.ApiKey;
import com.astra.auth.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    /** Create a new API key for a tenant. The raw key is returned only in this response. */
    @PostMapping("/v1/tenants/{tenantId}/api-keys")
    public ResponseEntity<Object> create(
            @PathVariable UUID tenantId,
            @RequestBody Map<String, Object> request) {
        try {
            Map<String, Object> result = apiKeyService.create(tenantId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/v1/tenants/{tenantId}/api-keys")
    public ResponseEntity<List<ApiKey>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(apiKeyService.listForTenant(tenantId));
    }

    @DeleteMapping("/v1/api-keys/{keyId}")
    public ResponseEntity<Object> revoke(@PathVariable UUID keyId) {
        try {
            apiKeyService.revoke(keyId);
            return ResponseEntity.ok(Map.of("status", "revoked", "key_id", keyId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** Validate a raw API key (used by gateway-service). */
    @PostMapping("/v1/auth/api-key/validate")
    public ResponseEntity<Object> validate(@RequestHeader("Authorization") String authorization) {
        Map<String, Object> result = apiKeyService.validate(authorization);
        boolean valid = Boolean.TRUE.equals(result.get("valid"));
        return valid ? ResponseEntity.ok(result) : ResponseEntity.status(401).body(result);
    }
}
