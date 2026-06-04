# Implementation Guide: Building Astra Gateway

## Quick Start (5 minutes)

```bash
# 1. Clone and setup
git clone https://github.com/astra-gateway/astra-gateway.git
cd astra-gateway

# 2. Start local environment
docker-compose -f docker-compose.dev.yaml up -d

# 3. Create database schema
docker-compose exec postgres psql -U astra -d astra -f /docker-entrypoint-initdb.d/01-schema.sql

# 4. Test gateway
curl http://localhost:8080/health

# 5. Access dashboards
# Frontend: http://localhost:3000
# Grafana: http://localhost:3000 (credentials: admin/admin)
# Jaeger: http://localhost:16686
```

## Architecture Overview

### Request Flow

```
Client Request
  ↓
[API Gateway (Envoy)]
  ├─ TLS termination
  ├─ Rate limiting (DDoS)
  └─ Load balancing
  ↓
[Gateway Service - Go]
  ├─ Request validation
  ├─ Auth verification (call Auth Service)
  ├─ Cache lookup (Redis)
  ├─ Routing decision (call Routing Engine)
  ├─ Governance pre-checks (call Governance Engine)
  ├─ Provider call (OpenAI/Anthropic/etc.)
  ├─ Governance post-checks
  ├─ Metrics recording (call Observability Service)
  └─ Billing recording
  ↓
[Response to Client]
  ├─ Streaming (SSE/WebSocket)
  ├─ Metrics
  └─ Astra metadata
```

## Core Components

### 1. Gateway Service (Port 8080)
**Purpose:** Main API entry point
**Language:** Go
**Key Responsibilities:**
- OpenAI-compatible API endpoints
- Request validation
- Authentication delegation
- Load balancing across providers
- Streaming coordination
- Response composition

**Key Files:**
- `backend/gateway-service/main.go` - Entry point
- `backend/gateway-service/handlers/` - HTTP handlers
- `backend/gateway-service/middleware/` - Middleware stack

**Quick Implementation:**
```go
package main

import (
    "github.com/gin-gonic/gin"
)

func main() {
    router := gin.Default()
    
    // Health check
    router.GET("/health", func(c *gin.Context) {
        c.JSON(200, gin.H{"status": "healthy"})
    })
    
    // Chat completions (OpenAI compatible)
    router.POST("/v1/chat/completions", handleChatCompletion)
    
    // Start server
    router.Run(":8080")
}

func handleChatCompletion(c *gin.Context) {
    var req ChatCompletionRequest
    c.BindJSON(&req)
    
    // 1. Authenticate
    claims := authService.VerifyAPIKey(c.GetHeader("Authorization"))
    
    // 2. Check cache
    if cached := cache.Get(cacheKey(req)); cached != nil {
        c.JSON(200, cached)
        return
    }
    
    // 3. Route to provider
    provider := routingEngine.DecideRouting(req, claims)
    
    // 4. Call provider
    response, err := callProvider(provider, req)
    
    // 5. Record metrics
    observabilityService.RecordRequest(response)
    
    // 6. Return response
    c.JSON(200, response)
}
```

### 2. Auth Service (Port 9000)
**Purpose:** API key and JWT verification
**Language:** Go
**Key Responsibilities:**
- API key validation
- JWT verification
- Permission checking
- Claims caching

**Quick Implementation:**
```go
package main

func (a *AuthService) VerifyAPIKey(ctx context.Context, keyString string) (*Claims, error) {
    // 1. Hash the key
    keyHash := sha256(keyString)
    
    // 2. Check cache
    if cached := a.cache.Get(keyHash); cached != nil {
        return cached.(*Claims), nil
    }
    
    // 3. Lookup in database
    apiKey, err := a.db.GetAPIKeyByHash(ctx, keyHash)
    if err != nil || apiKey == nil {
        return nil, ErrInvalidKey
    }
    
    // 4. Verify not expired
    if time.Now().After(apiKey.ExpiresAt) {
        return nil, ErrKeyExpired
    }
    
    // 5. Build claims
    claims := &Claims{
        APIKeyID:    apiKey.ID,
        TenantID:    apiKey.TenantID,
        Permissions: apiKey.Permissions,
    }
    
    // 6. Cache for 5 minutes
    a.cache.Set(keyHash, claims, 5*time.Minute)
    
    return claims, nil
}
```

### 3. Routing Engine (Port 9000)
**Purpose:** Intelligent provider selection
**Language:** Go
**Key Responsibilities:**
- Cost-optimized routing
- Latency-optimized routing
- Quality-optimized routing
- Fallback chain management

