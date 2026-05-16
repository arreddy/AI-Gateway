package internal

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// ============================================================================
// TOOL EXECUTOR
// ============================================================================

// DefaultToolExecutor implements the ToolExecutor interface
type DefaultToolExecutor struct {
	discoverer ServerDiscoverer
	cache      ToolCache
	logger     Logger
	mu         sync.RWMutex
	asyncCalls map[string]chan *ToolExecutionResponse
}

// NewToolExecutor creates a new tool executor
func NewToolExecutor(discoverer ServerDiscoverer, cache ToolCache, logger Logger) *DefaultToolExecutor {
	return &DefaultToolExecutor{
		discoverer: discoverer,
		cache:      cache,
		logger:     logger,
		asyncCalls: make(map[string]chan *ToolExecutionResponse),
	}
}

// Execute synchronously executes a tool
func (te *DefaultToolExecutor) Execute(ctx context.Context, req *ToolExecutionRequest) (*ToolExecutionResponse, error) {
	startTime := time.Now()

	// Validate input
	if err := validateToolRequest(req); err != nil {
		return nil, fmt.Errorf("invalid tool request: %w", err)
	}

	// Find the MCP client for this server
	client, err := te.discoverer.GetClient(req.ServerName)
	if err != nil {
		return nil, fmt.Errorf("server not found: %w", err)
	}

	// Execute the tool
	result, err := client.CallTool(ctx, req.ToolName, req.Arguments)
	if err != nil {
		te.logger.Error("Tool execution failed",
			"server", req.ServerName,
			"tool", req.ToolName,
			"error", err,
		)
		return &ToolExecutionResponse{
			RequestID:       req.RequestID,
			ServerName:      req.ServerName,
			ToolName:        req.ToolName,
			Result:          fmt.Sprintf("error: %v", err),
			IsError:         true,
			ExecutionTimeMs: int(time.Since(startTime).Milliseconds()),
			Timestamp:       time.Now(),
		}, nil
	}

	te.logger.Debug("Tool execution completed",
		"server", req.ServerName,
		"tool", req.ToolName,
		"duration_ms", time.Since(startTime).Milliseconds(),
	)

	return &ToolExecutionResponse{
		RequestID:       req.RequestID,
		ServerName:      req.ServerName,
		ToolName:        req.ToolName,
		Result:          result,
		IsError:         false,
		ExecutionTimeMs: int(time.Since(startTime).Milliseconds()),
		Timestamp:       time.Now(),
	}, nil
}

// ExecuteAsync asynchronously executes a tool
func (te *DefaultToolExecutor) ExecuteAsync(ctx context.Context, req *ToolExecutionRequest, callback func(*ToolExecutionResponse, error)) {
	respChan := make(chan *ToolExecutionResponse, 1)
	errChan := make(chan error, 1)

	te.mu.Lock()
	te.asyncCalls[req.RequestID] = respChan
	te.mu.Unlock()

	go func() {
		defer func() {
			te.mu.Lock()
			delete(te.asyncCalls, req.RequestID)
			te.mu.Unlock()
		}()

		resp, err := te.Execute(ctx, req)
		if err != nil {
			errChan <- err
		} else {
			respChan <- resp
		}
	}()

	go func() {
		select {
		case resp := <-respChan:
			callback(resp, nil)
		case err := <-errChan:
			callback(nil, err)
		case <-ctx.Done():
			callback(nil, ctx.Err())
		}
	}()
}

// ============================================================================
// TOOL CACHE IMPLEMENTATION
// ============================================================================

// MemoryToolCache is an in-memory implementation of ToolCache
type MemoryToolCache struct {
	mu       sync.RWMutex
	entries  map[string]map[string]*CacheEntry // [server][tool] -> entry
	maxSize  int64
	ttl      time.Duration
	stats    CacheStats
	stopChan chan struct{}
}

// NewMemoryToolCache creates a new in-memory tool cache
func NewMemoryToolCache(maxSize int64, ttl time.Duration) *MemoryToolCache {
	cache := &MemoryToolCache{
		entries:  make(map[string]map[string]*CacheEntry),
		maxSize:  maxSize,
		ttl:      ttl,
		stopChan: make(chan struct{}),
	}

	// Start cleanup goroutine
	go cache.cleanupExpired()

	return cache
}

// Get retrieves a tool definition from cache
func (c *MemoryToolCache) Get(serverName, toolName string) (*ToolDefinition, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.stats.TotalRequests++

	serverCache, ok := c.entries[serverName]
	if !ok {
		c.stats.CacheMisses++
		return nil, false
	}

	entry, ok := serverCache[toolName]
	if !ok || time.Now().After(entry.ExpiresAt) {
		c.stats.CacheMisses++
		return nil, false
	}

	entry.HitCount++
	c.stats.CacheHits++
	c.updateHitRate()

	return &entry.Tool, true
}

// Set caches tool definitions
func (c *MemoryToolCache) Set(serverName string, tools []ToolDefinition) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, ok := c.entries[serverName]; !ok {
		c.entries[serverName] = make(map[string]*CacheEntry)
	}

	now := time.Now()
	expiresAt := now.Add(c.ttl)

	for _, tool := range tools {
		c.entries[serverName][tool.Name] = &CacheEntry{
			Tool:      tool,
			Server:    serverName,
			ExpiresAt: expiresAt,
			HitCount:  0,
		}
	}

	c.updateSize()
}

// Invalidate removes all cached tools for a server
func (c *MemoryToolCache) Invalidate(serverName string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	delete(c.entries, serverName)
	c.updateSize()
}

// Stats returns cache statistics
func (c *MemoryToolCache) Stats() CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return c.stats
}

