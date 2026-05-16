package internal

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// ============================================================================
// SERVER DISCOVERY MANAGER
// ============================================================================

// DiscoveryManager manages server discovery and health checking
type DiscoveryManager struct {
	config        *DiscoveryConfig
	registry      ServerRegistry
	servers       map[string]*ServerStatus
	clients       map[string]MCPClient
	mu            sync.RWMutex
	ticker        *time.Ticker
	stopChan      chan struct{}
	wg            sync.WaitGroup
	cache         ToolCache
	logger        Logger
}

// NewDiscoveryManager creates a new discovery manager
func NewDiscoveryManager(config *DiscoveryConfig, cache ToolCache, logger Logger) *DiscoveryManager {
	return &DiscoveryManager{
		config:   config,
		servers:  make(map[string]*ServerStatus),
		clients:  make(map[string]MCPClient),
		stopChan: make(chan struct{}),
		cache:    cache,
		logger:   logger,
	}
}

// Start begins the discovery and health check process
func (dm *DiscoveryManager) Start(ctx context.Context) error {
	if !dm.config.Enabled {
		dm.logger.Info("Discovery disabled")
		return nil
	}

	dm.logger.Info("Starting MCP server discovery",
		"registry_url", dm.config.RegistryURL,
		"poll_interval", dm.config.PollingInterval,
	)

	// Perform initial discovery
	if err := dm.discoverServers(ctx); err != nil {
		dm.logger.Error("Initial discovery failed", "error", err)
		return err
	}

	// Start periodic health checks
	dm.startHealthCheckLoop()

	return nil
}

// Stop stops the discovery process
func (dm *DiscoveryManager) Stop(ctx context.Context) error {
	close(dm.stopChan)
	dm.wg.Wait()

	// Close all client connections
	dm.mu.Lock()
	defer dm.mu.Unlock()

	for name, client := range dm.clients {
		if err := client.Disconnect(ctx); err != nil {
			dm.logger.Error("Failed to disconnect client", "server", name, "error", err)
		}
	}

	dm.logger.Info("Discovery manager stopped")
	return nil
}

// RegisterServer registers a new MCP server
func (dm *DiscoveryManager) RegisterServer(ctx context.Context, config *ServerConfig) error {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	dm.logger.Info("Registering MCP server", "name", config.Name, "endpoint", config.Endpoint)

	// Create and connect client
	client, err := dm.createClient(config)
	if err != nil {
		return fmt.Errorf("failed to create client: %w", err)
	}

	if err := client.Connect(ctx); err != nil {
		return fmt.Errorf("failed to connect to server: %w", err)
	}

	// Get server health status
	status, err := client.Health(ctx)
	if err != nil {
		client.Close()
		return fmt.Errorf("health check failed: %w", err)
	}

	// Cache tools
	tools, err := client.ListTools(ctx)
	if err != nil {
		dm.logger.Warn("Failed to list tools", "server", config.Name, "error", err)
	} else {
		dm.cache.Set(config.Name, tools)
	}

	// Store client and status
	dm.servers[config.Name] = status
	dm.clients[config.Name] = client

	dm.logger.Info("Server registered successfully", "name", config.Name, "tools", status.ToolsCount)
	return nil
}

// GetServer retrieves server information
func (dm *DiscoveryManager) GetServer(ctx context.Context, serverName string) (*ServerStatus, error) {
	dm.mu.RLock()
	status, exists := dm.servers[serverName]
	dm.mu.RUnlock()

	if !exists {
		return nil, fmt.Errorf("server not found: %s", serverName)
	}

	return status, nil
}

// ListServers returns all registered servers
func (dm *DiscoveryManager) ListServers(ctx context.Context) ([]ServerStatus, error) {
	dm.mu.RLock()
	defer dm.mu.RUnlock()

	servers := make([]ServerStatus, 0, len(dm.servers))
	for _, status := range dm.servers {
		servers = append(servers, *status)
	}

	return servers, nil
}

// DeregisterServer removes a server from the registry
func (dm *DiscoveryManager) DeregisterServer(ctx context.Context, serverName string) error {
	dm.mu.Lock()
	defer dm.mu.Unlock()

	client, exists := dm.clients[serverName]
	if !exists {
		return fmt.Errorf("server not found: %s", serverName)
	}

	if err := client.Disconnect(ctx); err != nil {
		dm.logger.Error("Failed to disconnect client", "server", serverName, "error", err)
	}

	delete(dm.servers, serverName)
	delete(dm.clients, serverName)
	dm.cache.Invalidate(serverName)

	dm.logger.Info("Server deregistered", "name", serverName)
	return nil
}

// RefreshServerStatus updates the health status of a server
func (dm *DiscoveryManager) RefreshServerStatus(ctx context.Context, serverName string) error {
	dm.mu.Lock()
	client, exists := dm.clients[serverName]
	dm.mu.Unlock()

	if !exists {
		return fmt.Errorf("server not found: %s", serverName)
	}

	status, err := client.Health(ctx)
	if err != nil {
		dm.logger.Error("Health check failed", "server", serverName, "error", err)
		return err
	}

	dm.mu.Lock()
	dm.servers[serverName] = status
	dm.mu.Unlock()

	return nil
}

