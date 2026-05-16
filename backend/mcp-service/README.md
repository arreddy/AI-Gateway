## MCP Service - Model Context Protocol Integration

Service: `mcp-service`
Language: Go 1.21+
Purpose: MCP server implementation, discovery, and integration with AI-Gateway

## Overview

The MCP Service provides Model Context Protocol (MCP) integration for the Astra Gateway, enabling seamless connection between AI models and external tools, data sources, and services. It acts as a central hub for:

- **Server Discovery**: Automatic discovery and registration of MCP servers
- **Tool Catalog**: Maintain and serve dynamic tool catalogs
- **Server Health Monitoring**: Track server availability and performance
- **Async Communication**: Support for both request-response and streaming patterns
- **Security & Access Control**: Scope-based permissions for tool access

## Responsibilities

### 1. MCP Protocol Implementation
- Implement Model Context Protocol (SSE and stdio transport)
- Request/response handling with JSON-RPC 2.0
- Streaming support for long-running operations
- Error handling and protocol compliance

### 2. Server Discovery & Registration
- Discover MCP servers from configurable registry
- Auto-registration via environment/config
- Health checks and availability monitoring
- Dynamic server list updates
- Server capability introspection

### 3. Tool & Resource Management
- Expose tool catalogs from registered servers
- Cache tool metadata for performance
- Resource availability tracking
- Tool version management

### 4. Gateway Integration
- Tools available through `/v1/tools/list` endpoint
- Tool execution via `/v1/tools/call` endpoint
- Automatic tool injection into LLM prompts
- Tool result formatting and caching

### 5. Observability
- MCP server health metrics
- Tool execution latency
- Success/failure rates per tool
- Server availability tracking
- Detailed request/response logging

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   Gateway Service                           │
│              (Chat Completion Requests)                     │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
        ┌────────────────────────────────┐
        │      MCP Service               │
        │                                │
        │  ┌──────────────────────────┐ │
        │  │ Server Discovery         │ │
        │  │ - Registry lookup        │ │
        │  │ - Health checks          │ │
        │  │ - Capability introspect  │ │
        │  └──────────────────────────┘ │
        │                                │
        │  ┌──────────────────────────┐ │
        │  │ MCP Protocol Handler     │ │
        │  │ - JSON-RPC 2.0           │ │
        │  │ - SSE Transport          │ │
        │  │ - Streaming support      │ │
        │  └──────────────────────────┘ │
        │                                │
        │  ┌──────────────────────────┐ │
        │  │ Tool Cache & Manager     │ │
        │  │ - Tool metadata          │ │
        │  │ - Tool execution         │ │
        │  │ - Result formatting      │ │
        │  └──────────────────────────┘ │
        └──────┬──────────────────┬─────┘
               │                  │
        ┌──────▼──┐        ┌──────▼──────────┐
        │ MCP     │        │ Server          │
        │ Server  │        │ Registry        │
        │ #1 (SSE)│        │ (PostgreSQL)    │
        └─────────┘        └─────────────────┘
        
        ┌──────────┐
        │ MCP      │
        │ Server   │
        │ #2 (stdio)
        └──────────┘
```

## Configuration

### Environment Variables
```bash
MCP_DISCOVERY_ENABLED=true
MCP_HEALTH_CHECK_INTERVAL=30s
MCP_CACHE_TTL=5m
MCP_REGISTRY_URL=postgres://...
MCP_LOG_LEVEL=info
```

### Server Registration (config/mcp-servers.yaml)
```yaml
servers:
  - name: "weather-tool"
    type: "http"
    endpoint: "http://localhost:3001"
    transport: "sse"
    capabilities:
      - "weather:read"
    health_check_path: "/health"
    timeout: 30s
    
  - name: "database-tool"
    type: "stdio"
    command: "python /opt/tools/db-connector.py"
    transport: "stdio"
    capabilities:
      - "database:query"
      - "database:write"
    env:
      DB_CONNECTION_STRING: "${DB_URL}"
    
  - name: "file-system-tool"
    type: "http"
    endpoint: "http://localhost:3002"
    transport: "sse"
    capabilities:
      - "filesystem:read"
      - "filesystem:list"
    authentication:
      type: "bearer"
      token: "${FS_API_KEY}"
```

## API Endpoints

### List Available Tools
```http
GET /v1/tools/list
Authorization: Bearer {api_key}

