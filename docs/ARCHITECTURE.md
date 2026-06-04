# System Architecture

## Overview

Astra Gateway is a cloud-native microservices platform built for enterprise AI workloads. It provides a unified control plane for LLM traffic management, governance, and observability.

## C4 Model Architecture

### Level 1: System Context

```
┌─────────────────────────────────────────────────────────────────────────┐
│                                                                           │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐     │
│  │  Mobile App      │  │  Web App         │  │  Backend Service │     │
│  │  (ChatGPT-like)  │  │  (Dashboard)     │  │  (LLM Integration)     │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘     │
│           │                     │                     │                │
│           └─────────────────────┼─────────────────────┘                │
│                                 │                                       │
│                                 ▼                                       │
│                      ┌──────────────────────┐                          │
│                      │  Astra Gateway       │                          │
│                      │  (OpenAI-Compatible  │                          │
│                      │   API Endpoint)      │                          │
│                      └──────────┬───────────┘                          │
│                                 │                                       │
│                    ┌────────────┼────────────┐                         │
│                    │            │            │                         │
│                    ▼            ▼            ▼                         │
│          ┌──────────────┐ ┌──────────────┐ ┌──────────────┐           │
│          │ OpenAI       │ │ Anthropic    │ │ Google       │           │
│          │ API          │ │ Claude API   │ │ Gemini API   │           │
│          └──────────────┘ └──────────────┘ └──────────────┘           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Level 2: Container Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Astra Gateway Platform                           │
│                                                                           │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │                     API Gateway (Envoy Proxy)                      │ │
│  │                                                                    │ │
│  │  ├─ Request Validation & Transformation                          │ │
│  │  ├─ Authentication & Authorization                              │ │
│  │  ├─ Rate Limiting & Quotas                                      │ │
│  │  ├─ Streaming Coordination                                      │ │
│  │  └─ Response Caching                                            │ │
│  └────────────────┬───────────────────────────────────────────────┘ │
│                   │                                                   │
│  ┌────────────────▼───────────────────────────────────────────────┐ │
│  │            Control Plane Services (gRPC + REST)               │ │
│  │                                                               │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │ │
│  │  │ Auth Service │  │ Routing      │  │ Governance   │       │ │
│  │  │              │  │ Engine       │  │ Engine       │       │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘       │ │
│  │                                                               │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │ │
│  │  │ Observability│  │ Billing      │  │ Webhook      │       │ │
│  │  │ Service      │  │ Service      │  │ Manager      │       │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘       │ │
│  │                                                               │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │ │
│  │  │ A2A Service  │  │ MCP Service  │  │ [Future]     │       │ │
│  │  │ (Agent-Agent)│  │ (MCP Client) │  │              │       │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘       │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐ │
│  │              Provider Adapter Layer (gRPC)                   │ │
│  │                                                               │ │
│  │  ├─ OpenAI Adapter      ├─ Google Adapter                   │ │
│  │  ├─ Anthropic Adapter   ├─ Mistral Adapter                  │ │
│  │  ├─ xAI Adapter         ├─ Groq Adapter                     │ │
│  │  └─ Local OSS Adapter   └─ Together AI Adapter              │ │
│  │                                                               │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
                    ▼            ▼            ▼
            ┌──────────────┐ ┌──────────┐ ┌──────────────┐
            │ PostgreSQL   │ │ Redis    │ │ ClickHouse   │
            │ Operational  │ │ Cache &  │ │ Analytics &  │
            │ Database     │ │ Sessions │ │ Events       │
            └──────────────┘ └──────────┘ └──────────────┘
                    │
                    ▼
            ┌──────────────────┐
            │ Kafka/RabbitMQ   │
            │ Event Bus        │
            └──────────────────┘
```

### Level 3: Component Breakdown

