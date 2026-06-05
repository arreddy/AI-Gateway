package com.astra.gateway.a2a.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class A2aTaskStatus {
    private String state;      // submitted | working | input-required | completed | failed | canceled
    private Map<String, Object> message;
    private String timestamp;
}
