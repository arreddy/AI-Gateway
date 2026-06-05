package com.astra.auth.service;

import com.astra.auth.entity.ApiKey;
import com.astra.auth.entity.Tenant;
import com.astra.auth.repository.ApiKeyRepository;
import com.astra.auth.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        // lenient: only create/cache tests use Redis; other tests don't touch it
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        apiKeyService = new ApiKeyService(apiKeyRepository, tenantRepository, redis);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_success_returnsRawKeyOnce() {
        UUID tenantExtId = UUID.randomUUID();
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(sampleTenant(tenantExtId)));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setExternalId(UUID.randomUUID());
            k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        Map<String, Object> result = apiKeyService.create(tenantExtId, Map.of("name", "My Key"));

        assertThat(result.get("key").toString()).startsWith("sk-astra-");
        assertThat(result.get("name")).isEqualTo("My Key");
        assertThat(result.get("status")).isEqualTo("active");
    }

    @Test
    void create_tenantNotFound_throwsIllegalArgument() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.create(id, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tenant not found");
    }

    @Test
    void create_withPermissions_setsPermissions() {
        UUID tenantExtId = UUID.randomUUID();
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(sampleTenant(tenantExtId)));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setExternalId(UUID.randomUUID());
            k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        Map<String, Object> req = new HashMap<>();
        req.put("name", "Key with perms");
        req.put("permissions", List.of("completions:create", "models:list"));

        Map<String, Object> result = apiKeyService.create(tenantExtId, req);
        assertThat(result.get("permissions").toString()).contains("completions:create");
    }

    @Test
    void create_withExpiresAt_setsExpiry() {
        UUID tenantExtId = UUID.randomUUID();
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(sampleTenant(tenantExtId)));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setExternalId(UUID.randomUUID());
            k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        String expiry = OffsetDateTime.now().plusDays(30).toString();
        Map<String, Object> req = Map.of("name", "Expiring Key", "expires_at", expiry);
        Map<String, Object> result = apiKeyService.create(tenantExtId, req);
        assertThat(result.get("expires_at")).isNotNull();
    }

    @Test
    void create_cachesSavedKeyInRedis() {
        UUID tenantExtId = UUID.randomUUID();
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(sampleTenant(tenantExtId)));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            k.setExternalId(UUID.randomUUID());
            k.setCreatedAt(OffsetDateTime.now());
            return k;
        });

        apiKeyService.create(tenantExtId, Map.of("name", "Key"));
        verify(valueOps).set(startsWith("apikey:sk-astra-"), anyString());
    }

    // ── validate ──────────────────────────────────────────────────────────────

    @Test
    void validate_validKey_returnsValid() {
        String raw = "sk-astra-abc123";
        String hash = ApiKeyService.sha256(raw);
        ApiKey key = activeKey(hash);
        Tenant tenant = sampleTenant(UUID.randomUUID());
        key.setTenantId(tenant.getId());

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));

        Map<String, Object> result = apiKeyService.validate(raw);
        assertThat(result.get("valid")).isEqualTo(true);
    }

    @Test
    void validate_stripsBearer_prefix() {
        String raw = "sk-astra-abc123";
        String hash = ApiKeyService.sha256(raw);
        ApiKey key = activeKey(hash);
        Tenant tenant = sampleTenant(UUID.randomUUID());
        key.setTenantId(tenant.getId());

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));
        when(tenantRepository.findById(any())).thenReturn(Optional.of(tenant));

        Map<String, Object> result = apiKeyService.validate("Bearer " + raw);
        assertThat(result.get("valid")).isEqualTo(true);
    }

    @Test
    void validate_keyNotFound_returnsInvalid() {
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        Map<String, Object> result = apiKeyService.validate("sk-astra-unknown");
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("key_not_found");
    }

    @Test
    void validate_revokedKey_returnsInvalid() {
        String raw = "sk-astra-revoked";
        String hash = ApiKeyService.sha256(raw);
        ApiKey key = activeKey(hash);
        key.setStatus("revoked");

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));
        Map<String, Object> result = apiKeyService.validate(raw);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("key_revoked");
    }

    @Test
    void validate_expiredKey_returnsInvalid() {
        String raw = "sk-astra-expired";
        String hash = ApiKeyService.sha256(raw);
        ApiKey key = activeKey(hash);
        key.setExpiresAt(OffsetDateTime.now().minusHours(1));

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));
        Map<String, Object> result = apiKeyService.validate(raw);
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("reason")).isEqualTo("key_expired");
    }

    @Test
    void validate_futureExpiry_returnsValid() {
        String raw = "sk-astra-future";
        String hash = ApiKeyService.sha256(raw);
        ApiKey key = activeKey(hash);
        key.setExpiresAt(OffsetDateTime.now().plusDays(30));
        Tenant tenant = sampleTenant(UUID.randomUUID());
        key.setTenantId(tenant.getId());

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));
        when(tenantRepository.findById(any())).thenReturn(Optional.of(tenant));

        Map<String, Object> result = apiKeyService.validate(raw);
        assertThat(result.get("valid")).isEqualTo(true);
    }

    // ── revoke ────────────────────────────────────────────────────────────────

    @Test
    void revoke_success_setsStatusRevoked() {
        UUID extId = UUID.randomUUID();
        ApiKey key = activeKey("hash");
        when(apiKeyRepository.findByExternalId(extId)).thenReturn(Optional.of(key));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        apiKeyService.revoke(extId);
        assertThat(key.getStatus()).isEqualTo("revoked");
        verify(apiKeyRepository).save(key);
    }

    @Test
    void revoke_notFound_throws() {
        UUID extId = UUID.randomUUID();
        when(apiKeyRepository.findByExternalId(extId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.revoke(extId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Key not found");
    }

    // ── listForTenant ─────────────────────────────────────────────────────────

    @Test
    void listForTenant_success_returnsList() {
        UUID tenantExtId = UUID.randomUUID();
        Tenant tenant = sampleTenant(tenantExtId);
        when(tenantRepository.findByExternalId(tenantExtId)).thenReturn(Optional.of(tenant));
        when(apiKeyRepository.findByTenantId(tenant.getId())).thenReturn(List.of(activeKey("h1"), activeKey("h2")));

        List<ApiKey> result = apiKeyService.listForTenant(tenantExtId);
        assertThat(result).hasSize(2);
    }

    @Test
    void listForTenant_tenantNotFound_throws() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.listForTenant(id))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── sha256 ────────────────────────────────────────────────────────────────

    @Test
    void sha256_deterministicHash() {
        String h1 = ApiKeyService.sha256("test-input");
        String h2 = ApiKeyService.sha256("test-input");
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    void sha256_differentInputs_differentHashes() {
        assertThat(ApiKeyService.sha256("abc")).isNotEqualTo(ApiKeyService.sha256("xyz"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Tenant sampleTenant(UUID extId) {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setExternalId(extId);
        t.setSlug("test-tenant");
        t.setEmail("test@test.com");
        t.setStatus("active");
        return t;
    }

    private ApiKey activeKey(String hash) {
        ApiKey k = new ApiKey();
        k.setId(1L);
        k.setExternalId(UUID.randomUUID());
        k.setKeyHash(hash);
        k.setKeyPreview("preview8");
        k.setStatus("active");
        k.setPermissions("completions:create,models:list");
        k.setTenantId(1L);
        return k;
    }
}