#### API Gateway Service
```go
// Request Flow
Client Request
    ↓
[Envoy Proxy]
    ├─ Rate Limiting Check
    ├─ Authentication
    └─ Request Transformation
    ↓
[Gateway Service - Go]
    ├─ Validation
    ├─ Auth Header Extraction
    ├─ Tenant Resolution
    └─ Streaming Setup
    ↓
[Routing Engine - gRPC]
    ├─ Provider Selection
    ├─ Fallback Chain
    └─ Load Balancing
    ↓
[Provider Adapter - gRPC]
    ├─ Provider Authentication
    ├─ Request Normalization
    └─ Stream Management
    ↓
[External LLM Provider]
    ↓
[Response Processing]
    ├─ Governance Checks
    ├─ Token Counting
    ├─ Caching Decision
    └─ Observability Recording
    ↓
Client Response (SSE/WebSocket)
```

#### Authentication Flow
```
API Key Request
    ↓
[API Gateway - Validation]
    ├─ Extract API Key from header
    └─ Quick cache lookup
    ↓
    Cache Hit? → [Return Cached Claims]
              ↓
    Cache Miss → [Auth Service - gRPC]
                 ├─ Verify signature
                 ├─ Check expiration
                 ├─ Validate permissions
                 └─ Cache result (TTL: 5 min)
    ↓
[Return Claims Object]
    ├─ tenant_id
    ├─ user_id
    ├─ permissions
    ├─ rate_limits
    └─ resource_quotas
```

#### Routing Decision Engine
```
Routing Request
    ├─ Model Name
    ├─ Provider Preferences
    ├─ Cost Constraints
    └─ Latency Requirements
    ↓
[Load Historical Metrics from Cache]
    ├─ Provider latencies
    ├─ Error rates
    ├─ Costs
    └─ Availability
    ↓
[Apply Routing Policy]
    ├─ Cost-Optimized
    │  └─ Select cheapest provider
    ├─ Latency-Optimized
    │  └─ Select fastest provider
    ├─ Quality-Optimized
    │  └─ Select highest accuracy
    ├─ Rule-Based
    │  └─ Apply custom DSL rules
    └─ Adaptive
       └─ Use ML model
    ↓
[Apply Constraints]
    ├─ Budget remaining?
    ├─ Rate limits?
    ├─ Geographic restrictions?
    └─ Data residency?
    ↓
[Build Fallback Chain]
    ├─ Primary: Selected provider
    ├─ Secondary: Backup provider
    └─ Tertiary: Last resort
    ↓
Return Provider Chain
```

#### Governance Pipeline
```
Request/Response Data
    ↓
[PII Detector]
    ├─ Scan for SSN, credit cards, etc.
    ├─ Redaction policy applied
    └─ Alert if threshold exceeded
    ↓
[Prompt Injection Detector]
    ├─ Pattern matching
    ├─ ML-based detection
    └─ Quarantine if suspicious
    ↓
[Toxicity Filter]
    ├─ Content classification
    ├─ Policy enforcement
    └─ Action (allow/block/redact)
    ↓
[Custom Policy Engine]
    ├─ DSL evaluation
    ├─ Conditional rules
    └─ Compliance checks
    ↓
[Audit Logger]
    ├─ Immutable event recording
    ├─ Compliance metadata
    └─ Incident tracking
    ↓
Return Processed Data
```

#### Observability Pipeline
```
Gateway Operations
    ├─ Request metadata
    ├─ Provider response
    ├─ Tokens used
    ├─ Latency
    ├─ Errors
    └─ Status codes
    ↓
[Metrics Collector]
    ├─ Prometheus metrics
    └─ Real-time dashboards
    ↓
[Event Stream]
    ├─ Publish to Kafka
    ├─ Partition by tenant_id
    └─ Retention: 7 days
    ↓
    ├─→ [Analytics Service] → [ClickHouse] → [Reports/Dashboards]
    ├─→ [Billing Service] → [Invoice Generation]
    ├─→ [Alerting Service] → [Alert Rules]
    └─→ [Tracing Service] → [Jaeger/Tempo] → [Debugging]
```