Response:
{
  "tools": [
    {
      "name": "weather",
      "description": "Get weather information",
      "input_schema": {
        "type": "object",
        "properties": {
          "location": { "type": "string" },
          "units": { "type": "string", "enum": ["celsius", "fahrenheit"] }
        },
        "required": ["location"]
      },
      "server": "weather-tool",
      "capabilities": ["weather:read"]
    }
  ]
}
```

### Call a Tool
```http
POST /v1/tools/call
Authorization: Bearer {api_key}
Content-Type: application/json

{
  "tool": "weather",
  "arguments": {
    "location": "San Francisco",
    "units": "fahrenheit"
  }
}

Response:
{
  "result": {
    "temperature": 72,
    "conditions": "Sunny",
    "humidity": 65
  },
  "execution_time_ms": 245,
  "server": "weather-tool"
}
```

### List Registered Servers
```http
GET /v1/servers
Authorization: Bearer {api_key}

Response:
{
  "servers": [
    {
      "name": "weather-tool",
      "endpoint": "http://localhost:3001",
      "status": "healthy",
      "last_health_check": "2024-01-15T10:30:00Z",
      "tools_count": 5,
      "capabilities": ["weather:read"]
    }
  ]
}
```

### Server Health Check
```http
GET /v1/servers/{server_name}/health
Authorization: Bearer {api_key}

Response:
{
  "status": "healthy",
  "uptime_percent": 99.98,
  "last_checked": "2024-01-15T10:30:00Z",
  "response_time_ms": 45,
  "error_rate": 0.02
}
```

## Integration with AI Models

When using tools with AI models, the MCP Service automatically:

1. **Injects tools into system prompt**: Model receives available tool definitions
2. **Monitors model tool calls**: Intercepts tool invocation from model
3. **Executes tools via MCP**: Routes to appropriate MCP server
4. **Returns results to model**: Feeds tool output back for continued execution
5. **Tracks usage**: Metrics for tool usage and performance

### Example Flow
```
User Request: "What's the weather in San Francisco and save it to the database"

1. Gateway receives request
2. MCP Service injects available tools into system prompt
3. LLM sees available tools and decides to use "weather" tool
4. Gateway intercepts tool call
5. MCP Service routes to weather-tool server
6. Weather data retrieved
7. LLM decides to use "database" tool
8. Gateway intercepts second tool call
9. MCP Service routes to database-tool server
10. Data stored
11. Final response sent to user
```

## Development Guide

### Creating a Simple MCP Server

```go
package main

import (
	"github.com/your-org/astra-gateway/mcp"
)

func main() {
	// Create MCP server
	server := mcp.NewServer("my-tool-server")
	
	// Register a tool
	server.RegisterTool(mcp.ToolDefinition{
		Name: "search",
		Description: "Search the database",
		InputSchema: mcp.JSONSchema{
			Type: "object",
			Properties: map[string]interface{}{
				"query": {"type": "string"},
			},
			Required: []string{"query"},
		},
		Handler: func(args map[string]interface{}) (interface{}, error) {
			query := args["query"].(string)
			// Execute search
			return searchResults, nil
		},
	})
	
	// Start server (SSE transport)
	server.StartSSE(":3001")
}
```

### Running MCP Service Locally

```bash
# Set up environment
export MCP_DISCOVERY_ENABLED=true
export MCP_REGISTRY_URL=postgres://localhost/astra_dev

# Build and run
go build -o mcp-service cmd/main.go
./mcp-service

# Or with docker-compose (see docker-compose.dev.yaml)
docker-compose -f docker-compose.dev.yaml up mcp-service
```

## Testing

```bash
# Unit tests
go test ./internal/...

# Integration tests
go test -tags integration ./test/...

# Load testing
go run test/load/main.go --servers 5 --rpm 1000
```

## Security Considerations

1. **API Key Scoping**: Tools can be scoped to specific API keys
2. **Capability-Based Access**: Users can only access tools with appropriate permissions
3. **Tenant Isolation**: MCP servers/tools isolated per tenant
4. **Rate Limiting**: Per-tool rate limits
5. **Input Validation**: Schema validation on tool inputs
6. **Timeout Protection**: Maximum execution time per tool call
7. **Resource Limits**: Memory/CPU limits per tool execution

## Performance Metrics

- **Tool Discovery**: < 100ms (cached)
- **Tool Execution**: Variable (depends on tool)
- **Health Checks**: < 50ms
- **Catalog Cache Hit Rate**: > 99%
