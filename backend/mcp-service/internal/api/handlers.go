package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"

	"github.com/your-org/astra-gateway/mcp/internal"
)

// ============================================================================
// HTTP HANDLERS FOR MCP SERVICE
// ============================================================================

// APIHandler handles HTTP requests for MCP service
type APIHandler struct {
	discoverer ServerDiscoverer
	executor   ToolExecutor
	router     ToolRouter
	logger     Logger
}

// NewAPIHandler creates a new API handler
func NewAPIHandler(
	discoverer internal.ServerDiscoverer,
	executor internal.ToolExecutor,
	router *internal.ToolRouter,
	logger internal.Logger,
) *APIHandler {
	return &APIHandler{
		discoverer: discoverer,
		executor:   executor,
		router:     router,
		logger:     logger,
	}
}

// ============================================================================
// HANDLER FUNCTIONS
// ============================================================================

// HandleListTools lists all available tools
// GET /v1/tools/list
func (h *APIHandler) HandleListTools(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract query parameters
	serverName := r.URL.Query().Get("server")
	capability := r.URL.Query().Get("capability")

	var tools []internal.ToolDefinition

	if serverName != "" {
		// List tools for specific server
		var err error
		tools, err = h.router.ListToolsForServer(ctx, serverName)
		if err != nil {
			h.respondError(w, http.StatusNotFound, fmt.Sprintf("Server not found: %v", err))
			return
		}
	} else {
		// List all tools
		toolsByServer, err := h.router.ListAllTools(ctx)
		if err != nil {
			h.respondError(w, http.StatusInternalServerError, fmt.Sprintf("Failed to list tools: %v", err))
			return
		}

		for _, serverTools := range toolsByServer {
			tools = append(tools, serverTools...)
		}
	}

	// Filter by capability if provided
	if capability != "" {
		filtered := []internal.ToolDefinition{}
		for _, tool := range tools {
			for _, cap := range tool.Capabilities {
				if cap == capability {
					filtered = append(filtered, tool)
					break
				}
			}
		}
		tools = filtered
	}

	response := internal.ListToolsResponse{
		Tools: tools,
		Count: len(tools),
	}

	h.respondJSON(w, http.StatusOK, response)
}

// HandleCallTool executes a tool
// POST /v1/tools/call
func (h *APIHandler) HandleCallTool(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Parse request body
	var callReq internal.CallToolRequest
	if err := json.NewDecoder(r.Body).Decode(&callReq); err != nil {
		h.respondError(w, http.StatusBadRequest, fmt.Sprintf("Invalid request body: %v", err))
		return
	}

	// Resolve server for tool
	serverName, err := h.router.ResolveServer(callReq.Tool)
	if err != nil {
		h.respondError(w, http.StatusNotFound, fmt.Sprintf("Tool not found: %v", err))
		return
	}

	// Extract tenant and API key from context
	tenantID := extractTenantID(r)
	apiKeyID := extractAPIKeyID(r)
	requestID := extractRequestID(r)

	if tenantID == 0 || apiKeyID == 0 {
		h.respondError(w, http.StatusUnauthorized, "Missing authentication information")
		return
	}

	// Execute tool
	execReq := &internal.ToolExecutionRequest{
		ServerName: serverName,
		ToolName:   callReq.Tool,
		Arguments:  callReq.Arguments,
		TenantID:   tenantID,
		APIKeyID:   apiKeyID,
		RequestID:  requestID,
	}

	execResp, err := h.executor.Execute(ctx, execReq)
	if err != nil {
		h.respondError(w, http.StatusInternalServerError, fmt.Sprintf("Tool execution failed: %v", err))
		return
	}

	// Convert execution response to call response
	response := internal.CallToolResponse{
		Result:          execResp.Result,
		ExecutionTimeMs: execResp.ExecutionTimeMs,
		Server:          execResp.ServerName,
		Cached:          execResp.CachedResult,
	}

	h.respondJSON(w, http.StatusOK, response)
}

// HandleListServers lists all registered MCP servers
// GET /v1/servers
func (h *APIHandler) HandleListServers(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	servers, err := h.discoverer.ListServers(ctx)
	if err != nil {
		h.respondError(w, http.StatusInternalServerError, fmt.Sprintf("Failed to list servers: %v", err))
		return
	}

	h.respondJSON(w, http.StatusOK, map[string]interface{}{
		"servers": servers,
		"count":   len(servers),
	})
}

// HandleGetServer gets information about a specific server
// GET /v1/servers/{server_name}
func (h *APIHandler) HandleGetServer(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract server name from URL
	serverName := strings.TrimPrefix(r.URL.Path, "/v1/servers/")

	status, err := h.discoverer.GetServer(ctx, serverName)
	if err != nil {
		h.respondError(w, http.StatusNotFound, fmt.Sprintf("Server not found: %v", err))
		return
	}

	h.respondJSON(w, http.StatusOK, status)
}

