# A2A Service Integration Guide

This guide explains how to integrate the A2A (Agent-to-Agent) Service with other services in the Astra Gateway.

## Overview

The A2A Service provides a robust communication infrastructure for inter-service coordination. It enables:

- **Agent Discovery**: Services can register themselves and discover other services
- **Point-to-Point Messaging**: Direct message routing between services
- **Publish-Subscribe Events**: Event broadcasting to interested subscribers
- **Task Distribution**: Work distribution across multiple agent instances
- **Health Monitoring**: Automatic health checks and failure detection

## Key Concepts

### Agent
An agent is any service that registers with the A2A Service. Each agent has:
- Unique ID
- Type (gateway, auth, mcp, etc.)
- Capabilities (list of things it can do)
- Endpoints (how to reach it)
- Region information
- Health status

### Messages
Point-to-point messages between two agents:
- Guaranteed delivery (via Kafka persistence)
- Automatic retries with exponential backoff
- Message ordering guarantees
- Timeout handling

### Events
Broadcast events published to multiple subscribers:
- Topic-based routing
- Fire-and-forget semantics
- Multiple subscribers can react to same event

### Tasks
Distributed work units assigned to agents:
- Can target multiple agents
- Track completion status
- Aggregate results
- Deadlines and priorities

## Integration Patterns

### Pattern 1: Service Registration

When a service starts, it should register with A2A:

```go
package main

import (
    "context"
    "log"
    "net/http"
)

func main() {
    // Service startup code...
    
    // Register with A2A Service
    registerWithA2A()
    
    // Start service...
}

func registerWithA2A() {
    client := &http.Client{}
    
    payload := map[string]interface{}{
        "agent_id": "gateway-1",
        "agent_type": "gateway",
        "capabilities": []string{"routing", "ratelimit", "auth"},
        "endpoints": map[string]string{
            "grpc": "gateway-1:50051",
            "http": "gateway-1:8080",
        },
        "region": "us-east-1",
        "metadata": map[string]string{
            "version": "1.0.0",
            "instance": "prod-1",
        },
    }
    
    body, _ := json.Marshal(payload)
    
    req, _ := http.NewRequest(
        "POST",
        "http://a2a-service:8082/v1/agents/register",
        bytes.NewBuffer(body),
    )
    req.Header.Set("Content-Type", "application/json")
    
    resp, err := client.Do(req)
    if err != nil {
        log.Fatal("Failed to register with A2A:", err)
    }
    defer resp.Body.Close()
    
    if resp.StatusCode != http.StatusCreated {
        log.Fatal("A2A registration failed:", resp.Status)
    }
    
    log.Println("Successfully registered with A2A Service")
}
```

### Pattern 2: Service Discovery

To find services by capability:

```go
func discoverMCPAgents() ([]AgentInfo, error) {
    resp, err := http.Get(
        "http://a2a-service:8082/v1/agents/search?" +
        "capability=tools&region=us-east-1",
    )
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()
    
    var result map[string]interface{}
    json.NewDecoder(resp.Body).Decode(&result)
    
    agents := result["agents"].([]interface{})
    
    // Use discovered agents...
    return agents, nil
}
```

### Pattern 3: Point-to-Point Messaging

Send a message to a specific agent and wait for response:

```go
// Gateway Service needs tool information from MCP Service
func requestToolsFromMCP(ctx context.Context, toolQuery string) ([]Tool, error) {
    // 1. Discover MCP agents
    mcpAgents, err := discoverMCPAgents()
    if err != nil {
        return nil, err
    }
    
    if len(mcpAgents) == 0 {
        return nil, errors.New("no MCP agents available")
    }
    
    // 2. Send message to first available MCP agent
    targetAgent := mcpAgents[0].(map[string]interface{})
    targetID := targetAgent["id"].(string)
    
    message := map[string]interface{}{
        "to_agent_id": targetID,
        "from_agent_id": "gateway-1",
        "message_type": "tool_discovery_request",
        "payload": map[string]string{
            "query": toolQuery,
        },
    }
    
    msgBody, _ := json.Marshal(message)
    req, _ := http.NewRequest(
        "POST",
        "http://a2a-service:8082/v1/messages/send",
        bytes.NewBuffer(msgBody),
    )
    req.Header.Set("Content-Type", "application/json")
    
    resp, _ := http.DefaultClient.Do(req)
    defer resp.Body.Close()
    
    var sendResp map[string]interface{}
    json.NewDecoder(resp.Body).Decode(&sendResp)
    
    messageID := sendResp["message_id"].(string)
    
    // 3. Wait for response (timeout after 5 seconds)
    startTime := time.Now()
    for {
        if time.Since(startTime) > 5*time.Second {
            return nil, errors.New("response timeout")
        }
        
        statusResp, _ := http.Get(
            fmt.Sprintf("http://a2a-service:8082/v1/messages/%s", messageID),
        )
        defer statusResp.Body.Close()
        
        var status map[string]interface{}
        json.NewDecoder(statusResp.Body).Decode(&status)
        
        if status["delivered"].(bool) {
            // Extract tools from response
            tools := status["response_body"].(map[string]interface{})["tools"]
            return tools, nil
        }
        
        time.Sleep(100 * time.Millisecond)
    }
}
```

