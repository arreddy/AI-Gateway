package internal

import (
	"context"
	"time"
)

// ============================================================================
// MCP PROTOCOL TYPES
// ============================================================================

// JSONRPCRequest represents a JSON-RPC 2.0 request
type JSONRPCRequest struct {
	JSONRPC string        `json:"jsonrpc"`
	ID      interface{}   `json:"id"`
	Method  string        `json:"method"`
	Params  interface{}   `json:"params,omitempty"`
}

// JSONRPCResponse represents a JSON-RPC 2.0 response
type JSONRPCResponse struct {
	JSONRPC string      `json:"jsonrpc"`
	ID      interface{} `json:"id"`
	Result  interface{} `json:"result,omitempty"`
	Error   *JSONRPCError `json:"error,omitempty"`
}

// JSONRPCError represents a JSON-RPC 2.0 error
type JSONRPCError struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

// ============================================================================
// TOOL DEFINITIONS
// ============================================================================

// ToolDefinition describes a single tool available in an MCP server
type ToolDefinition struct {
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	InputSchema InputSchema            `json:"input_schema"`
	Capabilities []string              `json:"capabilities,omitempty"`
	Timeout     *int                   `json:"timeout_seconds,omitempty"`
	Version     string                 `json:"version,omitempty"`
}

// InputSchema describes the input schema for a tool
type InputSchema struct {
	Type        string                 `json:"type"`
	Description string                 `json:"description,omitempty"`
	Properties  map[string]interface{} `json:"properties,omitempty"`
	Required    []string               `json:"required,omitempty"`
	Items       interface{}            `json:"items,omitempty"`
	Enum        []interface{}          `json:"enum,omitempty"`
}

// ToolCall represents a request to execute a tool
type ToolCall struct {
	Name      string                 `json:"name"`
	Arguments map[string]interface{} `json:"arguments"`
}

// ToolResult represents the result of executing a tool
type ToolResult struct {
	Content         interface{} `json:"content"`
	IsError         bool        `json:"is_error,omitempty"`
	ExecutionTimeMs int         `json:"execution_time_ms"`
}

// ============================================================================
// SERVER DEFINITIONS
// ============================================================================

// ServerTransportType defines the transport protocol for an MCP server
type ServerTransportType string

const (
	TransportSSE   ServerTransportType = "sse"    // Server-Sent Events (HTTP)
	TransportStdio ServerTransportType = "stdio"  // Standard Input/Output
	TransportHTTP  ServerTransportType = "http"   // HTTP JSON-RPC
)

// ServerType defines the type of MCP server
type ServerType string

const (
	ServerTypeHTTP  ServerType = "http"
	ServerTypeStdio ServerType = "stdio"
)

// ServerConfig represents the configuration for an MCP server
type ServerConfig struct {
	Name              string                      `json:"name"`
	Type              ServerType                  `json:"type"`
	Endpoint          string                      `json:"endpoint,omitempty"`     // For HTTP servers
	Command           string                      `json:"command,omitempty"`      // For stdio servers
	Transport         ServerTransportType         `json:"transport"`
	Capabilities      []string                    `json:"capabilities"`
	HealthCheckPath   string                      `json:"health_check_path,omitempty"`
	HealthCheckInterval time.Duration             `json:"health_check_interval,omitempty"`
	Timeout           time.Duration               `json:"timeout"`
	MaxRetries        int                         `json:"max_retries,omitempty"`
	Authentication    *AuthConfig                 `json:"authentication,omitempty"`
	Environment       map[string]string           `json:"environment,omitempty"`
	TenantID          int64                       `json:"tenant_id,omitempty"`
	Tags              map[string]string           `json:"tags,omitempty"`
}

// AuthConfig represents authentication configuration for an MCP server
type AuthConfig struct {
	Type   string `json:"type"` // "bearer", "basic", "api_key", "oauth2"
	Token  string `json:"token,omitempty"`
	Key    string `json:"key,omitempty"`
	Secret string `json:"secret,omitempty"`
}

// ServerStatus represents the current status of an MCP server
type ServerStatus struct {
	Name             string    `json:"name"`
	Status           string    `json:"status"` // "healthy", "unhealthy", "unknown"
	LastHealthCheck  time.Time `json:"last_health_check"`
	ResponseTimeMs   int       `json:"response_time_ms"`
	ErrorRate        float64   `json:"error_rate"` // 0.0 to 1.0
	ToolsCount       int       `json:"tools_count"`
	Capabilities     []string  `json:"capabilities"`
	UptimePercent    float64   `json:"uptime_percent"`
	LastError        string    `json:"last_error,omitempty"`
	ConnectionCount  int       `json:"connection_count"`
}

// ServerRegistry represents a registry of MCP servers
type ServerRegistry struct {
	ID         int64
	TenantID   int64
	Name       string
	Endpoint   string
	Type       ServerType
	Transport  ServerTransportType
	Status     string
	ToolCount  int
	CreatedAt  time.Time
	UpdatedAt  time.Time
	LastHealth time.Time
}

// ============================================================================
// DISCOVERY TYPES
// ============================================================================

