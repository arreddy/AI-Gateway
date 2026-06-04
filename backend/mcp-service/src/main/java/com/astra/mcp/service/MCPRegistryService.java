package com.astra.mcp.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MCPRegistryService {

    private final ConcurrentHashMap<String, ServerEntry> servers = new ConcurrentHashMap<>();

    public void registerServer(String serverId, String endpoint, List<String> tools, List<String> resources) {
        List<String> toolList = tools != null ? new ArrayList<>(tools) : new ArrayList<>();
        List<String> resourceList = resources != null ? new ArrayList<>(resources) : new ArrayList<>();
        ServerEntry entry = new ServerEntry(serverId, endpoint, toolList, resourceList, System.currentTimeMillis());
        servers.put(serverId, entry);
        log.info("Registered MCP server: {} at {} with {} tools, {} resources",
            serverId, endpoint, toolList.size(), resourceList.size());
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> allTools = new ArrayList<>();
        servers.forEach((serverId, server) -> {
            for (String tool : server.tools) {
                allTools.add(Map.of(
                    "name", tool,
                    "server_id", serverId,
                    "endpoint", server.endpoint,
                    "description", "Tool provided by MCP server " + serverId,
                    "input_schema", Map.of("type", "object", "properties", Map.of())
                ));
            }
        });
        return allTools;
    }

    public List<Map<String, Object>> listResources() {
        List<Map<String, Object>> allResources = new ArrayList<>();
        servers.forEach((serverId, server) -> {
            for (String resource : server.resources) {
                allResources.add(Map.of(
                    "uri", resource,
                    "server_id", serverId,
                    "name", resource,
                    "mime_type", "text/plain"
                ));
            }
        });
        return allResources;
    }

    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        Optional<Map.Entry<String, ServerEntry>> serverOpt = servers.entrySet().stream()
            .filter(e -> e.getValue().tools.contains(toolName))
            .findFirst();

        if (serverOpt.isEmpty()) {
            return Map.of(
                "success", false,
                "error", "Tool not found: " + toolName,
                "available_tools", listTools().stream().map(t -> t.get("name")).toList()
            );
        }

        ServerEntry server = serverOpt.get().getValue();
        log.info("Routing tool call '{}' to server {} at {}", toolName, server.serverId, server.endpoint);

        // Real implementation would make an HTTP/SSE call to the MCP server endpoint
        return Map.of(
            "success", true,
            "tool_name", toolName,
            "server_id", server.serverId,
            "content", List.of(Map.of(
                "type", "text",
                "text", "Tool '" + toolName + "' executed on server '" + server.serverId + "'"
            )),
            "arguments_received", arguments != null ? arguments : Map.of()
        );
    }

    public Map<String, Object> getServerInfo(String serverId) {
        ServerEntry server = servers.get(serverId);
        if (server == null) return null;
        return Map.of(
            "server_id", server.serverId,
            "endpoint", server.endpoint,
            "tools", server.tools,
            "resources", server.resources,
            "registered_at", server.registeredAt
        );
    }

    record ServerEntry(String serverId, String endpoint, List<String> tools, List<String> resources, long registeredAt) {}
}
