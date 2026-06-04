package com.astra.mcp.controller;

import com.astra.mcp.service.MCPRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/v1")
public class MCPController {

    @Autowired
    private MCPRegistryService registryService;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "mcp-service"));
    }

    @GetMapping("/tools/list")
    public ResponseEntity<Object> listTools() {
        log.info("Listing available tools");
        List<Map<String, Object>> tools = registryService.listTools();
        return ResponseEntity.ok(Map.of("tools", tools, "count", tools.size()));
    }

    @PostMapping("/tools/call")
    public ResponseEntity<Object> callTool(@RequestBody ToolRequest request) {
        log.info("Calling tool: {}", request.tool_name);
        Map<String, Object> result = registryService.callTool(request.tool_name, request.arguments);
        boolean success = Boolean.TRUE.equals(result.get("success"));
        return success ? ResponseEntity.ok(result) : ResponseEntity.status(404).body(result);
    }

    @GetMapping("/resources")
    public ResponseEntity<Object> listResources() {
        log.info("Listing available resources");
        List<Map<String, Object>> resources = registryService.listResources();
        return ResponseEntity.ok(Map.of("resources", resources, "count", resources.size()));
    }

    @PostMapping("/discovery/register")
    public ResponseEntity<Object> registerServer(@RequestBody ServerRegistration request) {
        log.info("Registering MCP server: {}", request.server_id);
        registryService.registerServer(request.server_id, request.endpoint, request.tools, request.resources);
        return ResponseEntity.status(201).body(Map.of(
            "server_id", request.server_id,
            "status", "registered",
            "tools_count", request.tools != null ? request.tools.size() : 0,
            "resources_count", request.resources != null ? request.resources.size() : 0
        ));
    }

    static class ToolRequest {
        public String tool_name;
        public Map<String, Object> arguments;
    }

    static class ServerRegistration {
        public String server_id;
        public String endpoint;
        public List<String> tools;
        public List<String> resources;
    }
}
