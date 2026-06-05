package com.astra.auth.service;

import com.astra.auth.entity.ApiKey;
import com.astra.auth.entity.Tenant;
import com.astra.auth.repository.ApiKeyRepository;
import com.astra.auth.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final StringRedisTemplate redis;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Create a new API key; returns a map that includes the raw key (shown only once). */
    @Transactional
    public Map<String, Object> create(UUID tenantExternalId, Map<String, Object> request) {
        Tenant tenant = tenantRepository.findByExternalId(tenantExternalId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantExternalId));

        String rawKey = generateRawKey();
        String hash   = sha256(rawKey);
        String preview = rawKey.substring(rawKey.length() - 8);

        ApiKey key = new ApiKey();
        key.setTenantId(tenant.getId());
        key.setKeyHash(hash);
        key.setKeyPreview(preview);
        key.setName((String) request.getOrDefault("name", "API Key"));
        key.setStatus("active");

        if (request.containsKey("permissions")) {
            Object perms = request.get("permissions");
            if (perms instanceof List<?> list) {
                key.setPermissions(list.stream().map(Object::toString).reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b));
            }
        }
        if (request.containsKey("expires_at")) {
            key.setExpiresAt(OffsetDateTime.parse((String) request.get("expires_at")));
        }

        ApiKey saved = apiKeyRepository.save(key);

        // Cache in Redis for fast validation (avoid DB hit on every request)
        cacheKey(rawKey, tenant.getExternalId().toString(), saved.getPermissions());

        log.info("Created API key for tenant: {}", tenant.getSlug());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", saved.getExternalId());
        response.put("key", rawKey);           // returned ONCE only
        response.put("key_preview", "..." + preview);
        response.put("name", saved.getName());
        response.put("status", saved.getStatus());
        response.put("permissions", Arrays.asList(saved.getPermissions().split(",")));
        response.put("expires_at", saved.getExpiresAt());
        response.put("created_at", saved.getCreatedAt());
        return response;
    }

    /** Validate a raw API key; returns validation result with tenant context. */
    public Map<String, Object> validate(String rawKey) {
        String clean = rawKey.startsWith("Bearer ") ? rawKey.substring(7).trim() : rawKey.trim();
        String hash  = sha256(clean);

        Optional<ApiKey> keyOpt = apiKeyRepository.findByKeyHash(hash);
        if (keyOpt.isEmpty()) {
            return Map.of("valid", false, "reason", "key_not_found");
        }

        ApiKey key = keyOpt.get();
        if (!"active".equals(key.getStatus())) {
            return Map.of("valid", false, "reason", "key_" + key.getStatus());
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(OffsetDateTime.now())) {
            return Map.of("valid", false, "reason", "key_expired");
        }

        Optional<Tenant> tenant = tenantRepository.findById(key.getTenantId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", true);
        result.put("key_id", key.getExternalId());
        result.put("tenant_id", tenant.map(t -> t.getExternalId().toString()).orElse(null));
        result.put("permissions", Arrays.asList(key.getPermissions().split(",")));
        result.put("rate_limit_rpm", key.getRateLimitRpm() != null
            ? key.getRateLimitRpm()
            : tenant.map(Tenant::getRateLimitRpm).orElse(60_000));
        return result;
    }

    /** Revoke a key by external ID. */
    @Transactional
    public void revoke(UUID keyExternalId) {
        ApiKey key = apiKeyRepository.findByExternalId(keyExternalId)
            .orElseThrow(() -> new IllegalArgumentException("Key not found: " + keyExternalId));
        key.setStatus("revoked");
        apiKeyRepository.save(key);
        log.info("Revoked API key: {}", keyExternalId);
    }

    public List<ApiKey> listForTenant(UUID tenantExternalId) {
        Tenant tenant = tenantRepository.findByExternalId(tenantExternalId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantExternalId));
        return apiKeyRepository.findByTenantId(tenant.getId());
    }

    // -------------------------------------------------------------------------

    private String generateRawKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "sk-astra-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private void cacheKey(String rawKey, String tenantId, String permissions) {
        String value = String.format("{\"tenant_id\":\"%s\",\"permissions\":\"%s\"}", tenantId, permissions);
        redis.opsForValue().set("apikey:" + rawKey, value);
    }
}
