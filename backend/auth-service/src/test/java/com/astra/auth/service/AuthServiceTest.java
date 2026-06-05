package com.astra.auth.service;

import com.astra.auth.config.AuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceTest {

    private static final String SECRET = "astra-gateway-dev-secret-key-minimum-256-bits-long-change-in-production";

    private AuthService authService;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setExpirationMs(86_400_000L);
        authService = new AuthService(props);
    }

    @Test
    void verifyToken_validToken_returnsValid() {
        String token = buildToken("user-123", 60_000L);
        Map<String, Object> result = authService.verifyToken(token);
        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("subject")).isEqualTo("user-123");
    }

    @Test
    void verifyToken_validToken_hasIssuer() {
        String token = buildToken("user-123", 60_000L);
        Map<String, Object> result = authService.verifyToken(token);
        assertThat(result.get("issuer")).isEqualTo("astra-gateway");
    }

    @Test
    void verifyToken_validToken_hasExpiry() {
        String token = buildToken("user-123", 60_000L);
        Map<String, Object> result = authService.verifyToken(token);
        assertThat(result.get("expiry")).isNotNull();
        assertThat(result.get("issued_at")).isNotNull();
    }

    @Test
    void verifyToken_expiredToken_returnsInvalidWithExpiredReason() {
        String token = buildToken("user-123", -1000L); // already expired
        Map<String, Object> result = authService.verifyToken(token);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("token_expired");
    }

    @Test
    void verifyToken_invalidSignature_returnsInvalidToken() {
        String token = buildTokenWithDifferentSecret("user-123");
        Map<String, Object> result = authService.verifyToken(token);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("invalid_token");
    }

    @Test
    void verifyToken_malformedToken_returnsInvalid() {
        Map<String, Object> result = authService.verifyToken("not.a.jwt.token.here");
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("invalid_token");
    }

    @Test
    void verifyToken_emptyString_returnsInvalid() {
        Map<String, Object> result = authService.verifyToken("");
        assertThat(result.get("valid")).isEqualTo(false);
    }

    private String buildToken(String subject, long expirationOffsetMs) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
            .subject(subject)
            .issuer("astra-gateway")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationOffsetMs))
            .signWith(key)
            .compact();
    }

    private String buildTokenWithDifferentSecret(String subject) {
        String otherSecret = "other-gateway-dev-secret-key-minimum-256-bits-long-different";
        SecretKey key = Keys.hmacShaKeyFor(otherSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
            .subject(subject)
            .issuer("astra-gateway")
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000L))
            .signWith(key)
            .compact();
    }
}
