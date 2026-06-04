package com.astra.a2a.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import java.time.Duration;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/v1")
public class A2AController {
    private static final Duration AGENT_TTL = Duration.ofMinutes(5);
    private static final Duration QUEUE_TTL = Duration.ofMinutes(30);
    private static final Duration TASK_TTL = Duration.ofHours(1);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(Map.of("status", "healthy", "service", "a2a-service"));
    }

    @PostMapping("/agents/register")
    public ResponseEntity<Object> registerAgent(@RequestBody JsonNode request) {
        String agentId = request.get("agent_id").asText();
        log.info("Registering agent: {}", agentId);

        ObjectNode agentData = request.deepCopy();
        long now = System.currentTimeMillis() / 1000;
        agentData.put("registered_at", now);
        agentData.put("last_heartbeat", now);
        agentData.put("status", "active");

        try {
            redisTemplate.opsForValue().set("agent:" + agentId, objectMapper.writeValueAsString(agentData), AGENT_TTL);
            redisTemplate.opsForSet().add("agents:list", agentId);

            JsonNode capabilities = agentData.get("capabilities");
            if (capabilities != null && capabilities.isArray()) {
                capabilities.forEach(capability -> {
                    if (capability.isTextual()) {
                        redisTemplate.opsForSet().add("capability:" + capability.asText(), agentId);
                    }
                });
            }

            JsonNode region = agentData.get("region");
            if (region != null && region.isTextual() && !region.asText().isEmpty()) {
                redisTemplate.opsForSet().add("region:" + region.asText(), agentId);
            }
        } catch (Exception e) {
            log.error("Failed to store agent in Redis: {}", agentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to register agent"));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
            "agent_id", agentId,
            "status", "registered",
            "timestamp", System.currentTimeMillis()
        ));
    }

    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<Object> unregisterAgent(@PathVariable String agentId) {
        log.info("Unregistering agent: {}", agentId);

        String agentData = redisTemplate.opsForValue().get("agent:" + agentId);
        if (agentData == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            JsonNode agent = objectMapper.readTree(agentData);
            JsonNode capabilities = agent.get("capabilities");
            if (capabilities != null && capabilities.isArray()) {
                capabilities.forEach(cap -> {
                    if (cap.isTextual()) {
                        redisTemplate.opsForSet().remove("capability:" + cap.asText(), agentId);
                    }
                });
            }
            JsonNode region = agent.get("region");
            if (region != null && region.isTextual() && !region.asText().isEmpty()) {
                redisTemplate.opsForSet().remove("region:" + region.asText(), agentId);
            }
        } catch (Exception e) {
            log.warn("Error cleaning up indices for agent {}", agentId, e);
        }

        redisTemplate.opsForSet().remove("agents:list", agentId);
        redisTemplate.delete("agent:" + agentId);

        return ResponseEntity.ok(Map.of("agent_id", agentId, "status", "unregistered"));
    }

    @GetMapping("/agents")
    public ResponseEntity<Object> listAgents() {
        log.info("Listing all agents");

        Set<String> agentIds = redisTemplate.opsForSet().members("agents:list");
        List<Object> agentList = new ArrayList<>();
        if (agentIds != null) {
            for (String id : agentIds) {
                String data = redisTemplate.opsForValue().get("agent:" + id);
                if (data != null) {
                    try {
                        agentList.add(objectMapper.readTree(data));
                    } catch (Exception e) {
                        log.warn("Failed to parse agent data for {}", id);
                    }
                }
            }
        }

        return ResponseEntity.ok(Map.of("agents", agentList, "count", agentList.size()));
    }

    @GetMapping("/agents/{agentId}")
    public ResponseEntity<Object> getAgent(@PathVariable String agentId) {
        log.info("Getting agent: {}", agentId);

        String data = redisTemplate.opsForValue().get("agent:" + agentId);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(objectMapper.readTree(data));
        } catch (Exception e) {
            log.error("Failed to parse agent data for {}", agentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to retrieve agent"));
        }
    }

    @GetMapping("/agents/search")
    public ResponseEntity<Object> searchAgents(
            @RequestParam String capability,
            @RequestParam(required = false) String region) {
        log.info("Searching agents by capability: {}, region: {}", capability, region);

        Set<String> candidateIds = redisTemplate.opsForSet().members("capability:" + capability);
        if (candidateIds == null) candidateIds = new HashSet<>();

        if (region != null && !region.isEmpty()) {
            Set<String> regionAgents = redisTemplate.opsForSet().members("region:" + region);
            if (regionAgents != null) {
                candidateIds.retainAll(regionAgents);
            } else {
                candidateIds.clear();
            }
        }

        List<Object> results = new ArrayList<>();
        for (String id : candidateIds) {
            String data = redisTemplate.opsForValue().get("agent:" + id);
            if (data != null) {
                try {
                    results.add(objectMapper.readTree(data));
                } catch (Exception e) {
                    log.warn("Failed to parse agent {}", id);
                }
            }
        }

        return ResponseEntity.ok(Map.of("agents", results, "count", results.size()));
    }

    @PostMapping("/messages/send")
    public ResponseEntity<Object> sendMessage(@RequestBody JsonNode request) {
        String toAgent = request.has("to_agent_id") ? request.get("to_agent_id").asText() : null;
        log.info("Sending message to agent: {}", toAgent);

        String msgId = UUID.randomUUID().toString();
        ObjectNode enriched = request.deepCopy();
        enriched.put("message_id", msgId);
        enriched.put("timestamp", System.currentTimeMillis());

        try {
            String payload = objectMapper.writeValueAsString(enriched);
            if (toAgent != null) {
                redisTemplate.opsForList().leftPush("queue:" + toAgent, payload);
                redisTemplate.expire("queue:" + toAgent, QUEUE_TTL);
            }
            kafkaTemplate.send("a2a.messages", toAgent, payload);
        } catch (Exception e) {
            log.error("Failed to send message", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to send message"));
        }

        return ResponseEntity.accepted().body(Map.of("message_id", msgId, "status", "accepted"));
    }

    @PostMapping("/messages/publish")
    public ResponseEntity<Object> publishEvent(@RequestBody JsonNode request) {
        String eventType = request.has("event_type") ? request.get("event_type").asText() : "unknown";
        log.info("Publishing event: {}", eventType);

        String eventId = UUID.randomUUID().toString();
        ObjectNode enriched = request.deepCopy();
        enriched.put("event_id", eventId);
        enriched.put("timestamp", System.currentTimeMillis());

        try {
            kafkaTemplate.send("a2a.events", eventType, objectMapper.writeValueAsString(enriched));
        } catch (Exception e) {
            log.error("Failed to publish event", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to publish event"));
        }

        return ResponseEntity.accepted().body(Map.of("event_id", eventId, "status", "published"));
    }

    @GetMapping("/messages/receive")
    public ResponseEntity<Object> receiveMessages(@RequestParam String agent_id) {
        log.info("Receiving messages for agent: {}", agent_id);

        List<Object> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String msg = redisTemplate.opsForList().rightPop("queue:" + agent_id);
            if (msg == null) break;
            try {
                messages.add(objectMapper.readTree(msg));
            } catch (Exception e) {
                log.warn("Failed to parse message for agent {}", agent_id);
            }
        }

        return ResponseEntity.ok(Map.of("messages", messages, "count", messages.size()));
    }

    @PostMapping("/tasks/distribute")
    public ResponseEntity<Object> distributeTask(@RequestBody JsonNode request) {
        String taskType = request.has("task_type") ? request.get("task_type").asText() : "unknown";
        log.info("Distributing task: {}", taskType);

        String taskId = UUID.randomUUID().toString();
        ObjectNode taskData = request.deepCopy();
        taskData.put("task_id", taskId);
        taskData.put("created_at", System.currentTimeMillis());
        taskData.put("status", "distributed");

        try {
            String payload = objectMapper.writeValueAsString(taskData);
            redisTemplate.opsForValue().set("task:" + taskId, payload, TASK_TTL);

            JsonNode targetAgents = request.get("target_agents");
            if (targetAgents != null && targetAgents.isArray()) {
                targetAgents.forEach(agent -> {
                    try {
                        redisTemplate.opsForList().leftPush("queue:" + agent.asText(), payload);
                        redisTemplate.expire("queue:" + agent.asText(), QUEUE_TTL);
                    } catch (Exception e) {
                        log.warn("Failed to queue task for agent {}", agent.asText());
                    }
                });
            }

            kafkaTemplate.send("a2a.tasks", taskType, payload);
        } catch (Exception e) {
            log.error("Failed to distribute task", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to distribute task"));
        }

        return ResponseEntity.accepted().body(Map.of("task_id", taskId, "status", "distributed"));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Object> getTaskStatus(@PathVariable String taskId) {
        log.info("Getting task status: {}", taskId);

        String data = redisTemplate.opsForValue().get("task:" + taskId);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(objectMapper.readTree(data));
        } catch (Exception e) {
            log.error("Failed to parse task data for {}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to retrieve task"));
        }
    }
}
