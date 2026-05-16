# MCP Service Architecture

## System Overview

The MCP Service is a core component of the Astra Gateway that provides Model Context Protocol (MCP) integration. It acts as a central hub for discovering, managing, and executing tools from external MCP servers.

## Component Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                      MCP Service                                 │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    HTTP API Layer                          │ │
│  │                                                            │ │
│  │  GET  /v1/tools/list               - List all tools      │ │
│  │  POST /v1/tools/call               - Execute a tool      │ │
│  │  GET  /v1/servers                  - List servers        │ │
│  │  POST /v1/servers                  - Register server     │ │
│  │  GET  /v1/servers/{name}/health    - Health check       │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                 │                                │
│                                 ▼                                │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │               Business Logic Layer                         │ │
│  │                                                            │ │
│  │  ┌──────────────────┐  ┌──────────────────┐              │ │
│  │  │ Discovery Manager│  │ Tool Executor    │              │ │
│  │  │                  │  │                  │              │ │
│  │  │ • Registration   │  │ • Execution      │              │ │
│  │  │ • Health Checks  │  │ • Error Handling │              │ │
│  │  │ • Capabilities   │  │ • Async Support  │              │ │
│  │  └──────────────────┘  └──────────────────┘              │ │
│  │                                                            │ │
│  │  ┌──────────────────┐  ┌──────────────────┐              │ │
│  │  │ Tool Cache       │  │ Tool Router      │              │ │
│  │  │                  │  │                  │              │ │
│  │  │ • Metadata Cache │  │ • Routing Logic  │              │ │
│  │  │ • TTL Management │  │ • Server Lookup  │              │ │
│  │  │ • Hit Rate Stats │  │ • Tool Resolution
│  │  └──────────────────┘  └──────────────────┘              │ │
│  │                                                            │ │
│  │  ┌──────────────────┐  ┌──────────────────┐              │ │
│  │  │ Gateway Bridge   │  │ Usage Tracker    │              │ │
│  │  │                  │  │                  │              │ │
│  │  │ • Tool Injection │  │ • Metrics        │              │ │
│  │  │ • Call Intercept │  │ • Analytics      │              │ │
│  │  │ • Integration    │  │ • Audit Log      │              │ │
│  │  └──────────────────┘  └──────────────────┘              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                 │                                │
│                                 ▼                                │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │               MCP Client Layer                             │ │
│  │                                                            │ │
│  │  ┌──────────────────┐  ┌──────────────────┐              │ │
│  │  │ HTTP Client      │  │ Stdio Client     │              │ │
│  │  │ (SSE Transport)  │  │ (Process Mgmt)   │              │ │
│  │  └──────────────────┘  └──────────────────┘              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                 │                                │
└─────────────────────────────────┼────────────────────────────────┘
                                  │
                                  ▼
                    ┌─────────────────────────────┐
                    │  External MCP Servers       │
                    │                             │
                    │ • Weather Service (HTTP)    │
                    │ • Database Tool (stdio)     │
                    │ • File System (HTTP)        │
                    │ • Web Search (HTTP)         │
                    │ • Email (stdio)             │
                    │ • Custom Tools (any)        │
                    └─────────────────────────────┘
```

## Data Flow

### Tool Discovery Flow

```
1. MCP Service Startup
   ↓
2. Load Configuration (mcp-servers.yaml)
   ↓
3. For Each Server:
   ├─ Create MCP Client
   ├─ Connect to Server
   ├─ Get Server Status
   ├─ List Available Tools
   └─ Cache Tool Metadata
   ↓
4. Health Check Loop (periodic)
   ├─ Check each server
   ├─ Update availability status
   └─ Refresh tool cache if needed
   ↓
5. Servers Ready for Use
```

### Tool Execution Flow

```
Client Request (POST /v1/tools/call)
   ↓
[Authenticate & Validate]
   ├─ Check API Key
   ├─ Verify Permissions
   └─ Validate Input
   ↓
[Create Execution Request]
   ├─ Resolve Server for Tool
   ├─ Build Arguments
   └─ Generate Request ID
   ↓
[Execute Tool]
   ├─ Get MCP Client
   ├─ Call Tool Method
   ├─ Wait for Result (with timeout)
   └─ Record Metrics
   ↓
[Process Result]
   ├─ Check for Errors
   ├─ Format Response
   └─ Cache if appropriate
   ↓
Return to Client
```

### Tool Injection into LLM Flow

```
Gateway receives Chat Request
   ↓
[Needs Tool Context?]
   ├─ Get Tenant's Available Tools
   ├─ Build Tool Definitions
   └─ Format for LLM
   ↓
[Inject into System Prompt]
   ├─ Append Tool List
   ├─ Provide Tool Schema
   └─ Explain Tool Invocation
   ↓
Send to LLM
   ↓
LLM Response with Tool Call
   ↓
[Intercept Tool Call]
   ├─ Parse Tool Call
   ├─ Extract Arguments
   └─ Validate
   ↓
[Execute Tool]
   ├─ Call MCP Service
   ├─ Get Result
   └─ Format for LLM
   ↓
