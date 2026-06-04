package main

// Agent represents a registered agent in the system
type Agent struct {
	ID           string            `json:"id"`
	Type         string            `json:"type"`
	Capabilities []string          `json:"capabilities"`
	Endpoints    AgentEndpoints    `json:"endpoints"`
	Region       string            `json:"region"`
	Status       string            `json:"status"` // active, inactive, unhealthy
	Metadata     map[string]string `json:"metadata"`
	RegisteredAt int64             `json:"registered_at"`
	LastHeartbeat int64             `json:"last_heartbeat"`
}

// AgentEndpoints defines how to communicate with an agent
type AgentEndpoints struct {
	GRPC string `json:"grpc"`
	HTTP string `json:"http"`
	WS   string `json:"ws"`
}

// Message represents a point-to-point message between agents
type Message struct {
	ID            string                 `json:"id"`
	FromAgentID   string                 `json:"from_agent_id"`
	ToAgentID     string                 `json:"to_agent_id"`
	MessageType   string                 `json:"message_type"`
	Payload       map[string]interface{} `json:"payload"`
	Status        string                 `json:"status"` // pending, sent, delivered, failed
	Timestamp     int64                  `json:"timestamp"`
	TimeoutMs     int64                  `json:"timeout_ms"`
	RetryCount    int                    `json:"retry_count"`
	MaxRetries    int                    `json:"max_retries"`
	CorrelationID string                 `json:"correlation_id"`
}

// Event represents a publish-subscribe event
type Event struct {
	ID         string                 `json:"id"`
	EventType  string                 `json:"event_type"`
	SourceAgent string                `json:"source_agent"`
	Payload    map[string]interface{} `json:"payload"`
	Timestamp  int64                  `json:"timestamp"`
	Topics     []string               `json:"topics"`
}

// Task represents a distributed task for agents
type Task struct {
	ID              string                 `json:"id"`
	TaskType        string                 `json:"task_type"`
	SourceAgent     string                 `json:"source_agent"`
	TargetAgents    []string               `json:"target_agents"`
	Payload         map[string]interface{} `json:"payload"`
	Status          string                 `json:"status"` // pending, assigned, completed, failed
	CreatedAt       int64                  `json:"created_at"`
	Deadline        int64                  `json:"deadline"`
	Priority        int                    `json:"priority"`
	Results         map[string]interface{} `json:"results"`
}

// HealthCheckRequest for agent health verification
type HealthCheckRequest struct {
	AgentID string `json:"agent_id"`
}

// HealthCheckResponse from agent
type HealthCheckResponse struct {
	AgentID  string `json:"agent_id"`
	Status   string `json:"status"`
	Uptime   int64  `json:"uptime"`
	Timestamp int64 `json:"timestamp"`
}

// MessageResponse is the response to a sent message
type MessageResponse struct {
	ID             string      `json:"id"`
	Status         string      `json:"status"`
	Delivered      bool        `json:"delivered"`
	ResponseBody   interface{} `json:"response_body"`
	Error          string      `json:"error,omitempty"`
	DeliveryTimeMs int64       `json:"delivery_time_ms"`
}

// BatchMessageRequest for sending multiple messages
type BatchMessageRequest struct {
	Messages []Message `json:"messages"`
}

// BatchMessageResponse for batch send results
type BatchMessageResponse struct {
	Successful int                `json:"successful"`
	Failed     int                `json:"failed"`
	Results    []MessageResponse  `json:"results"`
}

// RegisterAgentRequest for agent registration
type RegisterAgentRequest struct {
	AgentID      string            `json:"agent_id" binding:"required"`
	AgentType    string            `json:"agent_type" binding:"required"`
	Capabilities []string          `json:"capabilities"`
	Endpoints    AgentEndpoints    `json:"endpoints" binding:"required"`
	Region       string            `json:"region"`
	Metadata     map[string]string `json:"metadata"`
}

// SearchAgentsRequest for discovering agents by capability
type SearchAgentsRequest struct {
	Capability string `json:"capability" binding:"required"`
	Region     string `json:"region"`
	Status     string `json:"status"`
}

// SendMessageRequest for sending a message
type SendMessageRequest struct {
	ToAgentID     string                 `json:"to_agent_id" binding:"required"`
	FromAgentID   string                 `json:"from_agent_id" binding:"required"`
	MessageType   string                 `json:"message_type" binding:"required"`
	Payload       map[string]interface{} `json:"payload"`
	TimeoutMs     int64                  `json:"timeout_ms"`
	CorrelationID string                 `json:"correlation_id"`
}

// PublishEventRequest for publishing an event
type PublishEventRequest struct {
	EventType   string                 `json:"event_type" binding:"required"`
	SourceAgent string                 `json:"source_agent" binding:"required"`
	Payload     map[string]interface{} `json:"payload"`
	Topics      []string               `json:"topics"`
}

// DistributeTaskRequest for distributing a task
type DistributeTaskRequest struct {
	TaskType     string                 `json:"task_type" binding:"required"`
	SourceAgent  string                 `json:"source_agent" binding:"required"`
	TargetAgents []string               `json:"target_agents"`
	Payload      map[string]interface{} `json:"payload"`
	Deadline     int64                  `json:"deadline"`
	Priority     int                    `json:"priority"`
}

// TaskResultRequest for reporting task results
type TaskResultRequest struct {
	TaskID      string                 `json:"task_id" binding:"required"`
	AgentID     string                 `json:"agent_id" binding:"required"`
	Status      string                 `json:"status" binding:"required"`
	Result      map[string]interface{} `json:"result"`
	Error       string                 `json:"error"`
}