#### Agent-to-Agent (A2A) Communication Service
```
Inter-Agent Communication Architecture

Agent Registry & Discovery
    ├─ Agent metadata storage (Redis)
    ├─ Capability-based indexing
    ├─ Health monitoring
    └─ Load balancing
    ↓
Message Routing
    ├─ Point-to-point messaging
    │  ├─ Guaranteed delivery
    │  ├─ Automatic retries
    │  └─ Message ordering (per pair)
    ├─ Publish-Subscribe events
    │  ├─ Topic-based routing
    │  ├─ Subscriber notifications
    │  └─ Event streaming (Kafka)
    └─ Task Distribution
       ├─ Work queue management
       ├─ Agent assignment
       └─ Result aggregation
    ↓
Communication Patterns
    ├─ gRPC (high-performance, low-latency)
    │  └─ For inter-service communication
    ├─ REST/HTTP (webhooks, long-polling)
    │  └─ For external integrations
    └─ WebSocket (real-time streaming)
       └─ For live connections
    ↓
Security & Compliance
    ├─ mTLS authentication
    ├─ Message encryption
    ├─ Access control lists (ACLs)
    ├─ Rate limiting per agent pair
    └─ Audit logging
    ↓
Message Delivery
    ├─ Kafka topics for persistence
    │  ├─ a2a.messages (point-to-point)
    │  ├─ a2a.events (pub-sub)
    │  └─ a2a.tasks (work distribution)
    └─ Guaranteed at-least-once delivery
```

Agent Communication Flow Example:
```
[Gateway Service] needs to make async routing decision
    ↓
Publish message to MCP Service
    │
[A2A Service]
    ├─ Look up MCP Service agents by capability
    ├─ Select agent with lowest load
    ├─ Queue message to selected agent
    └─ Record message delivery status
    ↓
[MCP Service Agent 1]
    ├─ Receive message via long-poll
    ├─ Process tool discovery request
    ├─ Report result back to A2A Service
    └─ Delete from queue
    ↓
[Gateway Service]
    ├─ Poll for response
    ├─ Apply returned routing decision
    └─ Send LLM request to provider
```

## Data Flow Diagrams

### Streaming Response Flow
```
Client
  │
  ├─→ POST /chat/completions (with stream=true)
  │
Gateway
  ├─→ Validate request
  ├─→ Authenticate
  ├─→ Apply governance pre-checks
  ├─→ Determine provider
  │
Provider Adapter
  ├─→ Open streaming connection
  ├─→ Stream chunk 1 → [Governance Check] → [Token Count] → Client (SSE)
  ├─→ Stream chunk 2 → [Governance Check] → [Token Count] → Client (SSE)
  ├─→ Stream chunk N → [Governance Check] → [Token Count] → Client (SSE)
  ├─→ Stream complete
  │
Observability
  ├─→ Record aggregated metrics
  ├─→ Publish usage event
  ├─→ Update billing counter
  └─→ Trigger alerts if threshold exceeded
```

### Multi-Tenant Request Isolation
```
Tenant A Request
  ├─ API Key: key_a_xyz
  ├─ Route through Auth Service
  ├─ Extract tenant_id: tenant_a
  │
Tenant B Request (Concurrent)
  ├─ API Key: key_b_abc
  ├─ Route through Auth Service
  ├─ Extract tenant_id: tenant_b
  │
[Request Processing]
  ├─ All queries filtered by tenant_id
  ├─ Separate rate limit buckets
  ├─ Isolated cache keys
  ├─ Isolated quota tracking
  └─ Cross-tenant data leakage: IMPOSSIBLE

[Response]
  ├─ Tenant A gets only their data
  └─ Tenant B gets only their data
```

## Scalability Architecture

### Horizontal Scaling

**API Gateway Layer**
- Stateless services → unlimited horizontal scaling
- Load balancing with health checks
- Auto-scaling based on RPS and latency metrics

**Routing Engine**
- In-memory metrics cache (Redis) for decisions
- Async metric updates don't block requests
- ClickHouse for historical analysis

**Provider Adapters**
- Connection pooling to external providers
- Configurable concurrency limits
- Circuit breakers prevent cascade failures

**Data Layer**
- PostgreSQL read replicas for scaling reads
- Connection pooling (pgBouncer)
- Sharding by tenant_id for multi-region

### Multi-Region Deployment

