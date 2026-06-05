package com.astra.auth.service;

import com.astra.auth.config.AuthProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthProperties authProperties;

    public Map<String, Object> verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return Map.of("valid", false, "reason", "invalid_token");
        }
        try {
            SecretKey key = Keys.hmacShaKeyFor(
                authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
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
}