// DiscoveryResult represents the result of server discovery
type DiscoveryResult struct {
	Server ServerStatus              `json:"server"`
	Tools  []ToolDefinition          `json:"tools"`
	Error  string                    `json:"error,omitempty"`
}

// DiscoveryConfig represents the configuration for server discovery
type DiscoveryConfig struct {
	Enabled           bool
	RegistryURL       string
	PollingInterval   time.Duration
	HealthCheckPath   string
	HealthCheckTimeout time.Duration
	MaxConcurrent     int
}

// ============================================================================
// CACHE TYPES
// ============================================================================

// CacheEntry represents a cached tool definition
type CacheEntry struct {
	Tool      ToolDefinition
	Server    string
	ExpiresAt time.Time
	HitCount  int64
}

// CacheStats represents cache statistics
type CacheStats struct {
	TotalRequests   int64
	CacheHits       int64
	CacheMisses     int64
	EvictionCount   int64
	CurrentSize     int64
	MaxSize         int64
	HitRatePercent  float64
}

// ============================================================================
// EXECUTION TYPES
// ============================================================================

// ToolExecutionRequest represents a request to execute a tool
type ToolExecutionRequest struct {
	ServerName  string                 `json:"server_name"`
	ToolName    string                 `json:"tool_name"`
	Arguments   map[string]interface{} `json:"arguments"`
	Timeout     time.Duration          `json:"timeout,omitempty"`
	TenantID    int64                  `json:"tenant_id"`
	APIKeyID    int64                  `json:"api_key_id"`
	RequestID   string                 `json:"request_id"`
}

// ToolExecutionResponse represents the response from tool execution
type ToolExecutionResponse struct {
	RequestID       string        `json:"request_id"`
	ServerName      string        `json:"server_name"`
	ToolName        string        `json:"tool_name"`
	Result          interface{}   `json:"result"`
	IsError         bool          `json:"is_error"`
	ExecutionTimeMs int           `json:"execution_time_ms"`
	CachedResult    bool          `json:"cached_result,omitempty"`
	Timestamp       time.Time     `json:"timestamp"`
}

// ============================================================================
// HANDLER INTERFACES
// ============================================================================

// MCPClient defines the interface for communicating with an MCP server
type MCPClient interface {
	// Connect establishes a connection to the MCP server
	Connect(ctx context.Context) error
	
	// Disconnect closes the connection
	Disconnect(ctx context.Context) error
	
	// IsConnected returns whether the client is currently connected
	IsConnected() bool
	
	// ListTools returns all tools available on the server
	ListTools(ctx context.Context) ([]ToolDefinition, error)
	
	// CallTool executes a specific tool
	CallTool(ctx context.Context, toolName string, args map[string]interface{}) (interface{}, error)
	
	// Health checks the server health
	Health(ctx context.Context) (*ServerStatus, error)
	
	// Close closes the client
	Close() error
}

// ToolExecutor defines the interface for executing tools
type ToolExecutor interface {
	Execute(ctx context.Context, req *ToolExecutionRequest) (*ToolExecutionResponse, error)
	ExecuteAsync(ctx context.Context, req *ToolExecutionRequest, callback func(*ToolExecutionResponse, error))
}

// ToolCache defines the interface for caching tool definitions
type ToolCache interface {
	Get(serverName, toolName string) (*ToolDefinition, bool)
	Set(serverName string, tools []ToolDefinition)
	Invalidate(serverName string)
	Stats() CacheStats
	Clear()
}

// ServerDiscoverer defines the interface for discovering MCP servers
type ServerDiscoverer interface {
	// DiscoverServers discovers available MCP servers
	DiscoverServers(ctx context.Context) ([]DiscoveryResult, error)
	
	// RegisterServer registers a new MCP server
	RegisterServer(ctx context.Context, config *ServerConfig) error
	
	// GetServer retrieves server information
	GetServer(ctx context.Context, serverName string) (*ServerStatus, error)
	
	// ListServers returns all registered servers
	ListServers(ctx context.Context) ([]ServerStatus, error)
	
	// DeregisterServer removes a server from the registry
	DeregisterServer(ctx context.Context, serverName string) error
	
	// RefreshServerStatus updates the health status of a server
	RefreshServerStatus(ctx context.Context, serverName string) error
}

// ============================================================================
// REQUEST/RESPONSE TYPES
// ============================================================================

// ListToolsRequest represents a request to list available tools
type ListToolsRequest struct {
	ServerName  string `json:"server_name,omitempty"` // Empty = list from all servers
	Capability  string `json:"capability,omitempty"`   // Filter by capability
	IncludeSchema bool  `json:"include_schema,omitempty"`
}

// ListToolsResponse represents the response with available tools
type ListToolsResponse struct {
	Tools []ToolDefinition `json:"tools"`
	Count int              `json:"count"`
}

// CallToolRequest represents a request to call a tool
type CallToolRequest struct {
	Tool      string                 `json:"tool"`
	Arguments map[string]interface{} `json:"arguments"`
}

// CallToolResponse represents the response from calling a tool
type CallToolResponse struct {
	Result          interface{} `json:"result"`
	ExecutionTimeMs int         `json:"execution_time_ms"`
	Server          string      `json:"server"`
	Cached          bool        `json:"cached,omitempty"`
}