// Clear removes all entries from cache
func (c *MemoryToolCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.entries = make(map[string]map[string]*CacheEntry)
	c.stats = CacheStats{}
}

// Close stops the cleanup goroutine
func (c *MemoryToolCache) Close() {
	close(c.stopChan)
}

// ============================================================================
// PRIVATE CACHE METHODS
// ============================================================================

// updateSize updates the current cache size
func (c *MemoryToolCache) updateSize() {
	size := int64(0)
	for _, serverCache := range c.entries {
		size += int64(len(serverCache))
	}
	c.stats.CurrentSize = size
	c.stats.MaxSize = c.maxSize
}

// updateHitRate updates the cache hit rate percentage
func (c *MemoryToolCache) updateHitRate() {
	if c.stats.TotalRequests > 0 {
		c.stats.HitRatePercent = float64(c.stats.CacheHits) / float64(c.stats.TotalRequests) * 100
	}
}

// cleanupExpired removes expired entries from cache
func (c *MemoryToolCache) cleanupExpired() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-c.stopChan:
			return
		case <-ticker.C:
			c.performCleanup()
		}
	}
}

// performCleanup performs cleanup of expired entries
func (c *MemoryToolCache) performCleanup() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now()
	evicted := int64(0)

	for serverName := range c.entries {
		toDelete := []string{}

		for toolName, entry := range c.entries[serverName] {
			if now.After(entry.ExpiresAt) {
				toDelete = append(toDelete, toolName)
				evicted++
			}
		}

		for _, toolName := range toDelete {
			delete(c.entries[serverName], toolName)
		}

		// Remove server if empty
		if len(c.entries[serverName]) == 0 {
			delete(c.entries, serverName)
		}
	}

	if evicted > 0 {
		c.stats.EvictionCount += evicted
		c.updateSize()
	}
}

// ============================================================================
// TOOL REQUEST VALIDATION
// ============================================================================

// validateToolRequest validates a tool execution request
func validateToolRequest(req *ToolExecutionRequest) error {
	if req == nil {
		return fmt.Errorf("request cannot be nil")
	}

	if req.ServerName == "" {
		return fmt.Errorf("server_name is required")
	}

	if req.ToolName == "" {
		return fmt.Errorf("tool_name is required")
	}

	if req.TenantID == 0 {
		return fmt.Errorf("tenant_id is required")
	}

	if req.RequestID == "" {
		return fmt.Errorf("request_id is required")
	}

	return nil
}

// ============================================================================
// TOOL ROUTER
// ============================================================================

// ToolRouter routes tool calls to appropriate MCP servers
type ToolRouter struct {
	discoverer ServerDiscoverer
	executor   ToolExecutor
	cache      ToolCache
	logger     Logger
	mu         sync.RWMutex
	toolMap    map[string]string // tool_name -> server_name
}

// NewToolRouter creates a new tool router
func NewToolRouter(discoverer ServerDiscoverer, executor ToolExecutor, cache ToolCache, logger Logger) *ToolRouter {
	return &ToolRouter{
		discoverer: discoverer,
		executor:   executor,
		cache:      cache,
		logger:     logger,
		toolMap:    make(map[string]string),
	}
}

// BuildToolMap builds a map of all available tools to their servers
func (tr *ToolRouter) BuildToolMap(ctx context.Context) error {
	servers, err := tr.discoverer.ListServers(ctx)
	if err != nil {
		return fmt.Errorf("failed to list servers: %w", err)
	}

	toolMap := make(map[string]string)

	for _, server := range servers {
		if server.Status != "healthy" {
			continue
		}

		// Get client and list tools
		client, err := tr.discoverer.GetClient(server.Name)
		if err != nil {
			tr.logger.Warn("Failed to get client", "server", server.Name, "error", err)
			continue
		}

		tools, err := client.ListTools(ctx)
		if err != nil {
			tr.logger.Warn("Failed to list tools", "server", server.Name, "error", err)
			continue
		}

		for _, tool := range tools {
			toolMap[tool.Name] = server.Name
		}
	}

	tr.mu.Lock()
	tr.toolMap = toolMap
	tr.mu.Unlock()

	tr.logger.Info("Tool map built", "tools_count", len(toolMap))
	return nil
}

// ResolveServer resolves which server provides a specific tool
func (tr *ToolRouter) ResolveServer(toolName string) (string, error) {
	tr.mu.RLock()
	defer tr.mu.RUnlock()

	serverName, ok := tr.toolMap[toolName]
	if !ok {
		return "", fmt.Errorf("tool not found: %s", toolName)
	}

	return serverName, nil
}

// ListToolsForServer lists all tools available on a specific server
func (tr *ToolRouter) ListToolsForServer(ctx context.Context, serverName string) ([]ToolDefinition, error) {
	client, err := tr.discoverer.GetClient(serverName)
	if err != nil {
		return nil, fmt.Errorf("server not found: %w", err)
	}

	tools, err := client.ListTools(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to list tools: %w", err)
	}

	return tools, nil
}

// ListAllTools lists all available tools across all servers
func (tr *ToolRouter) ListAllTools(ctx context.Context) (map[string][]ToolDefinition, error) {
	servers, err := tr.discoverer.ListServers(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to list servers: %w", err)
	}

	result := make(map[string][]ToolDefinition)

	for _, server := range servers {
		if server.Status != "healthy" {
			continue
		}

		tools, err := tr.ListToolsForServer(ctx, server.Name)
		if err != nil {
			tr.logger.Warn("Failed to get tools for server", "server", server.Name, "error", err)
			continue
		}

		result[server.Name] = tools
	}

	return result, nil
}