```
┌──────────────────────────────────────────────────────────────────┐
│                     Global Control Plane                          │
│  (Single source of truth: Config, Policies, Rate Limits)         │
│  (Multi-region replicated PostgreSQL)                            │
└────────────────────────────────────────────────────────────────┬─┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
        ▼                        ▼                        ▼
    ┌────────────┐          ┌────────────┐          ┌────────────┐
    │ US-East    │          │ EU-West    │          │ AP-South   │
    │ Region     │          │ Region     │          │ Region     │
    │            │          │            │          │            │
    │ ├─ Gateway │          │ ├─ Gateway │          │ ├─ Gateway │
    │ ├─ Cache   │          │ ├─ Cache   │          │ ├─ Cache   │
    │ ├─ DB      │          │ ├─ DB      │          │ ├─ DB      │
    │ └─ Kafka   │          │ └─ Kafka   │          │ └─ Kafka   │
    │            │          │            │          │            │
    │ Latency: <50ms US users │ Latency: <50ms EU users │        │
    │                        │                        │            │
    └────────────┘          └────────────┘          └────────────┘
         │                       │                       │
         └───────────────────────┼───────────────────────┘
                                 │
                        ┌────────▼───────────┐
                        │ Cross-region Sync  │
                        │ (Eventually        │
                        │  Consistent)       │
                        └────────────────────┘
```

**Routing for Geo-Awareness**
```
Request from US User
  ├─ Route to nearest geographic region (US-East)
  ├─ Regional DB lookup
  ├─ Regional cache serving
  └─ ~15-30ms added latency vs direct to provider

Request from EU User
  ├─ Route to nearest geographic region (EU-West)
  ├─ Regional DB lookup
  ├─ Regional cache serving
  └─ ~15-30ms added latency vs direct to provider
```

## High Availability Design

### Service Redundancy
```
┌─────────────────────────────────────────────────┐
│           Load Balancer (Active-Active)         │
└──────────┬──────────────────────────┬───────────┘
           │                          │
      ┌────▼────┐              ┌──────▼──┐
      │ Gateway │              │ Gateway │
      │ Pod 1   │              │ Pod 2   │
      └────┬────┘              └──────┬──┘
           │                          │
      ┌────▼──────────────────────────▼──┐
      │   Service Mesh (Istio)            │
      │   ├─ Traffic management           │
      │   ├─ Fault injection testing      │
      │   ├─ Mutual TLS enforcement       │
      │   └─ Circuit breaker patterns     │
      └────┬──────────────────────────────┘
           │
      ┌────▼──────────────────────────┐
      │  Upstream Services (Replicated)│
      │  ├─ Auth (3 replicas)         │
      │  ├─ Routing (3 replicas)      │
      │  └─ Observability (3 replicas)│
      └───────────────────────────────┘
```

### Database High Availability
```
┌──────────────────────────────────────────┐
│     PostgreSQL Primary (Read/Write)      │
│     - Master node with WAL                │
│     - Continuous archiving               │
└────────────┬─────────────────────────────┘
             │
    ┌────────┼────────┐
    │        │        │
    ▼        ▼        ▼
┌────────┐┌──────┐┌────────┐
│Replica │Replica││Replica │
│ 1 (HotS│ 2 (Warm)   3 (Cold)
└────────┘└──────┘└────────┘

Failover: Primary → Replica 1 (0 downtime with pg_HA)
RPO: 0 (streaming replication)
RTO: <30 seconds
```

### Circuit Breaker Pattern

```
Provider Health Tracking
  │
  ├─ Success Rate < 90%? → HALF_OPEN
  ├─ Error Rate > 10%?   → OPEN (fallback)
  ├─ Latency > 5s?       → DEGRADE (queue)
  └─ Healthy            → CLOSED (pass-through)
  
When OPEN:
  ├─ Requests → [Fallback Provider]
  ├─ Periodically test provider
  ├─ When recovered → HALF_OPEN
  └─ Gradual traffic increase
```

## Security Architecture

### Defense in Depth