**Quick Implementation:**
```go
func (r *RoutingEngine) DecideRouting(req *ChatCompletionRequest, claims *Claims) (*RoutingDecision, error) {
    // 1. Load provider metrics
    metrics := r.loadMetrics()
    
    // 2. Apply routing strategy
    decision := &RoutingDecision{}
    
    if req.RoutingHint.Strategy == "cost" {
        decision = r.selectCheapestProvider(metrics)
    } else if req.RoutingHint.Strategy == "latency" {
        decision = r.selectFastestProvider(metrics)
    } else {
        decision = r.selectDefaultProvider(metrics)
    }
    
    // 3. Apply constraints
    if decision.EstimatedCost > req.RoutingHint.MaxCost {
        decision.PrimaryProvider = decision.FallbackChain[0]
    }
    
    return decision, nil
}

func (r *RoutingEngine) selectCheapestProvider(metrics map[string]ProviderMetrics) *RoutingDecision {
    var cheapest string
    var lowestCost float64 = 999999
    
    for provider, metric := range metrics {
        if metric.HealthStatus == "healthy" && metric.Cost < lowestCost {
            lowestCost = metric.Cost
            cheapest = provider
        }
    }
    
    return &RoutingDecision{
        PrimaryProvider: cheapest,
        FallbackChain: []string{"openai", "anthropic", "mistral"},
        EstimatedCost: lowestCost,
    }
}
```

### 4. Governance Engine (Port 9000)
**Purpose:** Policy enforcement and security
**Language:** Go
**Key Responsibilities:**
- PII detection
- Toxicity filtering
- Prompt injection detection
- Policy enforcement

**Quick Implementation:**
```go
func (g *GovernanceEngine) CheckRequest(ctx context.Context, req *ChatCompletionRequest) error {
    content := combineMessages(req.Messages)
    
    // 1. Detect PII
    if pii := g.detectPII(content); len(pii) > 0 {
        g.logViolation(ctx, "pii_detected", pii)
        // Action: redact, block, or warn
    }
    
    // 2. Detect injection
    if injection := g.detectInjection(content); len(injection) > 0 {
        g.logViolation(ctx, "injection_detected", injection)
        return fmt.Errorf("prompt injection detected")
    }
    
    // 3. Score toxicity
    score := g.scoreToxicity(content)
    if score > 0.8 {
        g.logViolation(ctx, "toxicity_high", map[string]float64{"score": score})
    }
    
    return nil
}

func (g *GovernanceEngine) detectPII(content string) []string {
    patterns := map[string]string{
        "ssn": `\d{3}-\d{2}-\d{4}`,
        "credit_card": `\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}`,
        "email": `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-z]{2,}`,
    }
    
    var detected []string
    for piiType, pattern := range patterns {
        if matches := regexp.MustCompile(pattern).FindAllString(content, -1); len(matches) > 0 {
            detected = append(detected, piiType)
        }
    }
    return detected
}
```

### 5. Observability Service (Port 9000)
**Purpose:** Metrics, logging, and tracing
**Language:** Go
**Key Responsibilities:**
- Metrics collection (Prometheus)
- Event publishing (Kafka)
- Analytics (ClickHouse)
- Tracing (OpenTelemetry)

**Quick Implementation:**
```go
func (o *ObservabilityService) RecordRequest(ctx context.Context, metrics *RequestMetrics) error {
    // 1. Prometheus metrics
    o.requestCounter.WithLabelValues(
        metrics.Provider,
        metrics.Status,
    ).Inc()
    
    o.latencyHistogram.WithLabelValues(
        metrics.Provider,
    ).Observe(float64(metrics.LatencyMs))
    
    // 2. Publish to Kafka
    event := map[string]interface{}{
        "request_id": metrics.RequestID,
        "tenant_id": metrics.TenantID,
        "provider": metrics.Provider,
        "tokens": metrics.TotalTokens,
        "cost": metrics.Cost,
        "latency_ms": metrics.LatencyMs,
        "timestamp": time.Now().Unix(),
    }
    o.kafkaProducer.SendMessage(event)
    
    // 3. Write to ClickHouse
    o.clickhouseClient.Insert("usage_events", event)
    
    return nil
}
```

### 6. A2A Service (Port 8082)
**Purpose:** Agent-to-Agent communication and coordination
**Language:** Go
**Key Responsibilities:**
- Agent registration and discovery
- Point-to-point messaging
- Publish-Subscribe events
- Task distribution and coordination
- Agent health monitoring

