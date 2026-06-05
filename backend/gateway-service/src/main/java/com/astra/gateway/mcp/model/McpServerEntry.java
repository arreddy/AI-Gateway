package com.astra.gateway.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerEntry {
    private String id;
    private String name;
    private String url;          // e.g. http://my-mcp-server:3000
    private String description;
    @Builder.Default
    private String status = "registered";
    @Builder.Default
    private long registeredAt = Instant.now().toEpochMilli();
    private List<McpToolDefinition> cachedTools;
    private long toolsCachedAt;
}
