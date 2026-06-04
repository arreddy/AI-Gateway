# A2A Service - Agent-to-Agent Communication

Service: `a2a-service`
Language: Go 1.21+
Purpose: Peer-to-peer communication framework for distributed agents

## Overview

The A2A Service provides a robust messaging and coordination layer for agents operating within the Astra Gateway ecosystem. It enables agents to discover each other, establish secure channels, and exchange messages asynchronously while maintaining high throughput and reliability.

## Responsibilities

### 1. Agent Discovery & Registration
- Agent self-registration with metadata (capabilities, regions, load)
- Dynamic service discovery
- Agent health monitoring and auto-deregistration
- Capability-based agent lookup
- Region-aware agent selection

### 2. Messaging Infrastructure
- Asynchronous message routing between agents
- Message queuing and persistence
- Message ordering guarantees (per-agent-pair)
- Message retry with exponential backoff
- Dead-letter queue for failed messages

### 3. Communication Patterns
- Request-Reply (RPC-style)
- Publish-Subscribe (event broadcasting)
- Task Distribution (work queues)
- Streaming (long-lived connections)
- Batch Processing (bulk message handling)

### 4. Security & Access Control
- Agent authentication (TLS certificates)
- Message encryption (in transit and at rest)
- Access control lists (ACLs)
- Rate limiting per agent pair
- Audit logging for all messages

### 5. Observability
- Message delivery metrics
- Agent health dashboard
- Message latency tracking
- Error rates and retry analytics
- Distributed tracing integration

## Architecture

### Component Diagram
```
┌──────────────────────────────────────────────────────────┐
│                    A2A Service                           │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │        Agent Registry & Discovery                  │ │
│  │  • Agent metadata store                            │ │
│  │  • Capability index                                │ │
│  │  • Health check manager                            │ │
│  │  • Load balancer                                   │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │        Message Router & Broker                     │ │
│  │  • Routing engine                                  │ │
│  │  • Queue management (Kafka)                        │ │
│  │  • Message ordering                                │ │
│  │  • Retry mechanism                                 │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │        API Endpoints                               │ │
│  │  • gRPC Service (high-performance)                 │ │
│  │  • REST API (webhooks, long-polling)               │ │
│  │  • WebSocket (real-time streaming)                 │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌────────────────────────────────────────────────────┐ │
│  │        Security & Auth                             │ │
│  │  • TLS/mTLS support                                │ │
│  │  • JWT validation                                  │ │
│  │  • API key management                              │ │
│  │  • Encryption service                              │ │
│  └────────────────────────────────────────────────────┘ │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## API Endpoints

### Agent Management
```
POST   /v1/agents/register      - Register new agent
DELETE /v1/agents/{agentId}     - Unregister agent
GET    /v1/agents               - List agents
GET    /v1/agents/{agentId}     - Get agent details
PATCH  /v1/agents/{agentId}     - Update agent metadata
GET    /v1/agents/search        - Search agents by capability
POST   /v1/agents/{agentId}/health - Heartbeat/health check
```

### Messaging
```
POST   /v1/messages/send        - Send message to agent
POST   /v1/messages/publish     - Publish event (pub-sub)
GET    /v1/messages/receive     - Long-poll for messages
POST   /v1/messages/batch       - Batch send messages
GET    /v1/messages/{msgId}     - Get message status
```

### Async Task Distribution
```
POST   /v1/tasks/distribute     - Distribute task to agents
GET    /v1/tasks/{taskId}       - Get task status
POST   /v1/tasks/{taskId}/result - Report task result
```

## Configuration

### Environment Variables
```bash
# Service Configuration
A2A_PORT=8082
A2A_GRPC_PORT=50051
A2A_ENV=development

# Database
A2A_DB_HOST=postgres
A2A_DB_PORT=5432
A2A_DB_NAME=astra
A2A_DB_USER=astra
A2A_DB_PASSWORD=astra_dev_password

