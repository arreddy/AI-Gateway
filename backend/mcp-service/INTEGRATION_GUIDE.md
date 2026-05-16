# MCP Integration Guide

## Overview

This guide provides comprehensive instructions for integrating MCP (Model Context Protocol) servers with the Astra Gateway. MCP enables AI models to seamlessly access external tools and data sources through a standardized protocol.

## Quick Start

### 1. Prerequisites

- Go 1.21 or later
- PostgreSQL 14+
- Docker & Docker Compose (for containerized deployment)
- curl or similar HTTP client for testing

### 2. Local Development Setup

```bash
# Navigate to MCP service directory
cd backend/mcp-service

# Install dependencies
go mod download
go mod tidy

# Set up environment
cp .env.example .env
# Edit .env with your configuration

# Run migrations (if needed)
go run cmd/migrations/main.go

# Start the service
go run cmd/main.go
```

### 3. Docker Setup

```bash
# From project root
docker-compose -f docker-compose.dev.yaml up -d mcp-service

# Verify it's running
curl http://localhost:8055/health
```

## Architecture Integration

### 1. Discovery System

The MCP Service automatically discovers and registers MCP servers:

```
┌─────────────────────────────────────────┐
│     MCP Service                         │
│                                         │
│  ┌─────────────────────────────────┐  │
│  │ Discovery Manager               │  │
│  │                                 │  │
│  │ • Polls server registry         │  │
│  │ • Health checks                 │  │
│  │ • Capability introspection      │  │
│  │ • Dynamic registration          │  │
│  └────────┬────────────────────────┘  │
└───────────┼──────────────────────────┘
            │
            ▼
    ┌───────────────────┐
    │ PostgreSQL        │
    │ (Server Registry) │
    └───────────────────┘
```

### 2. Tool Execution Flow

```
Client Request
    ↓
Gateway Service
    ↓
[Tool Needed?]
    ↓
MCP Service
    ├─ Resolve server for tool
    ├─ Route to appropriate server
    ├─ Execute tool
    ├─ Cache results
    └─ Return to gateway
    ↓
Gateway returns response to client
```

### 3. Integration Points

#### A. With Gateway Service

The Gateway Service uses MCP tools for:

- **Request Processing**: Enhance requests with tool results
- **Response Generation**: Use tools to gather data for responses
- **Context Enrichment**: Add external data to LLM context

```go
// Example: Gateway injecting tools into LLM
tools, _ := mcpBridge.BuildToolModels(ctx, tenantID)
systemPrompt := formatToolsForLLM(tools)
llmRequest.SystemPrompt = systemPrompt
```

#### B. With Observability Service

MCP Service reports:
- Tool execution metrics
- Server health metrics
- Cache hit rates
- Error rates per tool

```go
// Example: Recording metrics
usageTracker.RecordToolExecution(
    tenantID, 
    toolName, 
    executionMs, 
    success, 
    errorMsg,
)
```

#### C. With Auth Service

Security controls:
- API key scoping (which tools available to which keys)
- Tenant isolation (tools per tenant)
- Role-based access (permissions for specific tools)

```go
// Example: Checking permissions
canExecute := authService.HasToolPermission(
    apiKeyID,
    toolName,
    tenantID,
)
```

## Configuration

### Server Configuration (YAML)

```yaml
servers:
  - name: "my-tool"
    type: "http"
    endpoint: "http://localhost:3000"
    transport: "sse"
    capabilities:
      - "custom:read"
    timeout: 30s
```

### Environment Variables

```bash
# Discovery
MCP_DISCOVERY_ENABLED=true
MCP_DISCOVERY_POLLING_INTERVAL=60s

# Cache
MCP_CACHE_TTL=5m
MCP_CACHE_MAX_SIZE=10000

# Security
MCP_DEFAULT_TIMEOUT=30s
MCP_MAX_TIMEOUT=300s

# Integration
GATEWAY_SERVICE_HOST=gateway-service
GATEWAY_SERVICE_PORT=50051
```

## API Usage

### List Available Tools

```bash
curl http://localhost:8055/v1/tools/list \
  -H "Authorization: Bearer YOUR_API_KEY"

# Filter by capability
curl http://localhost:8055/v1/tools/list?capability=weather:read \
  -H "Authorization: Bearer YOUR_API_KEY"
```

### Execute a Tool

```bash
curl -X POST http://localhost:8055/v1/tools/call \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "tool": "get_weather",
    "arguments": {
      "location": "San Francisco",
      "units": "fahrenheit"
    }
  }'
```

### Register a New Server

```bash
curl -X POST http://localhost:8055/v1/servers \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-tool",
    "type": "http",
    "endpoint": "http://localhost:3000",
    "transport": "sse",
    "capabilities": ["my:feature"],
    "timeout": "30s"
  }'
```

### Check Server Health

```bash
curl http://localhost:8055/v1/servers/my-tool/health \
  -H "Authorization: Bearer YOUR_API_KEY"
```

## Building an MCP Server

### Minimal Example (Go)

```go
package main

import (
	"github.com/your-org/astra-gateway/mcp"
)

func main() {
	// Create server
	server := mcp.NewServer("my-tool")
	
	// Register a tool
	server.RegisterTool(mcp.ToolDefinition{
		Name:        "greet",
		Description: "Greets a person",
		InputSchema: mcp.InputSchema{
			Type: "object",
			Properties: map[string]interface{}{
				"name": {"type": "string"},
			},
			Required: []string{"name"},
		},
		Handler: func(args map[string]interface{}) (interface{}, error) {
			name := args["name"].(string)
			return map[string]string{"greeting": "Hello, " + name}, nil
		},
	})
	
	// Start server
	server.StartSSE(":3000")
}
```

