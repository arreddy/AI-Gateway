# Gateway Service - Core Entry Point

Service: `gateway-service`
Language: Go 1.21+
Purpose: Main request ingress, request routing, streaming coordination

## Responsibilities

1. **Request Validation & Transformation**
   - OpenAI-compatible API endpoint (/v1/chat/completions, etc.)
   - Request schema validation
   - Provider-specific transformation
   - Streaming setup coordination

2. **Authentication & Authorization**
   - API key extraction and validation (via Auth Service)
   - JWT token verification
   - Tenant context extraction
   - Permission checking

3. **Rate Limiting & Quotas**
   - Token-level rate limiting
   - Request-per-minute limits
   - Monthly quota enforcement
   - Concurrent request limits

4. **Request/Response Processing**
   - Request caching lookup
   - Streaming SSE/WebSocket coordination
   - Response composition
   - Token counting

5. **Error Handling & Fallback**
   - Provider failures
   - Fallback chain invocation
   - Circuit breaker management
   - Timeout handling

## Architecture

### Envoy Proxy Layer
```
Client
  ↓
[Envoy Proxy]
  ├─ TLS termination
  ├─ Rate limiting (DDoS protection)
  ├─ Load balancing across gateway pods
  └─ Request logging
  ↓
[Gateway Service - Go]
  ├─ API validation
  ├─ Business logic
  └─ Upstream service calls
```

### Handler Structure
```go
// Request flow through middleware stack
Request
  ↓
[Logger Middleware]
  ↓
[Auth Middleware] - extract API key, verify claims
  ↓
[Rate Limit Middleware] - token counting, quota checks
  ↓
[Governance Middleware] - pre-request checks
  ↓
[Cache Middleware] - check if response cached
  ↓
[Business Logic Handler]
  ├─ Route to provider
  ├─ Handle streaming
  └─ Record metrics
  ↓
[Response Middleware] - cleanup, logging
  ↓
Response
```

## Configuration

```yaml
# gateway-config.yaml
server:
  port: 8080
  read_timeout: 30s
  write_timeout: 30s
  max_body_size: 50mb

envoy:
  admin_port: 9901
  grpc_port: 9000

providers:
  timeout_ms: 30000
  max_connections: 10000
  connection_pool_size: 100

streaming:
  chunk_size_bytes: 4096
  write_timeout_ms: 5000

cache:
  redis_url: "redis://redis:6379"
  ttl_seconds: 3600
  max_entries: 1000000

services:
  auth_service: "grpc://auth-service:9000"
  routing_engine: "grpc://routing-engine:9000"
  governance_engine: "grpc://governance-engine:9000"
  observability_service: "grpc://observability-service:9000"
  provider_adapters: "grpc://provider-adapters:9000"
```

## Key Implementation Details

### Token Counting
- Input tokens: Count on request body
- Output tokens: Count on streaming response
- Cached responses: 0 tokens (cached flag set)
- Timeout/failures: Partial token count

### Streaming Response
```go
// SSE streaming
func (h *Handler) streamChatCompletion(w http.ResponseWriter, r *http.Request) {
    w.Header().Set("Content-Type", "text/event-stream")
    w.Header().Set("Cache-Control", "no-cache")
    w.Header().Set("Connection", "keep-alive")

    // Start provider stream
    stream, err := h.providerAdapter.Stream(r.Context(), req)
    
    // Stream chunks to client
    for chunk := range stream {
        // Apply governance checks
        processedChunk := h.governanceEngine.Check(chunk)
        
        // Write SSE format
        fmt.Fprintf(w, "data: %s\n\n", json.Marshal(processedChunk))
        w.Flush()
    }
}
```

### Request Caching
```go
// Cache key generation
cacheKey := hash.SHA256(model + messages + temperature + top_p + ...)

// Lookup
if cached := h.cache.Get(cacheKey); cached != nil {
    return cached, true
}

// After response
h.cache.Set(cacheKey, response, ttl)
```

### Error Handling
```go
// Circuit breaker pattern
status := h.circuitBreaker.Call(func() error {
    return h.providerAdapter.Call(req)
})

if status == CircuitBreakerOpen {
    // Use fallback provider from routing policy
    fallbackProvider := h.routingEngine.GetFallback()
    return h.providerAdapter.CallWithProvider(req, fallbackProvider)
}
```

## Deployment

See: `infrastructure/helm/astra-gateway/Chart.yaml`

```bash
# Local development
make dev

# Docker
docker build -t astra-gateway:latest .
docker run -p 8080:8080 astra-gateway:latest

# Kubernetes
helm install astra-gateway ./infrastructure/helm/astra-gateway
```

## Monitoring

- Metrics: Prometheus metrics on `:8080/metrics`
- Logging: Structured logs to stdout (picked up by ELK)
- Tracing: OpenTelemetry spans sent to Jaeger
- Health: `/health` and `/health/ready` endpoints