// GetClient returns the MCP client for a specific server
func (dm *DiscoveryManager) GetClient(serverName string) (MCPClient, error) {
	dm.mu.RLock()
	defer dm.mu.RUnlock()

	client, exists := dm.clients[serverName]
	if !exists {
		return nil, fmt.Errorf("server not found: %s", serverName)
	}

	return client, nil
}

// GetClientByToolName finds the server that provides a specific tool
func (dm *DiscoveryManager) GetClientByToolName(toolName string) (MCPClient, string, error) {
	dm.mu.RLock()
	servers := dm.servers
	clients := dm.clients
	dm.mu.RUnlock()

	for serverName, status := range servers {
		if status.Status != "healthy" {
			continue
		}

		// Check cache first for tool metadata
		client := clients[serverName]
		if tools, err := client.ListTools(context.Background()); err == nil {
			for _, tool := range tools {
				if tool.Name == toolName {
					return client, serverName, nil
				}
			}
		}
	}

	return nil, "", fmt.Errorf("tool not found: %s", toolName)
}

// ============================================================================
// PRIVATE METHODS
// ============================================================================

// discoverServers performs the initial server discovery
func (dm *DiscoveryManager) discoverServers(ctx context.Context) error {
	// Load servers from configuration
	// This would typically load from YAML or environment
	dm.logger.Debug("Loading servers from configuration")

	// Servers would be loaded and registered here
	// This is a placeholder for the actual implementation
	return nil
}

// startHealthCheckLoop starts the periodic health check loop
func (dm *DiscoveryManager) startHealthCheckLoop() {
	dm.ticker = time.NewTicker(dm.config.PollingInterval)

	dm.wg.Add(1)
	go func() {
		defer dm.wg.Done()

		for {
			select {
			case <-dm.stopChan:
				dm.ticker.Stop()
				return
			case <-dm.ticker.C:
				dm.performHealthChecks()
			}
		}
	}()
}

// performHealthChecks performs health checks on all servers
func (dm *DiscoveryManager) performHealthChecks() {
	dm.mu.RLock()
	servers := make([]string, 0, len(dm.servers))
	for name := range dm.servers {
		servers = append(servers, name)
	}
	dm.mu.RUnlock()

	// Perform health checks concurrently
	semaphore := make(chan struct{}, dm.config.MaxConcurrent)
	var wg sync.WaitGroup

	for _, serverName := range servers {
		wg.Add(1)
		go func(name string) {
			defer wg.Done()

			semaphore <- struct{}{}
			defer func() { <-semaphore }()

			ctx, cancel := context.WithTimeout(
				context.Background(),
				dm.config.HealthCheckTimeout,
			)
			defer cancel()

			if err := dm.RefreshServerStatus(ctx, name); err != nil {
				dm.logger.Warn("Health check failed", "server", name, "error", err)
			}
		}(serverName)
	}

	wg.Wait()
}

// createClient creates an MCP client for a server
func (dm *DiscoveryManager) createClient(config *ServerConfig) (MCPClient, error) {
	switch config.Type {
	case ServerTypeHTTP:
		return NewHTTPClient(config, dm.logger), nil
	case ServerTypeStdio:
		return NewStdioClient(config, dm.logger), nil
	default:
		return nil, fmt.Errorf("unknown server type: %s", config.Type)
	}
}

// ============================================================================
// LOGGER INTERFACE (minimal)
// ============================================================================

// Logger is a minimal logging interface
type Logger interface {
	Debug(msg string, args ...interface{})
	Info(msg string, args ...interface{})
	Warn(msg string, args ...interface{})
	Error(msg string, args ...interface{})
}

// NewHTTPClient creates a new HTTP-based MCP client
func NewHTTPClient(config *ServerConfig, logger Logger) MCPClient {
	return &HTTPClient{
		config: config,
		logger: logger,
	}
}

// NewStdioClient creates a new stdio-based MCP client
func NewStdioClient(config *ServerConfig, logger Logger) MCPClient {
	return &StdioClient{
		config: config,
		logger: logger,
	}
}

// Placeholder client implementations
type HTTPClient struct {
	config *ServerConfig
	logger Logger
}

func (c *HTTPClient) Connect(ctx context.Context) error       { return nil }
func (c *HTTPClient) Disconnect(ctx context.Context) error   { return nil }
func (c *HTTPClient) IsConnected() bool                       { return true }
func (c *HTTPClient) ListTools(ctx context.Context) ([]ToolDefinition, error) { return nil, nil }
func (c *HTTPClient) CallTool(ctx context.Context, toolName string, args map[string]interface{}) (interface{}, error) { return nil, nil }
func (c *HTTPClient) Health(ctx context.Context) (*ServerStatus, error) { return &ServerStatus{Status: "healthy"}, nil }
func (c *HTTPClient) Close() error                            { return nil }

type StdioClient struct {
	config *ServerConfig
	logger Logger
}

func (c *StdioClient) Connect(ctx context.Context) error       { return nil }
func (c *StdioClient) Disconnect(ctx context.Context) error   { return nil }
func (c *StdioClient) IsConnected() bool                       { return true }
func (c *StdioClient) ListTools(ctx context.Context) ([]ToolDefinition, error) { return nil, nil }
func (c *StdioClient) CallTool(ctx context.Context, toolName string, args map[string]interface{}) (interface{}, error) { return nil, nil }
func (c *StdioClient) Health(ctx context.Context) (*ServerStatus, error) { return &ServerStatus{Status: "healthy"}, nil }
func (c *StdioClient) Close() error                            { return nil }