### Pattern 4: Event Publishing

Publish events that other services can subscribe to:

```go
// When a new agent registers
func publishAgentRegistrationEvent(agentID string) {
    event := map[string]interface{}{
        "event_type": "agent_registered",
        "source_agent": "a2a-service",
        "payload": map[string]interface{}{
            "agent_id": agentID,
            "timestamp": time.Now().Unix(),
            "region": "us-east-1",
        },
        "topics": []string{
            "agents:registration",
            "agents:discovery",
        },
    }
    
    body, _ := json.Marshal(event)
    
    req, _ := http.NewRequest(
        "POST",
        "http://a2a-service:8082/v1/messages/publish",
        bytes.NewBuffer(body),
    )
    req.Header.Set("Content-Type", "application/json")
    
    http.DefaultClient.Do(req)
}

// Subscription handler in another service
func startEventListener() {
    for {
        resp, _ := http.Get(
            "http://a2a-service:8082/v1/messages/receive?" +
            "agent_id=auth-service-1",
        )
        
        var messages map[string]interface{}
        json.NewDecoder(resp.Body).Decode(&messages)
        
        for _, msg := range messages["messages"].([]interface{}) {
            handleEvent(msg.(map[string]interface{}))
        }
        
        resp.Body.Close()
        time.Sleep(1 * time.Second)
    }
}
```

### Pattern 5: Task Distribution

Distribute work across multiple agents:

```go
// Distribute a routing decision task
func distributeRoutingTask(providers []string) error {
    task := map[string]interface{}{
        "task_type": "provider_benchmark",
        "source_agent": "gateway-1",
        "target_agents": []string{
            "benchmark-1",
            "benchmark-2",
            "benchmark-3",
        },
        "payload": map[string]interface{}{
            "providers": providers,
            "timeout_seconds": 30,
        },
        "deadline": time.Now().Add(1*time.Minute).Unix(),
        "priority": 1, // High priority
    }
    
    body, _ := json.Marshal(task)
    
    req, _ := http.NewRequest(
        "POST",
        "http://a2a-service:8082/v1/tasks/distribute",
        bytes.NewBuffer(body),
    )
    req.Header.Set("Content-Type", "application/json")
    
    resp, _ := http.DefaultClient.Do(req)
    defer resp.Body.Close()
    
    var result map[string]interface{}
    json.NewDecoder(resp.Body).Decode(&result)
    
    taskID := result["task_id"].(string)
    
    // Poll for task completion
    for {
        statusResp, _ := http.Get(
            fmt.Sprintf("http://a2a-service:8082/v1/tasks/%s", taskID),
        )
        
        var status map[string]interface{}
        json.NewDecoder(statusResp.Body).Decode(&status)
        
        if status["status"] == "completed" {
            return handleBenchmarkResults(status["results"])
        }
        
        statusResp.Body.Close()
        time.Sleep(500 * time.Millisecond)
    }
}
```

## Integration with Existing Services

### Gateway Service Integration

The Gateway Service should use A2A for:
- Discovering available MCP servers
- Distributing routing decisions to multiple providers
- Publishing request events for observability
- Subscribing to governance policy updates

### MCP Service Integration

The MCP Service should use A2A for:
- Registering available tools and resources
- Receiving tool discovery requests from Gateway
- Publishing tool execution results
- Coordinating with other MCP agents

### Auth Service Integration

