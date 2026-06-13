package com.astra.gateway.mcp.model;

import tools.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition {
    private String name;
    private String description;
    private JsonNode inputSchema;  // JSON Schema object
    private String serverId;       // which MCP server owns this tool
    private String serverUrl;
}