```
┌─────────────────────────────────────────────────────┐
│              Layer 1: Network Security              │
│  ├─ WAF (Web Application Firewall)                  │
│  ├─ DDoS Protection                                 │
│  ├─ VPC with security groups                        │
│  └─ TLS 1.3 for all communications                  │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│            Layer 2: Application Security            │
│  ├─ API Key validation                              │
│  ├─ JWT/OAuth2 token verification                   │
│  ├─ Request signature validation                    │
│  ├─ Input validation and sanitization               │
│  └─ Rate limiting per tenant                        │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│           Layer 3: Data Security                    │
│  ├─ Encryption at rest (AES-256)                    │
│  ├─ Encryption in transit (TLS 1.3)                 │
│  ├─ PII detection and redaction                     │
│  ├─ Tenant data isolation                           │
│  └─ Database role-based access control              │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│         Layer 4: Monitoring & Audit                 │
│  ├─ Immutable audit logs                            │
│  ├─ Security event monitoring                       │
│  ├─ Anomaly detection                               │
│  ├─ Compliance tracking (SOC2, GDPR)                │
│  └─ Regular security audits                         │
└─────────────────────────────────────────────────────┘
```

### Zero Trust Architecture

```
Every Request
  ├─ Verify identity (who are you?)
  │  └─ API Key, JWT, OAuth2
  │
  ├─ Verify device (are you trusted?)
  │  └─ mTLS certificates
  │
  ├─ Verify context (what are you doing?)
  │  └─ Resource, action, data sensitivity
  │
  └─ Make decision (allow, deny, challenge)
     └─ Least privilege access

Result: No implicit trust
        Every access is verified
        Everything is logged
```

## Cost Optimization Strategy

### Token Cost Minimization

```
Request arrives
  ├─ Calculate cost per provider/model combination
  ├─ [Cache Hit?] → Return cached response (0 cost)
  │   └─ Lexical cache: exact input match
  │   └─ Semantic cache: embedding similarity
  │
  ├─ [Response Available?] → Return stored response
  │   └─ Request deduplication
  │   └─ Response caching
  │
  ├─ [Select Provider]
  │   ├─ Cost-optimized routing
  │   │  └─ GPT-3.5 Turbo < GPT-4 < Claude Opus
  │   │
  │   └─ Quality-optimized routing
  │      └─ Always use best model for task
  │
  ├─ [Batch Processing?]
  │   └─ Queue requests for batching if possible
  │
  └─ [Execute Request]
     └─ Record actual token usage
     └─ Update cost tracking
     └─ Optimize for future requests
```

### Rate-Based Pricing

```
Provider List (cached, refreshed hourly):
  ├─ OpenAI GPT-4-8K: $0.03/$0.06 per 1K tokens
  ├─ Claude Opus: $0.015/$0.075 per 1K tokens
  ├─ Mistral Large: $0.007/$0.021 per 1K tokens
  └─ Groq (free tier): $0.00

Cost Calculator:
  Input tokens × Input rate
  + Output tokens × Output rate
  = Total cost

Optimization Engine:
  ├─ Same quality at 50% lower cost? → Switch
  ├─ Minimal quality loss at 40% savings? → Allow
  └─ Maintain quality at any cost? → Keep current
```

## Traffic Flow Examples

### Example 1: Simple Chat Completion

```
POST /v1/chat/completions
{
  "model": "gpt-4",
  "messages": [{"role": "user", "content": "Hello"}],
  "stream": false
}

[1] Gateway validates request
    ├─ Syntax validation ✓
    ├─ API key check ✓
    └─ Rate limit check ✓

[2] Auth service verifies key
    └─ Claims: {tenant: org_123, user: user_456}

[3] Routing engine selects provider
    └─ Model mapping: gpt-4 → openai
    └─ Health check: OpenAI healthy ✓
    └─ Selected: openai.gpt-4-turbo

[4] Governance checks
    ├─ No PII detected ✓
    ├─ No prompt injection ✓
    ├─ No toxicity ✓
    └─ Policy checks pass ✓

[5] Adapter calls provider
    └─ OpenAI API call with transformed request

[6] Provider responds
    └─ 500 tokens used

[7] Governance checks response
    ├─ No PII generated ✓
    ├─ Response moderation pass ✓
    └─ All checks pass ✓

[8] Observability records
    ├─ Event: completion_requested
    ├─ Provider: openai
    ├─ Tokens: 500 (input: 10, output: 490)
    ├─ Cost: $0.015
    ├─ Latency: 1234ms
    └─ Status: success

[9] Billing records
    ├─ Tenant: org_123
    ├─ Amount: $0.015
    ├─ Timestamp: 2024-01-15T10:30:00Z
    └─ Aggregated to invoice

[10] Response returned
     └─ 200 OK + response body
```