# Message Broker
A2A_KAFKA_BROKERS=kafka:29092
A2A_KAFKA_TOPIC_MESSAGES=a2a.messages
A2A_KAFKA_TOPIC_EVENTS=a2a.events

# Cache
A2A_REDIS_URL=redis://redis:6379

# Security
A2A_TLS_ENABLED=false
A2A_TLS_CERT=/etc/a2a/tls/cert.pem
A2A_TLS_KEY=/etc/a2a/tls/key.pem

# Observability
A2A_JAEGER_ENABLED=true
A2A_JAEGER_ENDPOINT=http://jaeger:14268/api/traces

# Timeouts
A2A_MESSAGE_TIMEOUT=30s
A2A_HEALTH_CHECK_INTERVAL=10s
A2A_AGENT_TTL=5m
```

### Configuration File (agents.yaml)
```yaml
version: "1.0"
agents:
  gateway:
    capabilities:
      - routing
      - ratelimit
      - auth
    replicas: 3
    regions:
      - us-east-1
      - us-west-2
  
  mcp:
    capabilities:
      - tools
      - resources
    replicas: 2
    regions:
      - us-east-1
  
  auth:
    capabilities:
      - authentication
      - authorization
    replicas: 1
    regions:
      - us-east-1
```

## Usage Examples

### Register Agent
```bash
curl -X POST http://localhost:8082/v1/agents/register \
  -H "Content-Type: application/json" \
  -d '{
    "agent_id": "router-1",
    "agent_type": "router",
    "capabilities": ["routing", "ratelimit"],
    "region": "us-east-1",
    "endpoints": {
      "grpc": "router-1:50051",
      "http": "router-1:8080"
    },
    "metadata": {
      "version": "1.0.0"
    }
  }'
```

### Send Message to Agent
```bash
curl -X POST http://localhost:8082/v1/messages/send \
  -H "Content-Type: application/json" \
  -d '{
    "to_agent_id": "router-1",
    "from_agent_id": "auth-1",
    "message_type": "auth_request",
    "payload": {
      "token": "eyJhbGc...",
      "tenant_id": "tenant-123"
    },
    "timeout_ms": 5000
  }'
```

### Publish Event
```bash
curl -X POST http://localhost:8082/v1/messages/publish \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "agent_registered",
    "source_agent": "gateway-1",
    "payload": {
      "agent_id": "mcp-1",
      "timestamp": "2024-01-15T10:30:00Z"
    }
  }'
```

## Database Schema

Tables created in PostgreSQL:
- `agents` - Agent registry
- `messages` - Message storage and tracking
- `message_subscriptions` - Pub-Sub subscriptions
- `agent_metrics` - Agent performance metrics
- `audit_log` - All A2A operations

## Performance Characteristics

| Metric | Target |
|--------|--------|
| Agent Registration | < 100ms |
| Message Routing | < 50ms (p99) |
| Discovery Query | < 20ms (p99) |
| Pub-Sub Delivery | < 100ms (p99) |
| Throughput | 10K msg/sec per instance |
| Availability | 99.99% |

## Testing

```bash
# Unit tests
go test ./... -v

# Integration tests
docker-compose up -d
go test ./... -v -tags=integration

# Load testing
go run cmd/loadtest/main.go -agents=10 -rps=1000
```

## Integration Points

1. **Gateway Service** - Receives distributed routing decisions
2. **Auth Service** - Authenticates agent communications
3. **MCP Service** - Tool distribution and coordination
4. **Kafka** - Message persistence and streaming
5. **PostgreSQL** - Agent registry and message history
6. **Redis** - Session caching and fast lookups
7. **Observability** - Metrics, logs, and tracing

## Security Considerations

- All inter-agent communication should use mTLS in production
- Agent credentials should be rotated regularly
- Messages containing sensitive data should be encrypted
- Access control lists should be regularly audited
- Dead-letter queue should be monitored for suspicious patterns
