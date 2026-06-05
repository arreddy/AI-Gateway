package com.astra.gateway.a2a.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Mirrors the A2A protocol Task object. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class A2aTask {
    private String id;
    private String sessionId;
    private A2aTaskStatus status;
    private List<A2aArtifact> artifacts;
    private List<Map<String, Object>> history;
    private Map<String, Object> metadata;

    /** Convenience: extract the first text artifact, or null. */
    public String firstTextResult() {
        if (artifacts == null || artifacts.isEmpty()) return null;
        return artifacts.stream()
            .flatMap(a -> a.getParts() == null ? java.util.stream.Stream.empty()
                : a.getParts().stream())
            .filter(p -> "text".equals(p.get("type")))
            .map(p -> String.valueOf(p.get("text")))
            .findFirst().orElse(null);
    }
}