### Example 2: Streaming with Fallback

```
POST /v1/chat/completions?stream=true
{
  "model": "gpt-4",
  "messages": [...],
  "stream": true
}

[1-4] Same validation and governance

[5] Routing decides
    ├─ Primary: openai.gpt-4-turbo
    ├─ Secondary: anthropic.claude-3
    └─ Tertiary: mistral.large

[6] Try OpenAI
    ├─ Stream starts...
    ├─ Chunk 1: "The answer..." → [Governance] → Client (SSE)
    ├─ Chunk 2: "is 42" → [Governance] → Client (SSE)
    │
    ├─ OpenAI service returns 429 (rate limited)
    │
    ├─ Circuit breaker triggers
    └─ Fallback to Secondary provider

[7] Attempt Anthropic
    ├─ Stream starts...
    ├─ Chunk 1: "The answer..." → [Governance] → Client (SSE)
    ├─ Chunk 2: "is 42" → [Governance] → Client (SSE)
    └─ Stream completes successfully

[8] Observability records fallback event
    ├─ Primary failed: openai (429)
    ├─ Fallback succeeded: anthropic
    ├─ Total tokens: 500
    ├─ Cost: $0.025 (Claude is more expensive)
    └─ Latency: 2150ms

[9] Alert triggered
    ├─ OpenAI rate limit exceeded
    └─ Auto-scale or request quota increase
```

### Example 3: Cost-Optimized Routing

```
Request: "Summarize this document"

[Routing Engine Analysis]
├─ Input tokens: 5000
├─ Expected output: 500 tokens
│
├─ Option 1: GPT-4 ($0.015 + $0.06)
│  └─ Cost: ~$0.315
│  └─ Quality: Excellent
│
├─ Option 2: Claude 3 Opus ($0.015 + $0.075)
│  └─ Cost: ~$0.420
│  └─ Quality: Excellent
│
├─ Option 3: Mistral Large ($0.007 + $0.021)
│  └─ Cost: ~$0.145
│  └─ Quality: Very Good
│
└─ Option 4: GPT-3.5 Turbo ($0.003 + $0.006)
   └─ Cost: ~$0.018
   └─ Quality: Good

Decision Tree:
├─ User budget constraint? → Yes, max $0.20
├─ Quality constraint? → "Very Good" acceptable
├─ Historical success? → Mistral: 95% success rate
│
└─ Selected: Mistral Large
   └─ Cost: $0.145 (54% savings vs GPT-4)
   └─ Quality: Acceptable for task
   └─ Success rate: 95%
```

## Deployment Architecture

### Kubernetes Manifest Structure

```
kubernetes/
├── namespaces/
│   └─ astra.yaml (astra namespace, network policies)
│
├── core/
│   ├─ gateway-deployment.yaml
│   ├─ auth-service-deployment.yaml
│   └─ routing-engine-deployment.yaml
│
├── infrastructure/
│   ├─ postgresql-statefulset.yaml
│   ├─ redis-deployment.yaml
│   └─ kafka-statefulset.yaml
│
├── config/
│   ├─ configmaps/
│   │  ├─ gateway-config.yaml
│   │  └─ routing-rules.yaml
│   └─ secrets/
│      ├─ provider-credentials.yaml
│      └─ api-keys.yaml
│
├── networking/
│   ├─ service.yaml (ClusterIP)
│   ├─ ingress.yaml (HTTPS)
│   └─ network-policies.yaml
│
├── observability/
│   ├─ prometheus-servicemonitor.yaml
│   └─ grafana-dashboard-configmap.yaml
│
└── istio/
    ├─ virtualservice.yaml
    ├─ destinationrule.yaml
    └─ gateway.yaml
```

## Summary

This architecture provides:
- **Scalability**: Horizontal scaling at every layer
- **Reliability**: High availability with automatic failover
- **Security**: Defense in depth with zero trust
- **Observability**: Comprehensive monitoring and tracing
- **Cost**: Optimization through intelligent routing
- **Governance**: Policy enforcement and audit trails
- **Performance**: Sub-100ms additional latency