The Auth Service should use A2A for:
- Publishing token validation events
- Subscribing to key revocation events
- Coordinating cache invalidation across instances

## Heartbeat and Health Checks

Services should send periodic heartbeats to stay registered:

```go
func startHeartbeat(agentID string) {
    ticker := time.NewTicker(30 * time.Second)
    
    go func() {
        for range ticker.C {
            req, _ := http.NewRequest(
                "POST",
                fmt.Sprintf("http://a2a-service:8082/v1/agents/%s/health", agentID),
                nil,
            )
            
            http.DefaultClient.Do(req)
        }
    }()
}
```

## Error Handling

A2A Service provides robust error handling:

- Messages are persisted in Kafka
- Automatic retries with exponential backoff
- Dead-letter queue for permanently failed messages
- Message status tracking for debugging

Always check the message status and implement retry logic on the client side:

```go
func sendMessageWithRetry(message map[string]interface{}, maxRetries int) error {
    for attempt := 0; attempt < maxRetries; attempt++ {
        body, _ := json.Marshal(message)
        
        req, _ := http.NewRequest(
            "POST",
            "http://a2a-service:8082/v1/messages/send",
            bytes.NewBuffer(body),
        )
        req.Header.Set("Content-Type", "application/json")
        
        resp, err := http.DefaultClient.Do(req)
        if err != nil {
            continue
        }
        defer resp.Body.Close()
        
        if resp.StatusCode == http.StatusAccepted {
            return nil
        }
        
        backoff := time.Duration(math.Pow(2, float64(attempt))) * time.Second
        time.Sleep(backoff)
    }
    
    return errors.New("message send failed after retries")
}
```

## Performance Considerations

### Message Batching

For bulk operations, use the batch API:

```go
messages := []interface{}{
    map[string]interface{}{
        "to_agent_id": "mcp-1",
        "payload": map[string]string{"query": "find tools"},
    },
    // ... more messages
}

batch := map[string]interface{}{
    "messages": messages,
}

// Single HTTP request for multiple messages
sendBatch(batch)
```

### Connection Pooling

Reuse HTTP client instances:

```go
var httpClient = &http.Client{
    Timeout: 5 * time.Second,
    Transport: &http.Transport{
        MaxIdleConns: 100,
        MaxConnsPerHost: 100,
    },
}
```

### Caching Agent Discovery

Cache agent discovery results to reduce load on A2A Service:

```go
var agentCache = make(map[string][]AgentInfo)
var cacheMutex sync.Mutex
var cacheTTL = 1 * time.Minute

func getAgents(capability string) []AgentInfo {
    cacheMutex.Lock()
    defer cacheMutex.Unlock()
    
    // Return from cache if valid
    if agents, ok := agentCache[capability]; ok {
        return agents
    }
    
    // Query A2A Service
    agents := queryA2AService(capability)
    agentCache[capability] = agents
    
    // Invalidate cache after TTL
    time.AfterFunc(cacheTTL, func() {
        cacheMutex.Lock()
        delete(agentCache, capability)
        cacheMutex.Unlock()
    })
    
    return agents
}
```

## Monitoring and Observability

Track A2A Service metrics:

```bash
# Prometheus metrics available at:
http://localhost:9090

# Key metrics:
a2a_messages_sent_total
a2a_messages_delivered_total
a2a_messages_failed_total
a2a_message_latency_seconds
a2a_agents_registered
a2a_agents_healthy
```

Monitor message delivery:

```bash
# View message status
curl http://a2a-service:8082/v1/messages/{messageId}

# Check agent health
curl http://a2a-service:8082/v1/agents/{agentId}
```

## Troubleshooting

### Service Not Appearing in Discovery

1. Check agent registration:
```bash
curl http://a2a-service:8082/v1/agents
```

2. Verify capabilities match:
```bash
curl http://a2a-service:8082/v1/agents/search?capability=tools
```

3. Check heartbeat is being sent

### Message Delivery Failures

1. Check message status:
```bash
curl http://a2a-service:8082/v1/messages/{messageId}
```

2. View dead-letter queue (in Kafka):
```bash
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic a2a.dlq --from-beginning
```

3. Check A2A Service logs:
```bash
docker logs astra-a2a-service
```

### Performance Issues

1. Monitor A2A Service metrics in Prometheus
2. Check Kafka consumer lag
3. Verify Redis connection pool usage
4. Review message queue depths
