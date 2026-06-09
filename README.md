# Astra Gateway — Enterprise AI Gateway Platform

A production-grade, multi-tenant AI Gateway providing unified access to multiple LLM providers (Anthropic, OpenAI, Google) with intelligent routing, governance, observability, Agent-to-Agent (A2A) communication, Model Context Protocol (MCP) integration, and a Next.js management portal.

---

## What's Implemented

All backend microservices are fully implemented in **Java 21 + Spring Boot 3.3.0**, plus a **Next.js 14 management portal**:

| Service | Port | Description |
|---|---|---|
| **Gateway Service** | 8080 | OpenAI-compatible chat completion API, provider routing, Redis caching, MCP + A2A endpoints |
| **Auth Service** | 8083 | JWT verification (JJWT 0.12), API key validation, multi-tenant management (tenants, users, API keys) |
| **Routing Engine** | 8084 | Cost / latency / quality routing decisions with fallback chains; persistent routing policies |
| **Governance Engine** | 8085 | PII detection, prompt injection, toxicity filtering; persistent governance policies |
| **Observability Service** | 8086 | Request metrics recording and aggregation (Prometheus + Micrometer + ClickHouse) |
| **Management Portal** | 3001 | Next.js 14 dashboard — service health, tenants, API keys, observability, routing, governance, A2A, MCP |

**Infrastructure**: PostgreSQL 15 · Redis 7 · Kafka (Confluent 7.5) · Prometheus · Grafana · Jaeger · Loki · ClickHouse

---

## Architecture

```
┌────────────────────────────────────────────────────────────────┐
│                      Client Applications                        │
│              (OpenAI-compatible API clients)                    │
└───────────────────────────┬────────────────────────────────────┘
                            │ HTTP/SSE  :8080
┌───────────────────────────▼────────────────────────────────────┐
│                    Gateway Service                              │
│  POST /v1/chat/completions   GET /v1/models   GET /v1/health   │
│  /v1/mcp/*  (MCP proxy)      /v1/a2a/*  (A2A proxy)           │
│                                                                 │
│  ┌─ Provider Router ──────────────────────────────────────┐    │
│  │  claude-*  → Anthropic API                             │    │
│  │  gpt-*/o1-* → OpenAI API                              │    │
│  │  gemini-*  → Google Generative Language API            │    │
│  │  Anthropic↔OpenAI format conversion                    │    │
│  │  Redis response cache (5 min TTL, hash-keyed)          │    │
│  └────────────────────────────────────────────────────────┘    │
└───┬───────────────┬──────────────┬──────────────┬──────────────┘
    │               │              │              │
    ▼ :8083         ▼ :8084        ▼ :8085        ▼ :8086
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐
│  Auth    │  │ Routing  │  │Governance│  │Observability │
│ Service  │  │ Engine   │  │ Engine   │  │  Service     │
│          │  │          │  │          │  │              │
│ JWT      │  │ cost /   │  │ PII      │  │ Micrometer   │
│ verify   │  │ latency /│  │ injection│  │ counters     │
│ API key  │  │ quality  │  │ toxicity │  │ & timers     │
│ validate │  │ fallback │  │ policy   │  │ /metrics     │
│ (Redis)  │  │ chains   │  │ validate │  │ endpoint     │
│ Tenants  │  │ (Redis + │  │ (regex + │  │ (Prometheus  │
│ Users    │  │ Postgres)│  │ Postgres)│  │ + ClickHouse)│
│ API Keys │  │          │  │          │  │              │
│ (JPA/PG) │  │          │  │          │  │              │
└──────────┘  └──────────┘  └──────────┘  └──────────────┘

    ▼ :3001  Management Portal
┌─────────────────────────────────────────────────────────────────┐
│                     Next.js 14 Portal                           │
│                                                                 │
│  Dashboard · Tenants · API Keys · Observability                 │
│  Governance · Routing · A2A · MCP                               │
│  Real-time service health · Provider metrics                    │
└─────────────────────────────────────────────────────────────────┘

                  ┌─────────────────────────────┐
                  │      Shared Infrastructure   │
                  │                             │
                  │  PostgreSQL :5432           │
                  │  Redis      :6379           │
                  │  Kafka      :9092           │
                  │  Prometheus :9090           │
                  │  Grafana    :3000           │
                  │  Jaeger     :16686          │
                  │  Loki       :3100           │
                  │  ClickHouse :8123           │
                  └─────────────────────────────┘
```

---

## Technology Stack

