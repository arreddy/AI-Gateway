package com.astra.gateway.a2a.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class A2aArtifact {
    private String name;
    private String description;
    private List<Map<String, Object>> parts;
}