// HandleServerHealth checks the health of a specific server
// GET /v1/servers/{server_name}/health
func (h *APIHandler) HandleServerHealth(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract server name from URL
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/servers/"), "/")
	if len(parts) < 1 || parts[0] == "" {
		h.respondError(w, http.StatusBadRequest, "Invalid server name")
		return
	}

	serverName := parts[0]

	if err := h.discoverer.RefreshServerStatus(ctx, serverName); err != nil {
		h.respondError(w, http.StatusInternalServerError, fmt.Sprintf("Health check failed: %v", err))
		return
	}

	status, err := h.discoverer.GetServer(ctx, serverName)
	if err != nil {
		h.respondError(w, http.StatusNotFound, fmt.Sprintf("Server not found: %v", err))
		return
	}

	h.respondJSON(w, http.StatusOK, map[string]interface{}{
		"status":             status.Status,
		"uptime_percent":     status.UptimePercent,
		"response_time_ms":   status.ResponseTimeMs,
		"error_rate":         status.ErrorRate,
		"last_health_check":  status.LastHealthCheck,
	})
}

// HandleRegisterServer registers a new MCP server
// POST /v1/servers
func (h *APIHandler) HandleRegisterServer(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Parse request body
	var config internal.ServerConfig
	if err := json.NewDecoder(r.Body).Decode(&config); err != nil {
		h.respondError(w, http.StatusBadRequest, fmt.Sprintf("Invalid request body: %v", err))
		return
	}

	// Extract tenant from context
	config.TenantID = extractTenantID(r)

	if err := h.discoverer.RegisterServer(ctx, &config); err != nil {
		h.respondError(w, http.StatusInternalServerError, fmt.Sprintf("Failed to register server: %v", err))
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]string{
		"status":  "registered",
		"server":  config.Name,
		"message": fmt.Sprintf("Server %s registered successfully", config.Name),
	})
}

// HandleDeregisterServer removes an MCP server
// DELETE /v1/servers/{server_name}
func (h *APIHandler) HandleDeregisterServer(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()

	// Extract server name from URL
	serverName := strings.TrimPrefix(r.URL.Path, "/v1/servers/")

	if err := h.discoverer.DeregisterServer(ctx, serverName); err != nil {
		h.respondError(w, http.StatusNotFound, fmt.Sprintf("Server not found: %v", err))
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{
		"status":  "deregistered",
		"server":  serverName,
		"message": fmt.Sprintf("Server %s deregistered successfully", serverName),
	})
}

// ============================================================================
// HELPER METHODS
// ============================================================================

// respondJSON sends a JSON response
func (h *APIHandler) respondJSON(w http.ResponseWriter, statusCode int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)

	if err := json.NewEncoder(w).Encode(data); err != nil {
		h.logger.Error("Failed to encode response", "error", err)
	}
}

// respondError sends an error response
func (h *APIHandler) respondError(w http.ResponseWriter, statusCode int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)

	json.NewEncoder(w).Encode(map[string]interface{}{
		"error":  http.StatusText(statusCode),
		"code":   statusCode,
		"message": message,
	})
}

// extractTenantID extracts tenant ID from request context or headers
func extractTenantID(r *http.Request) int64 {
	// Implementation would extract from JWT claims or header
	// This is a placeholder
	return 1
}

// extractAPIKeyID extracts API key ID from request context
func extractAPIKeyID(r *http.Request) int64 {
	// Implementation would extract from authentication context
	// This is a placeholder
	return 1
}

// extractRequestID extracts request ID from header or generates one
func extractRequestID(r *http.Request) string {
	// Try to get from header
	if id := r.Header.Get("X-Request-ID"); id != "" {
		return id
	}

	// Would generate a unique ID using uuid or similar
	return "req-" + fmt.Sprint(int64(0))
}

// ============================================================================
// ROUTER SETUP
// ============================================================================

// SetupRoutes registers all API routes
func SetupRoutes(
	mux *http.ServeMux,
	discoverer internal.ServerDiscoverer,
	executor internal.ToolExecutor,
	router *internal.ToolRouter,
	logger internal.Logger,
) {
	handler := NewAPIHandler(discoverer, executor, router, logger)

	// Tool endpoints
	mux.HandleFunc("/v1/tools/list", handler.HandleListTools)
	mux.HandleFunc("/v1/tools/call", handler.HandleCallTool)

	// Server endpoints
	mux.HandleFunc("/v1/servers", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			handler.HandleListServers(w, r)
		case http.MethodPost:
			handler.HandleRegisterServer(w, r)
		default:
			http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		}
	})

	mux.HandleFunc("/v1/servers/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/health") {
			handler.HandleServerHealth(w, r)
		} else {
			switch r.Method {
			case http.MethodGet:
				handler.HandleGetServer(w, r)
			case http.MethodDelete:
				handler.HandleDeregisterServer(w, r)
			default:
				http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
			}
		}
	})
}

// Placeholder interfaces for compilation
type ServerDiscoverer interface {
	ListServers(ctx context.Context) ([]internal.ServerStatus, error)
	GetServer(ctx context.Context, serverName string) (*internal.ServerStatus, error)
	RegisterServer(ctx context.Context, config *internal.ServerConfig) error
	DeregisterServer(ctx context.Context, serverName string) error
	RefreshServerStatus(ctx context.Context, serverName string) error
}

type ToolExecutor interface {
	Execute(ctx context.Context, req *internal.ToolExecutionRequest) (*internal.ToolExecutionResponse, error)
}

type ToolRouter interface {
	ResolveServer(toolName string) (string, error)
	ListToolsForServer(ctx context.Context, serverName string) ([]internal.ToolDefinition, error)
	ListAllTools(ctx context.Context) (map[string][]internal.ToolDefinition, error)
}

type Logger interface {
	Error(msg string, args ...interface{})
}
