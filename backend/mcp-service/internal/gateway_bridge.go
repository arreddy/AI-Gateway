package internal

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// ============================================================================
// GATEWAY MCP BRIDGE
// ============================================================================

// GatewayMCPBridge bridges MCP service with Gateway service
// This allows the gateway to use tools from MCP servers in LLM requests
type GatewayMCPBridge struct {
	discoverer ServerDiscoverer
	executor   ToolExecutor
	cache      ToolCache
	logger     Logger
	mu         sync.RWMutex
	toolModels map[int64][]ToolDefinition // tenant_id -> tools
}

// NewGatewayMCPBridge creates a new gateway MCP bridge
func NewGatewayMCPBridge(discoverer ServerDiscoverer, executor ToolExecutor, cache ToolCache, logger Logger) *GatewayMCPBridge {
	return &GatewayMCPBridge{
		discoverer: discoverer,
		executor:   executor,
		cache:      cache,
		logger:     logger,
		toolModels: make(map[int64][]ToolDefinition),
	}
}

// BuildToolModels builds tool definitions for injection into LLM prompts
func (b *GatewayMCPBridge) BuildToolModels(ctx context.Context, tenantID int64) ([]ToolDefinition, error) {
	// Check cache first
	b.mu.RLock()
	if tools, ok := b.toolModels[tenantID]; ok {
		b.mu.RUnlock()
		return tools, nil
	}
	b.mu.RUnlock()

	// Get all servers and tools
	servers, err := b.discoverer.ListServers(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to list servers: %w", err)
	}

	var tools []ToolDefinition

	for _, server := range servers {
		if server.Status != "healthy" {
			continue
		}

		// Only include tools from servers for this tenant
		// (in a real implementation, would filter by tenant)
		client, err := b.discoverer.GetClient(server.Name)
		if err != nil {
			b.logger.Error("Failed to get client", "server", server.Name, "error", err)
			continue
		}

		serverTools, err := client.ListTools(ctx)
		if err != nil {
			b.logger.Error("Failed to list tools", "server", server.Name, "error", err)
			continue
		}

		tools = append(tools, serverTools...)
	}

	// Cache the result
	b.mu.Lock()
	b.toolModels[tenantID] = tools
	b.mu.Unlock()

	b.logger.Info("Tool models built for tenant", "tenant_id", tenantID, "tools_count", len(tools))
	return tools, nil
}

// GetToolModel gets the definition of a specific tool for LLM injection
func (b *GatewayMCPBridge) GetToolModel(ctx context.Context, tenantID int64, toolName string) (*ToolDefinition, error) {
	tools, err := b.BuildToolModels(ctx, tenantID)
	if err != nil {
		return nil, err
	}

	for _, tool := range tools {
		if tool.Name == toolName {
			return &tool, nil
		}
	}

	return nil, fmt.Errorf("tool not found: %s", toolName)
}

// ExecuteToolFromGateway executes a tool called from the gateway
func (b *GatewayMCPBridge) ExecuteToolFromGateway(ctx context.Context, tenantID int64, toolName string, arguments map[string]interface{}) (interface{}, error) {
	// Find which server provides this tool
	servers, err := b.discoverer.ListServers(ctx)
	if err != nil {
		return nil, err
	}

	var serverName string
	for _, server := range servers {
		if server.Status != "healthy" {
			continue
		}

		client, err := b.discoverer.GetClient(server.Name)
		if err != nil {
			continue
		}

		tools, err := client.ListTools(ctx)
		if err != nil {
			continue
		}

		for _, tool := range tools {
			if tool.Name == toolName {
				serverName = server.Name
				break
			}
		}

		if serverName != "" {
			break
		}
	}

	if serverName == "" {
		return nil, fmt.Errorf("tool not found: %s", toolName)
	}

	// Execute via normal execution path
	execReq := &ToolExecutionRequest{
		ServerName: serverName,
		ToolName:   toolName,
		Arguments:  arguments,
		TenantID:   tenantID,
		RequestID:  fmt.Sprintf("gateway-%d-%d", tenantID, time.Now().UnixNano()),
	}

	execResp, err := b.executor.Execute(ctx, execReq)
	if err != nil {
		return nil, err
	}

	if execResp.IsError {
		return nil, fmt.Errorf("tool execution failed: %v", execResp.Result)
	}

	return execResp.Result, nil
}

// InvalidateToolCache invalidates cached tool models for a tenant
func (b *GatewayMCPBridge) InvalidateToolCache(tenantID int64) {
	b.mu.Lock()
	delete(b.toolModels, tenantID)
	b.mu.Unlock()
}

// InvalidateAllToolCache invalidates all cached tool models
func (b *GatewayMCPBridge) InvalidateAllToolCache() {
	b.mu.Lock()
	b.toolModels = make(map[int64][]ToolDefinition)
	b.mu.Unlock()
}

// ============================================================================
// LLM TOOL INJECTION
// ============================================================================

// ToolInjectionContext represents context for tool injection into LLM
type ToolInjectionContext struct {
	TenantID     int64
	APIKeyID     int64
	AvailableTools []ToolDefinition
	ToolExecutor ToolExecutor
	Logger       Logger
}

