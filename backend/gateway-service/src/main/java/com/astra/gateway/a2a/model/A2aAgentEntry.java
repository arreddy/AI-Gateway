package com.astra.gateway.a2a.model;

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
public class A2aAgentEntry {
    private String id;
    private String name;
    private String url;               // e.g. http://my-agent:8080
    private String description;
    private List<String> skills;      // skill IDs from the agent card
    private List<String> capabilities;// "streaming", "pushNotifications", etc.
    @Builder.Default
    private String status = "registered";
    @Builder.Default
    private long registeredAt = Instant.now().toEpochMilli();
}