### Backend
| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| Build | Maven (multi-module, parent POM) |
| REST | Spring MVC + Spring WebFlux (streaming) |
| HTTP Client | WebClient (provider calls) |
| Auth | JJWT 0.12.5 (HS256/384/512) |
| Messaging | Apache Kafka (Spring Kafka) |
| Cache / Registry | Redis (Spring Data Redis) |
| Database | PostgreSQL 15 (Spring Data JPA) |
| Metrics | Micrometer + Prometheus |
| Tracing | OpenTelemetry + Jaeger |
| Logging | SLF4J + Logback + Loki |

### Frontend
| Component | Technology |
|---|---|
| Framework | Next.js 14 (App Router) |
| Language | TypeScript |
| Data Fetching | TanStack Query v5 |
| Styling | Tailwind CSS v3 |
| Icons | Lucide React |
| Port | 3001 |

### Observability Stack
| Component | Port | Purpose |
|---|---|---|
| Prometheus | 9090 | Metrics scraping and storage |
| Grafana | 3000 | Dashboards |
| Jaeger | 16686 | Distributed tracing UI |
| Loki | 3100 | Log aggregation |
| ClickHouse | 8123 | Analytics events |

---

## Quick Start

### Prerequisites
- Docker 24+ and Docker Compose v2
- Java 21+ and Maven 3.9+ (for local builds only)
- `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, and/or `GOOGLE_API_KEY` (optional — services run without them)

### 1. Clone and configure

```bash
git clone https://github.com/astra-gateway/astra-gateway.git
cd AI-Gateway

# Copy and edit environment variables
cp .env.example .env
# Set ANTHROPIC_API_KEY, OPENAI_API_KEY, and/or GOOGLE_API_KEY in .env
```

### 2. Start all services

```bash
docker-compose -f docker-compose.dev.yaml up -d
```

### 3. Verify all services are healthy

```bash
docker-compose -f docker-compose.dev.yaml ps

# Quick health check across all backend services
for port in 8080 8083 8084 8085 8086; do
  echo -n "Port $port: "
  curl -s http://localhost:$port/actuator/health | grep -o '"status":"[^"]*"'
done
```

### 4. Open the management portal

```
http://localhost:3001
```

The dashboard shows real-time service health, provider metrics, active models, and tenant count.

### 5. Send your first request

```bash
# Chat completion (Anthropic)
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-astra-dev-key-1234567890" \
  -d '{
    "model": "claude-sonnet-4-6",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'

# Chat completion (Google Gemini)
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gemini-2.5-flash",
    "messages": [{"role": "user", "content": "Hello!"}]
  }'

# List available models
curl http://localhost:8080/v1/models

# Streaming (SSE)
curl -N -X POST "http://localhost:8080/v1/chat/completions?stream=true" \
  -H "Content-Type: application/json" \
  -d '{"model": "claude-sonnet-4-6", "messages": [{"role": "user", "content": "Count to 3"}]}'
```

### 6. Stop

```bash
docker-compose -f docker-compose.dev.yaml down
```

---

## Environment Variables

| Variable | Service | Default | Description |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | Gateway | _(empty)_ | Anthropic API key — enables `claude-*` models |
| `OPENAI_API_KEY` | Gateway | _(empty)_ | OpenAI API key — enables `gpt-*` / `o1-*` models |
| `GOOGLE_API_KEY` | Gateway | _(empty)_ | Google API key — enables `gemini-*` models |
| `JWT_SECRET` | Auth, Gateway | `astra-gateway-dev-secret...` | HS256 signing key (min 32 chars) |
| `SPRING_DATA_REDIS_HOST` | All | `redis` | Redis hostname |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Gateway, Routing, Governance, Observability | `kafka:29092` | Kafka broker |
| `SPRING_DATASOURCE_URL` | All | `jdbc:postgresql://postgres:5432/astra` | PostgreSQL URL |

---

## Supported Models

Models are surfaced automatically based on which API keys are configured:

| Provider | Models |
|---|---|
| **Anthropic** | `claude-opus-4-8`, `claude-sonnet-4-6`, `claude-haiku-4-5-20251001` |
| **OpenAI** | `gpt-4o`, `gpt-4o-mini`, `gpt-3.5-turbo` |
| **Google** | `gemini-2.5-pro`, `gemini-2.5-flash`, `gemini-2.0-flash-lite`, `gemini-3-pro-preview`, `gemini-3-flash-preview`, `gemini-3.1-pro-preview`, `gemini-3.1-flash-lite` |