### Minimal Example (Python)

```python
from astra_mcp import MCPServer, ToolDefinition

# Create server
server = MCPServer("my-tool")

# Register a tool
@server.tool(
    name="greet",
    description="Greets a person"
)
def greet(name: str) -> dict:
    return {"greeting": f"Hello, {name}"}

# Define input schema
def get_greeting_schema():
    return {
        "type": "object",
        "properties": {
            "name": {"type": "string"}
        },
        "required": ["name"]
    }

# Start server
if __name__ == "__main__":
    server.start_sse(port=3000)
```

## Database Schema

Required tables for MCP Service:

```sql
-- Server Registry
CREATE TABLE mcp_servers (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(255) UNIQUE NOT NULL,
    endpoint VARCHAR(1024) NOT NULL,
    server_type VARCHAR(50) NOT NULL,
    transport_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) DEFAULT 'unknown',
    tool_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_health_check TIMESTAMP
);

-- Tool Metadata Cache
CREATE TABLE mcp_tools (
    id BIGSERIAL PRIMARY KEY,
    server_id BIGINT NOT NULL REFERENCES mcp_servers(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    input_schema JSONB,
    capabilities TEXT[],
    version VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(server_id, name)
);

-- Tool Execution Audit Log
CREATE TABLE mcp_tool_executions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    api_key_id BIGINT NOT NULL,
    tool_id BIGINT NOT NULL REFERENCES mcp_tools(id),
    arguments JSONB,
    result JSONB,
    error_message TEXT,
    execution_time_ms INT,
    success BOOLEAN,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Usage Metrics
CREATE TABLE mcp_tool_usage_metrics (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    tool_id BIGINT NOT NULL REFERENCES mcp_tools(id),
    call_count BIGINT DEFAULT 0,
    success_count BIGINT DEFAULT 0,
    failure_count BIGINT DEFAULT 0,
    total_execution_ms BIGINT DEFAULT 0,
    max_execution_ms INT,
    min_execution_ms INT,
    last_called_at TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indices
CREATE INDEX idx_mcp_servers_tenant_id ON mcp_servers(tenant_id);
CREATE INDEX idx_mcp_tools_server_id ON mcp_tools(server_id);
CREATE INDEX idx_mcp_executions_tenant_id ON mcp_tool_executions(tenant_id);
CREATE INDEX idx_mcp_usage_tenant_id ON mcp_tool_usage_metrics(tenant_id);
```

## Testing

### Unit Tests

```bash
cd backend/mcp-service
go test ./internal/...
go test ./internal/api/...
```

### Integration Tests

```bash
go test -tags integration ./test/...
```

### Load Testing

```bash
go run test/load/main.go \
  --servers 3 \
  --concurrent-requests 100 \
  --duration 60s
```

## Troubleshooting

### Server Not Discovered

1. Check server is running: `curl {endpoint}/health`
2. Check MCP Service logs: `docker logs astra-mcp-service`
3. Verify configuration in `config/mcp-servers.yaml`
4. Check network connectivity between containers

### Tool Execution Failing

1. Check tool input schema matches arguments
2. Review MCP server logs for errors
3. Check resource limits aren't exceeded
4. Verify timeout isn't too short

### Performance Issues

1. Check cache hit rate: `curl http://localhost:9055/metrics | grep cache_hit`
2. Check tool execution times: Look at logs
3. Consider increasing cache TTL or size
4. Check resource limits on servers

## Security Best Practices

1. **API Key Scoping**: Only expose necessary tools per API key
2. **Input Validation**: Always validate tool input arguments
3. **Timeout Protection**: Set appropriate timeouts per tool
4. **Resource Limits**: Set memory/CPU limits per tool execution
5. **Audit Logging**: Track all tool executions with audit trail
6. **TLS/mTLS**: Use encrypted connections to servers
7. **Authentication**: Require authentication for server access

## Performance Optimization

1. **Caching**: Tool results cached for configurable TTL
2. **Health Checks**: Stagger health checks to prevent thundering herd
3. **Connection Pooling**: Reuse connections to MCP servers
4. **Async Execution**: Support async tool calls for non-blocking operations
5. **Rate Limiting**: Prevent overload with per-tool rate limits

## Monitoring & Metrics

### Key Metrics

- `mcp_tool_execution_duration_ms`: Tool execution time
- `mcp_server_health_check_duration_ms`: Health check time
- `mcp_cache_hit_rate`: Percentage of cache hits
- `mcp_tool_execution_errors_total`: Total execution errors
- `mcp_server_availability_percent`: Server availability

### Dashboards

Grafana dashboards available in `infrastructure/grafana/dashboards/mcp-*.json`

## Examples

See `examples/` directory for:
- Simple HTTP-based MCP server
- Database query tool
- File system access tool
- Web search integration
- Email notification tool

## Support

For issues or questions:
1. Check logs: `docker logs astra-mcp-service`
2. Review configuration: `cat config/mcp-servers.yaml`
3. Test API endpoints manually: `curl` commands above
4. Create GitHub issue with logs and configuration
