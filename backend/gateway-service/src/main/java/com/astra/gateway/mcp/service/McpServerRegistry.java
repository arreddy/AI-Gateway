package com.astra.gateway.mcp.service;

import com.astra.gateway.mcp.entity.McpServerEntity;
import com.astra.gateway.mcp.model.McpServerEntry;
import com.astra.gateway.mcp.model.McpToolDefinition;
import com.astra.gateway.mcp.repository.McpServerJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP server registry.
 *
 * Storage strategy:
 *   Primary  → PostgreSQL (mcp_servers table) — authoritative, survives restarts
 *   Cache    → ConcurrentHashMap (in-memory) — write-through, rebuilt on startup
 *   Tool cache → in-memory only (derived from live discovery, TTL 5 min)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerRegistry {

    private static final long TOOL_CACHE_TTL_MS = 5 * 60 * 1_000L;

    private final McpServerJpaRepository db;

    // In-memory read cache — always consistent with DB via write-through
    private final ConcurrentHashMap<String, McpServerEntry> cache = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    @Transactional
    public McpServerEntry register(String name, String url, String description) {
        String id = UUID.randomUUID().toString();

        McpServerEntity entity = new McpServerEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setUrl(url);
        entity.setDescription(description);
        entity.setStatus("registered");
        db.save(entity);

        McpServerEntry entry = toEntry(entity);
        cache.put(id, entry);
        log.info("Registered MCP server: {} → {} (id={})", name, url, id);
        return entry;
    }

    @Transactional
    public boolean deregister(String id) {
        if (!db.existsById(id)) return false;
        db.deleteById(id);
        cache.remove(id);
        log.info("Deregistered MCP server: {}", id);
        return true;
    }

    public Collection<McpServerEntry> listAll() {
        return cache.values();
    }

    public Optional<McpServerEntry> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    // -------------------------------------------------------------------------
    // Tool cache (in-memory only — not persisted)
    // -------------------------------------------------------------------------

    public void cacheTools(String serverId, List<McpToolDefinition> tools) {
        cache.computeIfPresent(serverId, (k, entry) -> {
            entry.setCachedTools(tools);
            entry.setToolsCachedAt(System.currentTimeMillis());
            return entry;
        });
    }

    public List<McpToolDefinition> getCachedTools(String serverId) {
        McpServerEntry entry = cache.get(serverId);
        if (entry == null || entry.getCachedTools() == null) return List.of();
        if (System.currentTimeMillis() - entry.getToolsCachedAt() > TOOL_CACHE_TTL_MS) return List.of();
        return entry.getCachedTools();
    }

    public Optional<McpServerEntry> findServerForTool(String toolName) {
        return cache.values().stream()
            .filter(s -> s.getCachedTools() != null &&
                s.getCachedTools().stream().anyMatch(t -> t.getName().equals(toolName)))
            .findFirst();
    }

    // -------------------------------------------------------------------------
    // Startup: warm the in-memory cache from DB
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public void loadFromDatabase() {
        List<McpServerEntity> all = db.findAll();
        all.forEach(e -> cache.put(e.getId(), toEntry(e)));
        log.info("Loaded {} MCP servers from database", all.size());
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private static McpServerEntry toEntry(McpServerEntity e) {
        return McpServerEntry.builder()
            .id(e.getId())
            .name(e.getName())
            .url(e.getUrl())
            .description(e.getDescription())
            .status(e.getStatus())
            .registeredAt(e.getRegisteredAt() != null
                ? e.getRegisteredAt().toInstant().toEpochMilli()
                : Instant.now().toEpochMilli())
            .build();
    }
}
