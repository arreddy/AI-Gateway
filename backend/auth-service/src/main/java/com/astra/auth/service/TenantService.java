package com.astra.auth.service;

import com.astra.auth.entity.Tenant;
import com.astra.auth.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional
    public Tenant create(Map<String, Object> request) {
        String email = (String) request.get("email");
        String slug  = (String) request.get("slug");

        if (tenantRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered: " + email);
        }
        if (tenantRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("Slug already taken: " + slug);
        }

        Tenant tenant = new Tenant();
        tenant.setName((String) request.get("name"));
        tenant.setSlug(slug);
        tenant.setEmail(email);
        tenant.setStatus("active");

        if (request.containsKey("tier"))         tenant.setTier((String) request.get("tier"));
        if (request.containsKey("industry"))     tenant.setIndustry((String) request.get("industry"));
        if (request.containsKey("company_size")) tenant.setCompanySize((String) request.get("company_size"));
        if (request.containsKey("country"))      tenant.setCountry((String) request.get("country"));

        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant: {} ({})", saved.getSlug(), saved.getExternalId());
        return saved;
    }

    public Optional<Tenant> findById(UUID externalId) {
        return tenantRepository.findByExternalId(externalId);
    }

    public List<Tenant> findAll() {
        return tenantRepository.findAll();
    }

    @Transactional
    public Tenant updateStatus(UUID externalId, String status) {
        Tenant tenant = tenantRepository.findByExternalId(externalId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + externalId));
        tenant.setStatus(status);
        return tenantRepository.save(tenant);
    }

    @Transactional
    public Tenant updateTier(UUID externalId, String tier, Integer rateLimitRpm, Integer rateLimitTpm) {
        Tenant tenant = tenantRepository.findByExternalId(externalId)
            .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + externalId));
        tenant.setTier(tier);
        if (rateLimitRpm != null) tenant.setRateLimitRpm(rateLimitRpm);
        if (rateLimitTpm != null) tenant.setRateLimitTpm(rateLimitTpm);
        return tenantRepository.save(tenant);
    }
}