[Resume LLM]
   ├─ Feed Tool Result
   ├─ Continue Generation
   └─ Final Response
   ↓
Return to Client
```

## Key Responsibilities

### Discovery Manager
- Maintains registry of available MCP servers
- Performs periodic health checks
- Tracks server status and capabilities
- Handles server registration/deregistration
- Manages client connections

### Tool Executor
- Executes tools on appropriate servers
- Handles timeouts and retries
- Records execution metrics
- Manages async execution
- Handles error cases

### Tool Cache
- Caches tool metadata in memory
- Manages cache expiration
- Provides hit rate statistics
- Cleans up expired entries
- Supports tenant isolation

### Tool Router
- Routes tool calls to correct servers
- Builds tool map from registered servers
- Resolves tool names to servers
- Lists tools per server or globally
- Filters by capability

### Gateway Bridge
- Integrates with Gateway Service
- Builds tool definitions for LLM injection
- Intercepts and executes tool calls
- Tracks usage metrics
- Provides tenant isolation

## Interfaces

### MCPClient Interface
```go
type MCPClient interface {
    Connect(ctx context.Context) error
    Disconnect(ctx context.Context) error
    IsConnected() bool
    ListTools(ctx context.Context) ([]ToolDefinition, error)
    CallTool(ctx context.Context, name string, args map[string]interface{}) (interface{}, error)
    Health(ctx context.Context) (*ServerStatus, error)
    Close() error
}
```

### ToolExecutor Interface
```go
type ToolExecutor interface {
    Execute(ctx context.Context, req *ToolExecutionRequest) (*ToolExecutionResponse, error)
    ExecuteAsync(ctx context.Context, req *ToolExecutionRequest, callback func(*ToolExecutionResponse, error))
}
```

### ToolCache Interface
```go
type ToolCache interface {
    Get(serverName, toolName string) (*ToolDefinition, bool)
    Set(serverName string, tools []ToolDefinition)
    Invalidate(serverName string)
    Stats() CacheStats
    Clear()
}
```

### ServerDiscoverer Interface
```go
type ServerDiscoverer interface {
    DiscoverServers(ctx context.Context) ([]DiscoveryResult, error)
    RegisterServer(ctx context.Context, config *ServerConfig) error
    GetServer(ctx context.Context, serverName string) (*ServerStatus, error)
    ListServers(ctx context.Context) ([]ServerStatus, error)
    DeregisterServer(ctx context.Context, serverName string) error
    RefreshServerStatus(ctx context.Context, serverName string) error
}
```

## State Management

### Server State
- Active servers tracked in memory
- Client connections pooled
- Health status updated periodically
- Registration stored in database

### Tool Metadata State
- Cached in memory with TTL
- Database backup for durability
- Per-server organization
- Capability indexing

### Execution State
- Current executions tracked
- Async call results stored
- Metrics accumulated
- Audit log maintained

## Error Handling Strategy

```
Tool Execution Request
   ↓
[Connection Error]
   ├─ Retry (if configured)
   ├─ Circuit breaker
   └─ Fallback server (if available)
   ↓
[Timeout]
   ├─ Cancel execution
   ├─ Return timeout error
   └─ Log warning
   ↓
[Tool Error]
   ├─ Return error result
   ├─ Log error
   └─ Record metric
   ↓
[Validation Error]
   ├─ Return validation error
   ├─ Log with details
   └─ Record invalid input
```

## Scalability Considerations

### Horizontal Scaling
- Stateless design allows multiple instances
- Shared PostgreSQL for server registry
- Redis for distributed caching (optional)
- Load balanced across instances

### Performance Optimization
- Tool metadata caching with TTL
- Connection pooling to servers
- Concurrent health checks with semaphore
- Async tool execution support

### Resource Management
- Configurable resource limits per tool
- Timeout enforcement
- Memory limits on tool execution
- CPU limits on tool processes

## Security Model

### Authentication
- API key based access
- JWT token validation
- Tenant context from auth service

### Authorization
- API key scoping (tools per key)
- Tenant isolation (tools per tenant)
- Role-based access control
- Capability-based permissions

### Data Protection
- Input validation on arguments
- Schema validation on results
- TLS for external connections
- Audit logging of executions

## Observability

### Metrics
- Prometheus format
- Tool execution duration
- Server health check duration
- Cache hit rate
- Error rates

### Logging
- Structured JSON logs
- Request/response logging
- Error stack traces
- Performance metrics

### Tracing
- OpenTelemetry integration
- Distributed tracing with Jaeger
- Request ID correlation
- Service-to-service tracing

## Integration Points

### With Gateway Service
- HTTP gRPC for synchronous calls
- Tool injection into system prompts
- Tool call interception and execution
- Response formatting

### With Observability Service
- Metrics export via Prometheus
- Log aggregation via ELK
- Distributed tracing via Jaeger
- Dashboard in Grafana

### With Auth Service
- Token validation
- Permission checking
- Tenant context extraction
- API key scoping

### With Routing Engine
- Tool-based routing decisions
- Tool availability in routing rules
- Tool execution for routing context
- Performance data from tools
