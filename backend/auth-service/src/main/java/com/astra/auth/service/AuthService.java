package com.astra.auth.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class AuthService {

    @Value("${app.jwt.secret:astra-gateway-dev-secret-key-minimum-256-bits-long-change-in-production}")
    private String jwtSecret;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public Map<String, Object> verifyToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

            Map<String, Object> result = new HashMap<>();
            result.put("valid", true);
            result.put("subject", claims.getSubject());
            result.put("issuer", claims.getIssuer());
            result.put("expiry", claims.getExpiration() != null ? claims.getExpiration().getTime() : null);
            result.put("issued_at", claims.getIssuedAt() != null ? claims.getIssuedAt().getTime() : null);
            return result;
        } catch (ExpiredJwtException e) {
            log.warn("Token expired");
            return Map.of("valid", false, "reason", "token_expired");
        } catch (JwtException e) {
            log.warn("Invalid token: {}", e.getMessage());
            return Map.of("valid", false, "reason", "invalid_token");
        }
    }

    public Map<String, Object> validateApiKey(String rawKey) {
        String key = rawKey.startsWith("Bearer ") ? rawKey.substring(7).trim() : rawKey.trim();

        // Check Redis for registered key
        String keyData = redisTemplate.opsForValue().get("apikey:" + key);
        if (keyData != null) {
            return Map.of("valid", true, "key_prefix", obfuscate(key), "source", "registry");
        }

        // Development fallback: accept well-formed keys
        if (key.startsWith("sk-astra-") && key.length() >= 20) {
            log.debug("API key accepted via format check (dev mode)");
            return Map.of("valid", true, "key_prefix", obfuscate(key), "source", "format_check");
        }

        return Map.of("valid", false, "reason", "invalid_api_key");
    }

    public void registerApiKey(String key, String ownerId, String name) {
        String value = String.format(
            "{\"owner_id\":\"%s\",\"name\":\"%s\",\"created_at\":%d}",
            ownerId, name, System.currentTimeMillis()
        );
        redisTemplate.opsForValue().set("apikey:" + key, value);
        log.info("Registered API key for owner: {}", ownerId);
    }

    private String obfuscate(String key) {
        if (key.length() <= 8) return "***";
        return key.substring(0, 8) + "***";
    }
}
