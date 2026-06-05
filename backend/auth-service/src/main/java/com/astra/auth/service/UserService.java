package com.astra.auth.service;

import com.astra.auth.config.AuthProperties;
import com.astra.auth.entity.Tenant;
import com.astra.auth.entity.User;
import com.astra.auth.repository.TenantRepository;
import com.astra.auth.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository     userRepository;
    private final TenantRepository   tenantRepository;
    private final AuthProperties     authProperties;

    @Transactional
    public Map<String, Object> register(UUID tenantExternalId, Map<String, Object> request) {
        Tenant tenant = tenantRepository.findByExternalId(tenantExternalId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantExternalId));

        String email = (String) request.get("email");
        if (userRepository.existsByTenantIdAndEmail(tenant.getId(), email)) {
            throw new IllegalArgumentException("Email already registered in this tenant: " + email);
        }

        User user = new User();
        user.setTenantId(tenant.getId());
        user.setEmail(email);
        user.setFirstName((String) request.get("first_name"));
        user.setLastName((String) request.get("last_name"));
        user.setPasswordHash(hashPassword((String) request.get("password")));
        user.setRole(request.containsKey("role") ? (String) request.get("role") : "member");

        User saved = userRepository.save(user);
        log.info("Registered user {} in tenant {}", email, tenant.getSlug());

        return toResponse(saved);
    }

    public Map<String, Object> login(Map<String, Object> request) {
        String email    = (String) request.get("email");
        String password = (String) request.get("password");
        String slug     = (String) request.get("tenant_slug");

        Tenant tenant = tenantRepository.findBySlug(slug)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + slug));

        User user = userRepository.findByTenantIdAndEmail(tenant.getId(), email)
            .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!hashPassword(password).equals(user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalArgumentException("Account is disabled");
        }

        // Update last login
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        String token = issueJwt(user, tenant);
        log.info("User {} logged in to tenant {}", email, slug);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("token", token);
        resp.put("user", toResponse(user));
        resp.put("tenant_id", tenant.getExternalId());
        return resp;
    }

    public List<User> listForTenant(UUID tenantExternalId) {
        Tenant tenant = tenantRepository.findByExternalId(tenantExternalId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));
        return userRepository.findByTenantId(tenant.getId());
    }

    // -------------------------------------------------------------------------

    private String issueJwt(User user, Tenant tenant) {
        SecretKey key = Keys.hmacShaKeyFor(
            authProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
            .subject(user.getExternalId().toString())
            .issuer("astra-gateway")
            .claim("email", user.getEmail())
            .claim("role", user.getRole())
            .claim("tenant_id", tenant.getExternalId().toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + authProperties.getJwt().getExpirationMs()))
            .signWith(key)
            .compact();
    }

    private String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private Map<String, Object> toResponse(User user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", user.getExternalId());
        m.put("email", user.getEmail());
        m.put("first_name", user.getFirstName());
        m.put("last_name", user.getLastName());
        m.put("role", user.getRole());
        m.put("is_active", user.getIsActive());
        m.put("created_at", user.getCreatedAt());
        return m;
    }
}
