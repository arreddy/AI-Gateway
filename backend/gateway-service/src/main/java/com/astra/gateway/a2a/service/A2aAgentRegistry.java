package com.astra.gateway.a2a.service;

import com.astra.gateway.a2a.entity.A2aAgentEntity;
import com.astra.gateway.a2a.model.A2aAgentEntry;
import com.astra.gateway.a2a.repository.A2aAgentJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A agent registry.
 *
 * Storage strategy:
 *   Primary  → PostgreSQL (a2a_agents table) — authoritative, survives restarts
 *   Cache    → ConcurrentHashMap (in-memory) — write-through, rebuilt on startup
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class A2aAgentRegistry {

    private final A2aAgentJpaRepository db;

    // In-memory read cache — always consistent with DB via write-through
    private final ConcurrentHashMap<String, A2aAgentEntry> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    @Transactional
    public A2aAgentEntry register(String name, String url, String description,
                                  List<String> skills, List<String> capabilities) {
        String id = UUID.randomUUID().toString();

        A2aAgentEntity entity = new A2aAgentEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setUrl(url);
        entity.setDescription(description);
        entity.setSkills(skills != null ? skills : List.of());
        entity.setCapabilities(capabilities != null ? capabilities : List.of());
        entity.setStatus("registered");
        db.save(entity);

        A2aAgentEntry entry = toEntry(entity);
        cache.put(id, entry);
        log.info("Registered A2A agent: {} → {} (id={})", name, url, id);
        return entry;
    }

    /** Update skills and capabilities after agent card discovery. */
    @Transactional
    public A2aAgentEntry updateDiscovered(String id, List<String> skills,
                                          List<String> capabilities, String description) {
        A2aAgentEntity entity = db.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + id));

        if (skills       != null) entity.setSkills(skills);
        if (capabilities != null) entity.setCapabilities(capabilities);
        if (description  != null) entity.setDescription(description);
        db.save(entity);

        A2aAgentEntry updated = toEntry(entity);
        cache.put(id, updated);
        log.info("Updated A2A agent discovery: {} — skills: {}", entity.getName(), skills);
        return updated;
    }

    @Transactional
    public boolean deregister(String id) {
        if (!db.existsById(id)) return false;
        db.deleteById(id);
        cache.remove(id);
        log.info("Deregistered A2A agent: {}", id);
        return true;
    }

    public Collection<A2aAgentEntry> listAll() {
        return cache.values();
    }

    public Optional<A2aAgentEntry> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public Optional<A2aAgentEntry> findBySkill(String skillId) {
        return cache.values().stream()
            .filter(a -> a.getSkills() != null && a.getSkills().contains(skillId))
            .findFirst();
    }

    // -------------------------------------------------------------------------
    // Tool name helpers
    // -------------------------------------------------------------------------

    public static String toolName(String agentId, String skillId) {
        return "a2a__" + agentId + "__" + skillId;
    }

    public static boolean isA2aTool(String toolName) {
        return toolName != null && toolName.startsWith("a2a__");
    }

    public static String[] parseA2aTool(String toolName) {
        if (!isA2aTool(toolName)) return null;
        String[] parts = toolName.split("__", 3);
        return parts.length == 3 ? new String[]{parts[1], parts[2]} : null;
    }

    // -------------------------------------------------------------------------
    // Startup: warm the in-memory cache from DB
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public void loadFromDatabase() {
        List<A2aAgentEntity> all = db.findAll();
        all.forEach(e -> cache.put(e.getId(), toEntry(e)));
        log.info("Loaded {} A2A agents from database", all.size());
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private static A2aAgentEntry toEntry(A2aAgentEntity e) {
        return A2aAgentEntry.builder()
            .id(e.getId())
            .name(e.getName())
            .url(e.getUrl())
            .description(e.getDescription())
            .skills(e.getSkills() != null ? e.getSkills() : List.of())
            .capabilities(e.getCapabilities() != null ? e.getCapabilities() : List.of())
            .status(e.getStatus())
            .registeredAt(e.getRegisteredAt() != null
                ? e.getRegisteredAt().toInstant().toEpochMilli()
                : Instant.now().toEpochMilli())
            .build();
    }
}