---

## API Reference

Full spec: [`api-specs/openapi.yaml`](api-specs/openapi.yaml) (31 paths, 55 schemas, OpenAPI 3.0.3)

### Gateway Service — `http://localhost:8080/v1`

| Method | Path | Description |
|---|---|---|
| `POST` | `/chat/completions` | Chat completion (Anthropic, OpenAI, or Google) |
| `POST` | `/chat/completions?stream=true` | Streaming chat completion (SSE) |
| `GET` | `/models` | List configured provider models |
| `GET` | `/health` | Health check |

### Auth Service — `http://localhost:8083/v1`

#### JWT & API Key Validation
| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/verify` | Verify JWT token, returns decoded claims |
| `POST` | `/auth/api-key/validate` | Validate `Authorization: Bearer <key>` header |
| `POST` | `/auth/login` | Authenticate user, returns signed JWT |
| `GET` | `/health` | Health check |

#### Tenant Management
| Method | Path | Description |
|---|---|---|
| `POST` | `/tenants` | Create tenant |
| `GET` | `/tenants` | List all tenants |
| `GET` | `/tenants/{tenantId}` | Get tenant by ID |
| `PATCH` | `/tenants/{tenantId}/status` | Update tenant status |

#### User Management
| Method | Path | Description |
|---|---|---|
| `POST` | `/tenants/{tenantId}/users` | Register user for a tenant |
| `GET` | `/tenants/{tenantId}/users` | List users for a tenant |

#### API Key Management
| Method | Path | Description |
|---|---|---|
| `POST` | `/tenants/{tenantId}/api-keys` | Create API key for tenant (raw key returned once) |
| `GET` | `/tenants/{tenantId}/api-keys` | List API keys for tenant |
| `DELETE` | `/api-keys/{keyId}` | Revoke an API key |

```bash
# Create a tenant
curl -X POST http://localhost:8083/v1/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "slug": "acme", "email": "admin@acme.com", "tier": "pro"}'

# Create an API key for the tenant
curl -X POST http://localhost:8083/v1/tenants/<tenantId>/api-keys \
  -H "Content-Type: application/json" \
  -d '{"name": "production-key"}'
```

### Routing Engine — `http://localhost:8084/v1`

#### Routing Decisions
| Method | Path | Description |
|---|---|---|
| `POST` | `/routing/decide` | Get routing decision (provider + fallback chain) |
| `GET` | `/routing/metrics` | Provider performance metrics (cost, latency, quality, error rate) |
| `GET` | `/health` | Health check |

#### Routing Policies (persistent, per-tenant)
| Method | Path | Description |
|---|---|---|
| `POST` | `/routing-policies` | Create routing policy |
| `GET` | `/routing-policies/tenant/{tenantId}` | List active policies for tenant |
| `GET` | `/routing-policies/{id}` | Get policy by ID |
| `PUT` | `/routing-policies/{id}` | Update policy |
| `DELETE` | `/routing-policies/{id}` | Deactivate policy |

**Routing strategies**: `cost` (cheapest) · `latency` (fastest, adaptive) · `quality` (highest score)

### Governance Engine — `http://localhost:8085/v1`

#### Content Checks
| Method | Path | Description |
|---|---|---|
| `POST` | `/governance/check` | Run content through governance pipeline |
| `POST` | `/governance/policy/validate` | Validate a policy name |
| `GET` | `/health` | Health check |

#### Governance Policies (persistent, per-tenant)
| Method | Path | Description |
|---|---|---|
| `POST` | `/governance-policies` | Create governance policy |
| `GET` | `/governance-policies/tenant/{tenantId}` | List enabled policies for tenant |
| `GET` | `/governance-policies/{id}` | Get policy by ID |
| `PUT` | `/governance-policies/{id}` | Update policy |
| `PATCH` | `/governance-policies/{id}/disable` | Disable policy |

**Governance checks**: PII (email, phone, SSN, credit cards) · prompt injection (6 patterns) · toxicity (6 harm categories)

```bash
# Check a prompt for PII
curl -X POST http://localhost:8085/v1/governance/check \
  -H "Content-Type: application/json" \
  -d '{"content": "My SSN is 123-45-6789", "type": "prompt"}'
# → 422 with {"safe":false,"issues":["pii_detected"],"action":"block"}
```

### Observability Service — `http://localhost:8086/v1/observability`