// FormatToolsForLLM formats tool definitions for inclusion in LLM system prompt
// Returns a formatted string suitable for injection into OpenAI API compatible models
func (tic *ToolInjectionContext) FormatToolsForLLM() string {
	if len(tic.AvailableTools) == 0 {
		return ""
	}

	// For OpenAI format:
	// Tools array should be included in function_tools parameter
	// This returns the formatted structure
	result := "You have access to the following tools:\n\n"

	for i, tool := range tic.AvailableTools {
		result += fmt.Sprintf("%d. %s: %s\n", i+1, tool.Name, tool.Description)
		result += fmt.Sprintf("   Input schema: %v\n", tool.InputSchema)
		if tool.Timeout != nil {
			result += fmt.Sprintf("   Timeout: %d seconds\n", *tool.Timeout)
		}
		result += "\n"
	}

	result += "When you need to use a tool, respond with:\n"
	result += "<tool_call>\n"
	result += "{\n"
	result += "  \"tool\": \"tool_name\",\n"
	result += "  \"arguments\": {\"key\": \"value\"}\n"
	result += "}\n"
	result += "</tool_call>\n"

	return result
}

// ============================================================================
// TOOL CALL INTERCEPTION
// ============================================================================

// ToolCallInterceptor intercepts tool calls from LLM responses
type ToolCallInterceptor struct {
	bridge *GatewayMCPBridge
	logger Logger
}

// NewToolCallInterceptor creates a new tool call interceptor
func NewToolCallInterceptor(bridge *GatewayMCPBridge, logger Logger) *ToolCallInterceptor {
	return &ToolCallInterceptor{
		bridge: bridge,
		logger: logger,
	}
}

// InterceptAndExecute intercepts a tool call from LLM response and executes it
func (tci *ToolCallInterceptor) InterceptAndExecute(ctx context.Context, tenantID int64, toolCall *ToolCall) (interface{}, error) {
	tci.logger.Debug("Intercepted tool call",
		"tenant_id", tenantID,
		"tool", toolCall.Name,
		"args_count", len(toolCall.Arguments),
	)

	// Execute the tool via the bridge
	result, err := tci.bridge.ExecuteToolFromGateway(ctx, tenantID, toolCall.Name, toolCall.Arguments)
	if err != nil {
		tci.logger.Error("Tool execution failed",
			"tool", toolCall.Name,
			"error", err,
		)
		return nil, err
	}

	return result, nil
}

// ============================================================================
// METRICS & MONITORING
// ============================================================================

// ToolUsageMetrics tracks usage metrics for tools
type ToolUsageMetrics struct {
	ToolName          string
	TenantID          int64
	CallCount         int64
	SuccessCount      int64
	FailureCount      int64
	TotalExecutionMs  int64
	AvgExecutionMs    float64
	MaxExecutionMs    int
	MinExecutionMs    int
	LastCalledAt      time.Time
	LastError         string
}

// ToolUsageTracker tracks tool usage metrics
type ToolUsageTracker struct {
	mu      sync.RWMutex
	metrics map[string]*ToolUsageMetrics // tool_name -> metrics
}

// NewToolUsageTracker creates a new tool usage tracker
func NewToolUsageTracker() *ToolUsageTracker {
	return &ToolUsageTracker{
		metrics: make(map[string]*ToolUsageMetrics),
	}
}

// RecordToolExecution records a tool execution
func (tut *ToolUsageTracker) RecordToolExecution(tenantID int64, toolName string, executionMs int, success bool, errMsg string) {
	tut.mu.Lock()
	defer tut.mu.Unlock()

	key := fmt.Sprintf("%d:%s", tenantID, toolName)

	if _, ok := tut.metrics[key]; !ok {
		tut.metrics[key] = &ToolUsageMetrics{
			ToolName:       toolName,
			TenantID:       tenantID,
			MinExecutionMs: executionMs,
			MaxExecutionMs: executionMs,
		}
	}

	m := tut.metrics[key]
	m.CallCount++
	m.TotalExecutionMs += int64(executionMs)
	m.AvgExecutionMs = float64(m.TotalExecutionMs) / float64(m.CallCount)
	m.LastCalledAt = time.Now()

	if executionMs > m.MaxExecutionMs {
		m.MaxExecutionMs = executionMs
	}
	if executionMs < m.MinExecutionMs {
		m.MinExecutionMs = executionMs
	}

	if success {
		m.SuccessCount++
	} else {
		m.FailureCount++
		m.LastError = errMsg
	}
}

// GetMetrics retrieves metrics for a specific tool
func (tut *ToolUsageTracker) GetMetrics(tenantID int64, toolName string) *ToolUsageMetrics {
	tut.mu.RLock()
	defer tut.mu.RUnlock()

	key := fmt.Sprintf("%d:%s", tenantID, toolName)
	return tut.metrics[key]
}

// GetAllMetrics retrieves all metrics
func (tut *ToolUsageTracker) GetAllMetrics() map[string]*ToolUsageMetrics {
	tut.mu.RLock()
	defer tut.mu.RUnlock()

	result := make(map[string]*ToolUsageMetrics)
	for k, v := range tut.metrics {
		result[k] = v
	}

	return result
}
