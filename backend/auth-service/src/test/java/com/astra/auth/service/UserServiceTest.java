package com.astra.auth.service;

import com.astra.auth.config.AuthProperties;
import com.astra.auth.entity.Tenant;
import com.astra.auth.entity.User;
import com.astra.auth.repository.TenantRepository;
import com.astra.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository   userRepository;
    @Mock private TenantRepository tenantRepository;

    private UserService userService;

    private static final String SECRET = "astra-gateway-dev-secret-key-minimum-256-bits-long-change-in-production";

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setExpirationMs(86_400_000L);
        userService = new UserService(userRepository, tenantRepository, props);
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_success_returnsUserMap() {
        UUID tenantExtId = UUID.randomUUID();
        Tenant tenant = sampleTenant(tenantExtId, "acme");
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(tenant));
        when(userRepository.existsByTenantIdAndEmail(tenant.getId(), "alice@acme.com")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setExternalId(UUID.randomUUID());
            return u;
        });

        Map<String, Object> result = userService.register(tenantExtId, registerRequest("alice@acme.com", "Alice", "pass123"));
        assertThat(result.get("email")).isEqualTo("alice@acme.com");
        assertThat(result.get("role")).isEqualTo("member");
    }

    @Test
    void register_tenantNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.register(id, registerRequest("u@u.com", "U", "pass")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tenant not found");
    }

    @Test
    void register_duplicateEmail_throws() {
        UUID tenantExtId = UUID.randomUUID();
        Tenant tenant = sampleTenant(tenantExtId, "acme");
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(tenant));
        when(userRepository.existsByTenantIdAndEmail(tenant.getId(), "dup@acme.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.register(tenantExtId, registerRequest("dup@acme.com", "Dup", "pass")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Email already registered");
    }

    @Test
    void register_customRole_setsRole() {
        UUID tenantExtId = UUID.randomUUID();
        Tenant tenant = sampleTenant(tenantExtId, "acme");
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(tenant));
        when(userRepository.existsByTenantIdAndEmail(any(), any())).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setExternalId(UUID.randomUUID());
            return u;
        });

        Map<String, Object> req = registerRequest("admin@acme.com", "Admin", "pass");
        req.put("role", "admin");
        Map<String, Object> result = userService.register(tenantExtId, req);
        assertThat(result.get("role")).isEqualTo("admin");
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_success_returnsTokenAndUser() {
        Tenant tenant = sampleTenant(UUID.randomUUID(), "acme");
        User user = sampleUser(tenant, "alice@acme.com", hashPassword("secret123"));

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndEmail(tenant.getId(), "alice@acme.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenReturn(user);

        Map<String, Object> result = userService.login(loginRequest("alice@acme.com", "secret123", "acme"));
        assertThat(result.get("token")).isNotNull();
        assertThat(result.get("tenant_id")).isEqualTo(tenant.getExternalId());
        @SuppressWarnings("unchecked")
        Map<String, Object> userMap = (Map<String, Object>) result.get("user");
        assertThat(userMap.get("email")).isEqualTo("alice@acme.com");
    }

    @Test
    void login_tenantNotFound_throws() {
        when(tenantRepository.findBySlug("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(loginRequest("u@u.com", "pass", "nope")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tenant not found");
    }

    @Test
    void login_userNotFound_throws() {
        Tenant tenant = sampleTenant(UUID.randomUUID(), "acme");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndEmail(any(), eq("ghost@acme.com"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.login(loginRequest("ghost@acme.com", "pass", "acme")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid credentials");
    }

    @Test
    void login_wrongPassword_throws() {
        Tenant tenant = sampleTenant(UUID.randomUUID(), "acme");
        User user = sampleUser(tenant, "alice@acme.com", hashPassword("correct-pass"));

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndEmail(any(), any())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login(loginRequest("alice@acme.com", "wrong-pass", "acme")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid credentials");
    }

    @Test
    void login_disabledUser_throws() {
        Tenant tenant = sampleTenant(UUID.randomUUID(), "acme");
        User user = sampleUser(tenant, "alice@acme.com", hashPassword("pass"));
        user.setIsActive(false);

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantIdAndEmail(any(), any())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.login(loginRequest("alice@acme.com", "pass", "acme")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("disabled");
    }

    // ── listForTenant ─────────────────────────────────────────────────────────

    @Test
    void listForTenant_success_returnsList() {
        UUID tenantExtId = UUID.randomUUID();
        Tenant tenant = sampleTenant(tenantExtId, "acme");
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(tenant));
        when(userRepository.findByTenantId(tenant.getId())).thenReturn(List.of(new User(), new User()));

        List<User> result = userService.listForTenant(tenantExtId);
        assertThat(result).hasSize(2);
    }

    @Test
    void listForTenant_tenantNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.listForTenant(id))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> registerRequest(String email, String firstName, String password) {
        Map<String, Object> m = new HashMap<>();
        m.put("email", email);
        m.put("first_name", firstName);
        m.put("last_name", "Test");
        m.put("password", password);
        return m;
    }

    private Map<String, Object> loginRequest(String email, String password, String slug) {
        return Map.of("email", email, "password", password, "tenant_slug", slug);
    }

    private Tenant sampleTenant(UUID extId, String slug) {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setExternalId(extId);
        t.setSlug(slug);
        t.setEmail(slug + "@example.com");
        t.setStatus("active");
        return t;
    }

    private User sampleUser(Tenant tenant, String email, String passwordHash) {
        User u = new User();
        u.setId(1L);
        u.setExternalId(UUID.randomUUID());
        u.setTenantId(tenant.getId());
        u.setEmail(email);
        u.setPasswordHash(passwordHash);
        u.setIsActive(true);
        u.setRole("member");
        return u;
    }

    private String hashPassword(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