| Method | Path | Description |
|---|---|---|
| `POST` | `/metrics/record` | Record latency + token usage for a request |
| `GET` | `/metrics` | Get aggregated per-provider stats |
| `GET` | `/health` | Health check |

Prometheus metrics also available at `http://localhost:8086/actuator/prometheus`.

### A2A Service — `http://localhost:8082/v1`

#### Agent Registry

| Method | Path | Description |
|---|---|---|
| `POST` | `/agents/register` | Register agent (Redis, 5-min TTL, capability/region indices) |
| `GET` | `/agents` | List all registered agents |
| `GET` | `/agents/search?capability=X&region=Y` | Search agents by capability and optional region |
| `GET` | `/agents/{agentId}` | Get single agent |
| `DELETE` | `/agents/{agentId}` | Unregister agent |

#### Messaging

| Method | Path | Description |
|---|---|---|
| `POST` | `/messages/send` | Push to agent's Redis queue + publish to `a2a.messages` Kafka topic |
| `POST` | `/messages/publish` | Publish event to `a2a.events` Kafka topic |
| `GET` | `/messages/receive?agent_id=X` | Pop up to 10 pending messages from Redis queue |

#### Tasks

| Method | Path | Description |
|---|---|---|
| `POST` | `/tasks/distribute` | Create task in Redis + fan out to target agents + publish to `a2a.tasks` |
| `GET` | `/tasks/{taskId}` | Get task record from Redis |

```bash
# Register an agent
curl -X POST http://localhost:8082/v1/agents/register \
  -H "Content-Type: application/json" \
  -d '{"agent_id":"agent-001","capabilities":["data_analysis"],"region":"us-east-1"}'

# Send it a message
curl -X POST http://localhost:8082/v1/messages/send \
  -H "Content-Type: application/json" \
  -d '{"from_agent_id":"orchestrator","to_agent_id":"agent-001","payload":{"task":"run"}}'

# Agent polls for messages
curl "http://localhost:8082/v1/messages/receive?agent_id=agent-001"
```

### MCP Service — `http://localhost:8081/v1`

| Method | Path | Description |
|---|---|---|
| `POST` | `/discovery/register` | Register an MCP server with its tools and resources |
| `GET` | `/tools/list` | List all tools from all registered servers |
| `POST` | `/tools/call` | Execute a tool (routes to the owning server) |
| `GET` | `/resources` | List all resources from all registered servers |
| `GET` | `/health` | Health check |

```bash
# Register an MCP server
curl -X POST http://localhost:8081/v1/discovery/register \
  -H "Content-Type: application/json" \
  -d '{
    "server_id": "filesystem",
    "endpoint": "http://mcp-fs:3000",
    "tools": ["read_file","write_file","list_dir"],
    "resources": ["file:///data"]
  }'

# Call a tool
curl -X POST http://localhost:8081/v1/tools/call \
  -H "Content-Type: application/json" \
  -d '{"tool_name": "read_file", "arguments": {"path": "/data/report.txt"}}'
```

---

## Project Structure