**Architecture:**
```
┌─────────────────────────────┐
│      A2A Service            │
├─────────────────────────────┤
│  Agent Registry (Redis)     │
│  • Agent metadata           │
│  • Capability index         │
│  • Health status            │
├─────────────────────────────┤
│  Message Broker (Kafka)     │
│  • Point-to-point queue     │
│  • Pub-sub topics           │
│  • Task distribution        │
├─────────────────────────────┤
│  REST API & gRPC            │
│  • HTTP/REST endpoints      │
│  • gRPC services            │
│  • WebSocket streaming      │
└─────────────────────────────┘
```

**Key Endpoints:**
- `POST /v1/agents/register` - Register new agent
- `GET /v1/agents` - List all agents
- `GET /v1/agents/search?capability=routing` - Find agents by capability
- `POST /v1/messages/send` - Send point-to-point message
- `POST /v1/messages/publish` - Publish event to subscribers
- `GET /v1/messages/receive?agent_id=gateway-1` - Long-poll for messages
- `POST /v1/tasks/distribute` - Distribute task to agents
- `GET /v1/tasks/{taskId}` - Get task status

**Quick Implementation:**
```go
package main

import (
    "github.com/gin-gonic/gin"
    "github.com/go-redis/redis/v8"
)

func main() {
    // Initialize Redis registry
    redis := redis.NewClient(&redis.Options{
        Addr: "redis:6379",
    })
    
    registry := NewAgentRegistry(redis)
    broker := NewMessageBroker([]string{"kafka:29092"})
    
    // Setup HTTP server
    router := gin.Default()
    
    // Agent management
    router.POST("/v1/agents/register", func(c *gin.Context) {
        var req RegisterAgentRequest
        c.BindJSON(&req)
        
        // Register agent in registry
        registry.RegisterAgent(c.Request.Context(), req.AgentID, req)
        c.JSON(201, gin.H{"status": "registered"})
    })
    
    // Message sending
    router.POST("/v1/messages/send", func(c *gin.Context) {
        var req SendMessageRequest
        c.BindJSON(&req)
        
        // Route message to target agent
        msgID := uuid.New().String()
        broker.PublishMessage(c.Request.Context(), msgID, req)
        
        c.JSON(202, gin.H{
            "message_id": msgID,
            "status": "accepted",
        })
    })
    
    // Agent discovery
    router.GET("/v1/agents/search", func(c *gin.Context) {
        capability := c.Query("capability")
        region := c.Query("region")
        
        agents, _ := registry.SearchByCapability(
            c.Request.Context(), 
            capability, 
            region,
        )
        
        c.JSON(200, gin.H{
            "agents": agents,
            "count": len(agents),
        })
    })
    
    router.Run(":8082")
}
```

**Usage Example:**
```bash
# Register agent
curl -X POST http://localhost:8082/v1/agents/register \
  -H "Content-Type: application/json" \
  -d '{
    "agent_id": "mcp-1",
    "agent_type": "mcp",
    "capabilities": ["tools", "resources"],
    "endpoints": {
      "grpc": "mcp-service:50052",
      "http": "mcp-service:8081"
    },
    "region": "us-east-1"
  }'

# Send message to agent
curl -X POST http://localhost:8082/v1/messages/send \
  -H "Content-Type: application/json" \
  -d '{
    "to_agent_id": "mcp-1",
    "from_agent_id": "gateway-1",
    "message_type": "tool_discovery_request",
    "payload": {
      "query": "find all tools",
      "timestamp": 1704067200
    }
  }'

# Search for agents by capability
curl http://localhost:8082/v1/agents/search?capability=tools

# Publish event to all subscribers
curl -X POST http://localhost:8082/v1/messages/publish \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "agent_registered",
    "source_agent": "a2a-service",
    "payload": {
      "agent_id": "mcp-1",
      "timestamp": 1704067200
    }
  }'
```

## Database Schema

### Key Tables

**tenants** - Multi-tenant isolation
```sql
CREATE TABLE tenants (
    id BIGSERIAL PRIMARY KEY,
    external_id UUID UNIQUE,
    name VARCHAR(255),
    tier VARCHAR(50),  -- 'free', 'starter', 'pro', 'enterprise'
    rate_limit_rpm INT,
    rate_limit_tpm INT,
    created_at TIMESTAMP
);
```

**api_keys** - Authentication
```sql
CREATE TABLE api_keys (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES tenants,
    key_hash VARCHAR(255) UNIQUE,  -- Never plaintext
    status VARCHAR(50),
    permissions TEXT[],
    created_at TIMESTAMP,
    expires_at TIMESTAMP
);
```

**request_logs** - Immutable event log
```sql
CREATE TABLE request_logs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES tenants,
    api_key_id BIGINT REFERENCES api_keys,
    request_id UUID UNIQUE,
    model_requested VARCHAR(255),
    model_used VARCHAR(255),
    provider_id BIGINT,
    input_tokens INT,
    output_tokens INT,
    total_cost DECIMAL(15,8),
    latency_ms INT,
    status VARCHAR(50),
    created_at TIMESTAMP
);
```

