package com.astra.gateway.mcp.controller;

import com.astra.gateway.mcp.model.McpServerEntry;
import com.astra.gateway.mcp.model.McpToolDefinition;
import com.astra.gateway.mcp.service.McpGatewayClient;
import com.astra.gateway.mcp.service.McpServerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpServerRegistry registry;
    private final McpGatewayClient  mcpClient;

    /** Register an external MCP server with the gateway. */
    @PostMapping("/servers")
    public ResponseEntity<McpServerEntry> register(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "unnamed");
        String url  = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        McpServerEntry entry = registry.register(name, url, body.get("description"));
        return ResponseEntity.status(HttpStatus.CREATED).body(entry);
    }

    /** List all registered MCP servers. */
    @GetMapping("/servers")
    public ResponseEntity<Object> list() {
        return ResponseEntity.ok(Map.of(
            "servers", registry.listAll(),
            "count",   registry.listAll().size()
        ));
    }

    /** Remove a registered MCP server. */
    @DeleteMapping("/servers/{id}")
    public ResponseEntity<Object> deregister(@PathVariable String id) {
        return registry.deregister(id)
            ? ResponseEntity.ok(Map.of("status", "deregistered", "id", id))
            : ResponseEntity.notFound().build();
    }

    /** Discover and return tools from a specific registered server. */
    @PostMapping("/servers/{id}/discover")
    public ResponseEntity<Object> discover(@PathVariable String id) {
        return registry.findById(id).map(server -> {
            List<McpToolDefinition> tools = mcpClient.discoverTools(server.getUrl(), id);
            registry.cacheTools(id, tools);
            return ResponseEntity.ok((Object) Map.of("tools", tools, "count", tools.size()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** List cached tools across all registered servers. */
    @GetMapping("/tools")
    public ResponseEntity<Object> listTools() {
        List<McpToolDefinition> all = registry.listAll().stream()
            .flatMap(s -> registry.getCachedTools(s.getId()).stream())
            .toList();
        return ResponseEntity.ok(Map.of("tools", all, "count", all.size()));
    }

    /** Execute a tool directly (without going through the LLM). */
    @PostMapping("/tools/{toolName}/call")
    public ResponseEntity<Object> callTool(
            @PathVariable String toolName,
            @RequestBody(required = false) Map<String, Object> arguments) {
        return registry.findServerForTool(toolName).map(server -> {
            log.info("Direct tool call: {} on {}", toolName, server.getUrl());
            Object result = mcpClient.callTool(server.getUrl(), toolName,
                arguments != null ? arguments : Map.of());
            return ResponseEntity.ok(result);
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Tool not found: " + toolName)));
    }
}