```
AI-Gateway/
├── api-specs/
│   └── openapi.yaml                    # OpenAPI 3.0.3 spec — 31 paths, 55 schemas
├── backend/
│   ├── pom.xml                         # Parent POM (Java 21, Spring Boot 3.3.0)
│   ├── gateway-service/                # :8080  Chat completions, provider routing, cache
│   │   └── src/main/java/com/astra/gateway/
│   │       ├── controller/GatewayController.java
│   │       ├── service/ProviderService.java          ← Anthropic + OpenAI + Google routing
│   │       ├── a2a/controller/A2aController.java     ← A2A proxy
│   │       ├── mcp/controller/McpController.java     ← MCP proxy
│   │       ├── client/{AuthClient,RoutingClient,ObservabilityClient}.java
│   │       └── interceptor/AuthInterceptor.java
│   ├── auth-service/                   # :8083  JWT + API key auth + tenant management
│   │   └── src/main/java/com/astra/auth/
│   │       ├── controller/{AuthController,TenantController,UserController,ApiKeyController}.java
│   │       ├── service/{AuthService,TenantService,UserService,ApiKeyService}.java
│   │       └── entity/{Tenant,User,ApiKey}.java      ← JPA entities
│   ├── routing-engine/                 # :8084  Cost/latency/quality routing + policy CRUD
│   │   └── src/main/java/com/astra/routing/
│   │       ├── controller/{RoutingController,RoutingPolicyController}.java
│   │       ├── service/RoutingService.java
│   │       └── entity/RoutingPolicy.java             ← Persistent routing policies
│   ├── governance-engine/              # :8085  Content safety + policy CRUD
│   │   └── src/main/java/com/astra/governance/
│   │       ├── controller/{GovernanceController,GovernancePolicyController}.java
│   │       ├── service/ContentGovernanceService.java
│   │       └── entity/GovernancePolicy.java          ← Persistent governance policies
│   └── observability-service/          # :8086  Metrics + ClickHouse events
│       └── src/main/java/com/astra/observability/
│           ├── controller/ObservabilityController.java
│           ├── service/{MetricsService,ClickHousePublisher}.java
│           └── model/GatewayMetricEvent.java
├── frontend/                           # :3001  Next.js 14 management portal
│   └── src/
│       ├── app/
│       │   ├── page.tsx                ← Dashboard (health, metrics, tenant count)
│       │   ├── tenants/                ← Tenant list + detail
│       │   ├── api-keys/               ← API key management
│       │   ├── observability/          ← Provider metrics
│       │   ├── governance/             ← Content governance
│       │   ├── routing/                ← Routing decisions + policies
│       │   ├── a2a/                    ← Agent-to-Agent registry
│       │   └── mcp/                    ← MCP tool registry
│       ├── components/
│       │   ├── layout/{Sidebar,ApiKeyWidget,Providers}.tsx
│       │   └── ui/{Badge,Button,Card,Modal,Table}.tsx
│       └── lib/{api,apiKey,utils}.ts
├── database/
│   ├── schema.sql                      # Full PostgreSQL schema (multi-tenant)
│   └── clickhouse-schema.sql           # ClickHouse analytics schema
├── docker-compose.dev.yaml             # All services + infrastructure
├── docker-compose.yml
├── docs/
│   ├── ARCHITECTURE.md
│   ├── SECURITY.md
│   ├── IMPLEMENTATION_GUIDE.md
│   ├── DEPLOYMENT.md
│   └── ROADMAP_AND_CHECKLIST.md
└── infrastructure/
    ├── k8s/astra-core.yaml
    ├── prometheus/prometheus.yml
    ├── grafana/provisioning/
    ├── loki/loki.yml
    └── promtail/promtail.yml
```

---

## Database Schema

The PostgreSQL schema (`database/schema.sql`) implements a full multi-tenant architecture:

| Table | Purpose |
|---|---|
| `tenants` | Tenant isolation — tier, rate limits, feature flags |
| `users` | Team members with role-based access (admin / member / viewer) |
| `api_keys` | Hashed API keys with per-key rate limits and permissions |
| `providers` | Provider catalog (Anthropic, OpenAI, Google) with health status |
| `models` | Model registry with pricing and performance metrics |
| `provider_credentials` | Per-tenant encrypted API keys |
| `routing_policies` | Persistent routing rules with strategy, fallback chain, constraints |
| `governance_policies` | Persistent content policies (PII, toxicity, injection) |
| `request_logs` | Immutable request event log with cost, tokens, governance flags |
| `token_usage_summary` | Pre-aggregated usage for billing |
| `billing_events` / `invoices` | Billing ledger |
| `audit_logs` | Compliance audit trail |
| `governance_violations` | Violation records linked to requests and policies |
| `mcp_servers` | Persistent MCP server registry |
| `a2a_agents` | Persistent A2A agent registry |

Includes materialized views (`daily_usage_summary`, `model_popularity`), auto-updating triggers, and row-level security hooks.

---

## Kafka Topics

| Topic | Producer | Consumer | Purpose |
|---|---|---|---|
| `a2a.messages` | A2A Service | A2A subscribers | Direct agent-to-agent messages |
| `a2a.events` | A2A Service | All services | Broadcast events (keyed by event type) |
| `a2a.tasks` | A2A Service | Worker agents | Distributed task queue |

---

## Governance Pipeline

Content submitted to `POST /v1/governance/check` passes through three stages in sequence:

```
Input content
     │
     ▼
┌─────────────────────────────────────────┐
│ 1. PII Detection                        │
│    Regex patterns: email, US phone,     │
│    SSN (###-##-####), Visa, MC, Amex   │
└──────────────────┬──────────────────────┘
                   │
     ▼ (type=prompt only)
┌─────────────────────────────────────────┐
│ 2. Prompt Injection Detection           │
│    "ignore previous instructions"      │
│    "jailbreak / DAN mode"              │
│    "pretend you are..."                │
│    + 3 more patterns                   │
└──────────────────┬──────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────┐
│ 3. Toxicity Detection                  │
│    Keywords: hate speech, self-harm,   │
│    explicit violence, terrorism, ...   │
└──────────────────┬──────────────────────┘
                   │
                   ▼
          safe=true → 200 allow
          safe=false → 422 block
```