## API Examples

### Example 1: Simple Chat Completion
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk_prod_xxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [
      {"role": "user", "content": "What is the capital of France?"}
    ]
  }'
```

**Response:**
```json
{
  "id": "chatcmpl-8Bjk4KzPx",
  "object": "chat.completion",
  "created": 1705334400,
  "model": "gpt-4",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "The capital of France is Paris."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 12,
    "total_tokens": 22,
    "cost_usd": 0.00045
  },
  "_astra": {
    "request_id": "req_123xyz",
    "provider": "openai",
    "latency_ms": 423,
    "cached": false,
    "cost": 0.00045,
    "fallback_count": 0
  }
}
```

### Example 2: Streaming Response
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk_prod_xxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [
      {"role": "user", "content": "Write a poem about AI"}
    ],
    "stream": true
  }'
```

**Response (SSE):**
```
data: {"id":"chatcmpl-8Bjk4KzPx","object":"chat.completion.chunk","created":1705334400,"model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":"Artificial"},"finish_reason":null}]}

data: {"id":"chatcmpl-8Bjk4KzPx","object":"chat.completion.chunk","created":1705334400,"model":"gpt-4","choices":[{"index":0,"delta":{"content":" intelligence"},"finish_reason":null}]}

data: [DONE]
```

### Example 3: Cost-Optimized Routing
```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk_prod_xxxxx" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4",
    "messages": [{"role": "user", "content": "Summarize this document"}],
    "routing": {
      "strategy": "cost",
      "constraints": {
        "max_cost_per_request": 0.10
      }
    }
  }'
```

## Testing

### Unit Tests
```bash
# Test auth service
go test ./backend/auth-service/... -v

# Test gateway service
go test ./backend/gateway-service/... -v

# With coverage
go test ./... -cover
```

### Integration Tests
```bash
# Start test environment
docker-compose -f docker-compose.test.yaml up -d

# Run integration tests
go test ./tests/integration/... -v

# Cleanup
docker-compose down
```

### Load Testing (k6)
```bash
# Install k6
brew install k6

# Run load test
k6 run scripts/load-test.js

# Expected results
# - 100k RPS capacity
# - P95 latency < 1000ms
# - Error rate < 0.1%
```

## Debugging

### View Logs
```bash
# Gateway logs
docker-compose logs -f gateway-service

# Auth service logs
docker-compose logs -f auth-service

# All logs
docker-compose logs -f
```

### Access Databases
```bash
# PostgreSQL
docker-compose exec postgres psql -U astra -d astra

# Redis CLI
docker-compose exec redis redis-cli

# ClickHouse
docker-compose exec clickhouse clickhouse-client
```

### Monitoring Dashboards
- **Grafana**: http://localhost:3000 (admin/admin)
- **Prometheus**: http://localhost:9090
- **Jaeger**: http://localhost:16686

### CPU/Memory Profiling
```go
import _ "net/http/pprof"

// Then access at:
// http://localhost:6060/debug/pprof/
// http://localhost:6060/debug/pprof/heap
// http://localhost:6060/debug/pprof/profile?seconds=30
```

## Deployment

### Docker Build
```bash
# Build gateway service
docker build -t astra-gateway:latest ./backend/gateway-service

# Tag and push
docker tag astra-gateway:latest docker.io/astragateway/gateway-service:latest
docker push docker.io/astragateway/gateway-service:latest
```

### Kubernetes Deployment
```bash
# Deploy core services
kubectl apply -f infrastructure/k8s/astra-core.yaml

# Deploy additional services
kubectl apply -f infrastructure/k8s/auth-service.yaml

# Verify
kubectl get pods -n astra
kubectl get svc -n astra

# Check logs
kubectl logs -n astra -l app=gateway-service -f
```

### Helm Charts
```bash
# Add Astra repo
helm repo add astra https://helm.astragateway.io
helm repo update

# Install
helm install astra-gateway astra/astra-gateway \
  --namespace astra \
  --values values-prod.yaml

# Upgrade
helm upgrade astra-gateway astra/astra-gateway \
  --namespace astra
```

## Next Steps

1. **Week 1-2:** Set up infrastructure and databases
2. **Week 3-4:** Implement gateway and auth services
3. **Week 5:** Add provider adapters
4. **Week 6:** Implement governance engine
5. **Week 7:** Add observability
6. **Week 8:** Deploy and test

Good luck building Astra Gateway! 🚀
