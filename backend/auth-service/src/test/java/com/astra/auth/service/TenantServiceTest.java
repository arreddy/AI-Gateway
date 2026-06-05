package com.astra.auth.service;

import com.astra.auth.entity.Tenant;
import com.astra.auth.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(tenantRepository);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_success_returnsSavedTenant() {
        when(tenantRepository.existsByEmail("acme@example.com")).thenReturn(false);
        when(tenantRepository.existsBySlug("acme")).thenReturn(false);
        Tenant saved = tenantWithId("acme", "acme@example.com");
        when(tenantRepository.save(any())).thenReturn(saved);

        Tenant result = tenantService.create(requestMap("acme@example.com", "acme", "Acme Corp"));
        assertThat(result.getSlug()).isEqualTo("acme");
        assertThat(result.getEmail()).isEqualTo("acme@example.com");
    }

    @Test
    void create_duplicateEmail_throwsIllegalArgument() {
        when(tenantRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.create(requestMap("dup@example.com", "new-slug", "Corp")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Email already registered");
    }

    @Test
    void create_duplicateSlug_throwsIllegalArgument() {
        when(tenantRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(tenantRepository.existsBySlug("taken-slug")).thenReturn(true);

        assertThatThrownBy(() -> tenantService.create(requestMap("new@example.com", "taken-slug", "Corp")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Slug already taken");
    }

    @Test
    void create_withOptionalFields_setsAllFields() {
        when(tenantRepository.existsByEmail(any())).thenReturn(false);
        when(tenantRepository.existsBySlug(any())).thenReturn(false);

        Map<String, Object> req = requestMap("t@t.com", "t-slug", "T Corp");
        req.put("tier", "enterprise");
        req.put("industry", "fintech");
        req.put("company_size", "large");
        req.put("country", "US");

        Tenant saved = tenantWithId("t-slug", "t@t.com");
        when(tenantRepository.save(any())).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });

        Tenant result = tenantService.create(req);
        assertThat(result.getTier()).isEqualTo("enterprise");
        assertThat(result.getIndustry()).isEqualTo("fintech");
        assertThat(result.getCompanySize()).isEqualTo("large");
        assertThat(result.getCountry()).isEqualTo("US");
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    void findById_existing_returnsPresent() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));

        Optional<Tenant> result = tenantService.findById(id);
        assertThat(result).isPresent();
    }

    @Test
    void findById_missing_returnsEmpty() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        Optional<Tenant> result = tenantService.findById(id);
        assertThat(result).isEmpty();
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_returnsList() {
        List<Tenant> tenants = List.of(tenantWithId("a", "a@a.com"), tenantWithId("b", "b@b.com"));
        when(tenantRepository.findAll()).thenReturn(tenants);

        List<Tenant> result = tenantService.findAll();
        assertThat(result).hasSize(2);
    }

    // ── updateStatus ──────────────────────────────────────────────────────────

    @Test
    void updateStatus_active_updatesStatus() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.updateStatus(id, "suspended");
        assertThat(result.getStatus()).isEqualTo("suspended");
    }

    @Test
    void updateStatus_deleted_setsDeletedAt() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.updateStatus(id, "deleted");
        assertThat(result.getDeletedAt()).isNotNull();
        assertThat(result.getStatus()).isEqualTo("deleted");
    }

    @Test
    void updateStatus_reactivated_clearsDeletedAt() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        t.setDeletedAt(OffsetDateTime.now());
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.updateStatus(id, "active");
        assertThat(result.getDeletedAt()).isNull();
    }

    @Test
    void updateStatus_alreadyDeletedAt_doesNotOverwrite() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        OffsetDateTime original = OffsetDateTime.now().minusDays(1);
        t.setDeletedAt(original);
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.updateStatus(id, "deleted");
        assertThat(result.getDeletedAt()).isEqualTo(original);
    }

    @Test
    void updateStatus_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.updateStatus(id, "active"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Tenant not found");
    }

    // ── updateTier ────────────────────────────────────────────────────────────

    @Test
    void updateTier_success_updatesTier() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.updateTier(id, "enterprise", 100_000, 200_000_000);
        assertThat(result.getTier()).isEqualTo("enterprise");
        assertThat(result.getRateLimitRpm()).isEqualTo(100_000);
        assertThat(result.getRateLimitTpm()).isEqualTo(200_000_000);
    }

    @Test
    void updateTier_nullLimits_doesNotOverwriteExisting() {
        UUID id = UUID.randomUUID();
        Tenant t = tenantWithId("slug", "e@e.com");
        t.setRateLimitRpm(999);
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.of(t));
        when(tenantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.updateTier(id, "pro", null, null);
        assertThat(result.getRateLimitRpm()).isEqualTo(999);
    }

    @Test
    void updateTier_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(tenantRepository.findByExternalId(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.updateTier(id, "pro", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> requestMap(String email, String slug, String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("email", email);
        m.put("slug", slug);
        m.put("name", name);
        return m;
    }

    private Tenant tenantWithId(String slug, String email) {
        Tenant t = new Tenant();
        t.setId(1L);
        t.setExternalId(UUID.randomUUID());
        t.setSlug(slug);
        t.setEmail(email);
        t.setName("Test Corp");
        t.setStatus("active");
        return t;
    }
}
