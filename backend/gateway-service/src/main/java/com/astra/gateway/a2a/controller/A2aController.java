package com.astra.gateway.a2a.controller;

import com.astra.gateway.a2a.model.A2aAgentEntry;
import com.astra.gateway.a2a.model.A2aTask;
import com.astra.gateway.a2a.service.A2aAgentRegistry;
import com.astra.gateway.a2a.service.A2aGatewayClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/a2a")
@RequiredArgsConstructor
public class A2aController {

    private final A2aAgentRegistry a2aRegistry;
    private final A2aGatewayClient a2aClient;

    // -------------------------------------------------------------------------
    // Agent management
    // -------------------------------------------------------------------------

    /**
     * Register an external A2A agent. Optionally auto-discovers the agent card.
     *
     * Body: { "name": "...", "url": "http://agent:8080", "description": "...", "discover": true }
     */
    @PostMapping("/agents")
    public ResponseEntity<A2aAgentEntry> register(@RequestBody Map<String, Object> body) {
        String name = (String) body.getOrDefault("name", "unnamed-agent");
        String url  = (String) body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        A2aAgentEntry entry = a2aRegistry.register(
            name, url,
            (String) body.get("description"),
            null, null
        );

        // Optionally auto-discover the agent card
        boolean discover = Boolean.TRUE.equals(body.get("discover"));
        if (discover) {
            entry = a2aClient.discoverCard(entry);
            // update registry with discovered skills/capabilities
            a2aRegistry.deregister(entry.getId());
            entry = a2aRegistry.register(
                entry.getName(), entry.getUrl(), entry.getDescription(),
                entry.getSkills(), entry.getCapabilities());
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    @GetMapping("/agents")
    public ResponseEntity<Object> list() {
        return ResponseEntity.ok(Map.of(
            "agents", a2aRegistry.listAll(),
            "count",  a2aRegistry.listAll().size()
        ));
    }

    @DeleteMapping("/agents/{id}")
    public ResponseEntity<Object> deregister(@PathVariable String id) {
        return a2aRegistry.deregister(id)
            ? ResponseEntity.ok(Map.of("status", "deregistered", "id", id))
            : ResponseEntity.notFound().build();
    }

    /** Fetch and update the agent card for a registered agent. */
    @PostMapping("/agents/{id}/discover")
    public ResponseEntity<Object> discover(@PathVariable String id) {
        return a2aRegistry.findById(id).map(entry -> {
            A2aAgentEntry updated = a2aClient.discoverCard(entry);
            // Re-register with discovered metadata
            a2aRegistry.deregister(id);
            A2aAgentEntry refreshed = a2aRegistry.register(
                updated.getName(), updated.getUrl(), updated.getDescription(),
                updated.getSkills(), updated.getCapabilities());
            return ResponseEntity.ok((Object) refreshed);
        }).orElse(ResponseEntity.notFound().build());
    }

    // -------------------------------------------------------------------------
    // Direct task delegation (bypasses LLM)
    // -------------------------------------------------------------------------

    /**
     * Send a task directly to an A2A agent and wait for completion.
     * Body: { "message": "Summarise this document: ..." }
     */
    @PostMapping("/agents/{id}/tasks")
    public ResponseEntity<Object> sendTask(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        return a2aRegistry.findById(id).map(agent -> {
            String message = body.get("message");
            if (message == null || message.isBlank()) {
                return ResponseEntity.badRequest().body((Object) Map.of("error", "message is required"));
            }
            try {
                log.info("Sending A2A task to agent: {} ({})", agent.getName(), agent.getUrl());
                A2aTask task = a2aClient.sendTaskAndWait(agent.getUrl(), message);
                return ResponseEntity.ok((Object) task);
            } catch (Exception e) {
                log.error("A2A task failed for agent {}: {}", id, e.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body((Object) Map.of("error", "Agent task failed", "detail", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Get the status of a previously submitted task. */
    @GetMapping("/agents/{id}/tasks/{taskId}")
    public ResponseEntity<Object> getTask(
            @PathVariable String id,
            @PathVariable String taskId) {

        return a2aRegistry.findById(id).map(agent -> {
            try {
                A2aTask task = a2aClient.getTask(agent.getUrl(), taskId);
                return ResponseEntity.ok((Object) task);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body((Object) Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Cancel an in-progress task. */
    @DeleteMapping("/agents/{id}/tasks/{taskId}")
    public ResponseEntity<Object> cancelTask(
            @PathVariable String id,
            @PathVariable String taskId) {

        return a2aRegistry.findById(id).map(agent -> {
            try {
                A2aTask task = a2aClient.cancelTask(agent.getUrl(), taskId);
                return ResponseEntity.ok((Object) task);
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body((Object) Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** List which A2A agent skills are currently exposed as LLM tools. */
    @GetMapping("/tools")
    public ResponseEntity<Object> listA2aTools() {
        List<Map<String, String>> tools = a2aRegistry.listAll().stream()
            .filter(a -> a.getSkills() != null && !a.getSkills().isEmpty())
            .flatMap(a -> a.getSkills().stream().map(skill -> Map.of(
                "tool_name",   A2aAgentRegistry.toolName(a.getId(), skill),
                "agent_name",  a.getName(),
                "agent_id",    a.getId(),
                "skill_id",    skill
            ))).toList();
        return ResponseEntity.ok(Map.of("tools", tools, "count", tools.size()));
    }
}