---

## Routing Logic

```
POST /v1/routing/decide  { model, strategy }
         │
         ▼
  Resolve provider from model prefix
  claude-*   → anthropic
  gpt-*/o1-* → openai
  gemini-*   → google
  command-*  → cohere
         │
         ▼
  Rank remaining providers by strategy:
  cost     → sort by $cost/1k tokens (Cohere < OpenAI < Anthropic)
  latency  → sort by runtime avg latency (adaptive, Redis-cached)
  quality  → sort by quality score (Anthropic > OpenAI > Cohere)
         │
         ▼
  Return { selected_provider, fallback_chain[], constraints }
  Cache decision in Redis (60s TTL)
```

---

## Roadmap

### Phase 1 — Foundation ✅
- [x] Architecture design and API specification
- [x] Gateway Service with OpenAI-compatible API
- [x] Auth Service (JWT + API key)
- [x] Docker Compose dev environment

### Phase 2 — Multi-Provider ✅
- [x] Anthropic provider adapter (format conversion)
- [x] OpenAI provider adapter
- [x] Google Gemini provider adapter
- [x] Routing Engine (cost / latency / quality strategies)
- [x] Redis response caching in Gateway

### Phase 3 — Governance ✅
- [x] PII detection (email, phone, SSN, credit cards)
- [x] Prompt injection detection (6 patterns)
- [x] Toxicity filtering (6 harm categories)
- [x] Policy validation engine
- [x] Persistent governance policies (PostgreSQL)

### Phase 4 — Observability ✅
- [x] Metrics recording (latency + tokens per provider)
- [x] Prometheus/Micrometer integration
- [x] Grafana + Jaeger + Loki stack in docker-compose
- [x] Per-provider aggregated metrics endpoint
- [x] ClickHouse analytics events

### Phase 5 — A2A & MCP ✅
- [x] Agent registry (Redis, capability/region indices)
- [x] Agent messaging (Redis queues + Kafka)
- [x] Task distribution (Redis + Kafka fan-out)
- [x] MCP server registry (in-memory)
- [x] Tool listing, execution routing, resource listing

### Phase 6 — Multi-Tenant Management ✅
- [x] Tenant, User, API key CRUD (Auth Service)
- [x] Persistent routing policies (per-tenant)
- [x] Persistent governance policies (per-tenant)
- [x] Full PostgreSQL schema with billing, audit, and analytics tables
- [x] Frontend dashboard (Next.js 14)

### Phase 7 — Enterprise (Upcoming)
- [ ] Multi-region active-active deployment
- [ ] Billing / cost tracking service
- [ ] Fine-grained RBAC / ABAC enforcement
- [ ] Rate limiting (token-level, per-tenant)
- [ ] Custom routing DSL
- [ ] Persistent MCP server registry (PostgreSQL)
- [ ] Agent heartbeat / TTL refresh

---

## Security

- **JWT**: HS256/384/512 via JJWT 0.12.5; secret via `JWT_SECRET` env var (min 32 chars)
- **API Keys**: SHA-256 hashed in PostgreSQL; `sk-astra-*` format-check fallback for dev
- **TLS**: Configured at infrastructure level (Nginx / Kubernetes ingress)
- **Content Safety**: Governance engine blocks PII, injections, and toxic content before requests reach providers
- **Secrets**: All credentials injected via environment variables; no secrets in source code
- See [`docs/SECURITY.md`](docs/SECURITY.md) for full details

---

## Observability

| Signal | Stack | Endpoint |
|---|---|---|
| Metrics | Prometheus + Micrometer | `http://localhost:9090` |
| Dashboards | Grafana | `http://localhost:3000` (admin / admin) |
| Tracing | Jaeger (OTLP) | `http://localhost:16686` |
| Logs | Loki + Promtail | `http://localhost:3100` |
| Analytics | ClickHouse | `http://localhost:8123` |

Each service exposes `/actuator/health` and `/actuator/prometheus` (where configured).

---

## Contributing

See [`docs/IMPLEMENTATION_GUIDE.md`](docs/IMPLEMENTATION_GUIDE.md)

## License

Proprietary — contact sales@astragateway.io

## Support

- **Docs**: https://docs.astragateway.io
- **Enterprise**: support@astragateway.io
