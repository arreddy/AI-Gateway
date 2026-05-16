-- Database Schema for Astra Gateway
-- PostgreSQL 14+
-- Multi-tenant architecture with tenant isolation

-- ============================================================================
-- ENUM TYPES
-- ============================================================================

CREATE TYPE tenant_status AS ENUM ('active', 'suspended', 'deleted', 'trial');
CREATE TYPE api_key_status AS ENUM ('active', 'rotated', 'revoked', 'expired');
CREATE TYPE provider_status AS ENUM ('healthy', 'degraded', 'unhealthy', 'maintenance');
CREATE TYPE model_status AS ENUM ('available', 'deprecated', 'unavailable', 'beta');
CREATE TYPE request_status AS ENUM ('success', 'partial', 'failed', 'timeout', 'cancelled');
CREATE TYPE audit_action AS ENUM ('create', 'read', 'update', 'delete', 'fallback', 'policy_violation');
CREATE TYPE governance_action AS ENUM ('allow', 'block', 'redact', 'warn', 'quarantine');
CREATE TYPE billing_event_type AS ENUM ('usage', 'adjustment', 'refund', 'promotion', 'correction');

-- ============================================================================
-- CORE TABLES
-- ============================================================================

-- Tenants (Multi-tenant isolation)
CREATE TABLE tenants (
    id BIGSERIAL PRIMARY KEY,
    external_id UUID UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    email VARCHAR(255) NOT NULL,
    status tenant_status DEFAULT 'active',
    
    -- Tier: free, starter, pro, enterprise
    tier VARCHAR(50) DEFAULT 'free',
    
    -- Rate limits and quotas
    rate_limit_rpm INT DEFAULT 60000,        -- Requests per minute
    rate_limit_tpm INT DEFAULT 90000000,     -- Tokens per minute
    monthly_token_quota BIGINT DEFAULT 100000000,
    
    -- Features enabled
    features JSONB DEFAULT '{}',             -- {ai_governance: true, advanced_routing: true}
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    
    -- Organization info
    industry VARCHAR(100),
    company_size VARCHAR(50),
    country VARCHAR(2),
    
    CONSTRAINT deleted_soft_delete CHECK (status != 'deleted' OR deleted_at IS NOT NULL)
);

CREATE INDEX idx_tenants_status ON tenants(status);
CREATE INDEX idx_tenants_external_id ON tenants(external_id);
CREATE INDEX idx_tenants_created_at ON tenants(created_at);

-- API Keys (Authentication)
CREATE TABLE api_keys (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    external_id UUID UNIQUE NOT NULL,
    
    key_hash VARCHAR(255) UNIQUE NOT NULL,  -- SHA-256 hash of actual key
    key_preview VARCHAR(20),                 -- Last 20 chars for display
    
    name VARCHAR(255),                       -- User-friendly name
    status api_key_status DEFAULT 'active',
    
    -- Permissions
    permissions TEXT[] DEFAULT ARRAY['completions:create', 'models:list'],
    
    -- Scope limitations
    allowed_models TEXT[],                   -- NULL = all models
    allowed_ip_addresses INET[],             -- NULL = all IPs
    allowed_referers TEXT[],                 -- NULL = all referers
    
    -- Usage tracking
    last_used_at TIMESTAMP WITH TIME ZONE,
    total_requests BIGINT DEFAULT 0,
    total_tokens BIGINT DEFAULT 0,
    
    -- Limits (optional override of tenant limits)
    rate_limit_rpm INT,
    rate_limit_tpm INT,
    
    -- Expiration
    expires_at TIMESTAMP WITH TIME ZONE,
    
    created_by BIGINT,                       -- user_id who created it
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    rotated_at TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT api_key_name_unique_per_tenant UNIQUE(tenant_id, name)
);

CREATE INDEX idx_api_keys_tenant_id ON api_keys(tenant_id);
CREATE INDEX idx_api_keys_status ON api_keys(status);
CREATE INDEX idx_api_keys_expires_at ON api_keys(expires_at);
CREATE INDEX idx_api_keys_key_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_last_used_at ON api_keys(last_used_at DESC);

-- Users (Team members)
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    external_id UUID UNIQUE NOT NULL,
    
    email VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    
    -- Authentication
    password_hash VARCHAR(255),
    oauth_provider VARCHAR(50),             -- 'google', 'github', 'microsoft'
    oauth_id VARCHAR(255),
    
    -- Status
    is_active BOOLEAN DEFAULT true,
    email_verified BOOLEAN DEFAULT false,
    
    -- Role-based access
    role VARCHAR(50) DEFAULT 'member',      -- 'admin', 'member', 'viewer'
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP WITH TIME ZONE,
    
    CONSTRAINT email_unique_per_tenant UNIQUE(tenant_id, email)
);

CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_oauth ON users(oauth_provider, oauth_id);

-- ============================================================================
-- PROVIDER & MODEL MANAGEMENT
-- ============================================================================

-- Providers (OpenAI, Anthropic, Google, etc.)
CREATE TABLE providers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,       -- 'openai', 'anthropic', 'google'
    display_name VARCHAR(255),
    
    status provider_status DEFAULT 'healthy',
    
    -- API Configuration
    api_base_url VARCHAR(500),
    api_timeout_ms INT DEFAULT 30000,
    
    -- Rate limits
    rate_limit_rps INT,                     -- Requests per second
    rate_limit_rpm INT,
    
    -- Pricing (in USD)
    pricing JSONB,                          -- {"gpt-4": {"input": 0.03, "output": 0.06}}
    
    -- Health check
    health_check_enabled BOOLEAN DEFAULT true,
    health_check_interval_seconds INT DEFAULT 60,
    
    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_providers_status ON providers(status);

-- Models (GPT-4, Claude 3, Gemini, etc.)
CREATE TABLE models (
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    
    name VARCHAR(255) NOT NULL,             -- 'gpt-4', 'claude-3-opus'
    external_name VARCHAR(255),             -- 'gpt-4-turbo-preview' (actual API name)
    
    display_name VARCHAR(255),
    description TEXT,
    
    status model_status DEFAULT 'available',
    
    -- Capabilities
    capabilities JSONB,                     -- {vision: true, function_calling: true, vision_url_support: true}
    
    -- Input/Output constraints
    context_window INT,
    max_output_tokens INT,
    
    -- Pricing (tokens per 1M tokens)
    input_price_per_mtok DECIMAL(10, 6),
    output_price_per_mtok DECIMAL(10, 6),
    
    -- Performance metrics
    avg_latency_ms INT,
    error_rate DECIMAL(5, 2),
    success_count BIGINT DEFAULT 0,
    failure_count BIGINT DEFAULT 0,
    
    -- Deprecation
    deprecated_at TIMESTAMP WITH TIME ZONE,
    sunset_at TIMESTAMP WITH TIME ZONE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT model_name_unique_per_provider UNIQUE(provider_id, name)
);

CREATE INDEX idx_models_provider_id ON models(provider_id);
CREATE INDEX idx_models_status ON models(status);
CREATE INDEX idx_models_external_name ON models(external_name);

-- Provider Credentials (per-tenant)
CREATE TABLE provider_credentials (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider_id BIGINT NOT NULL REFERENCES providers(id),
    
    -- Encrypted API key (using PG crypto)
    api_key_encrypted TEXT NOT NULL,
    
    -- Metadata
    api_key_provider_id VARCHAR(255),       -- Some providers use user identifiers
    quota_limit INT,                        -- Optional quota per provider
    
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT provider_cred_unique_per_tenant UNIQUE(tenant_id, provider_id)
);

CREATE INDEX idx_provider_credentials_tenant_id ON provider_credentials(tenant_id);
CREATE INDEX idx_provider_credentials_provider_id ON provider_credentials(provider_id);

-- ============================================================================
-- ROUTING & POLICY CONFIGURATION
-- ============================================================================

-- Routing Policies
CREATE TABLE routing_policies (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    name VARCHAR(255) NOT NULL,
    description TEXT,
    
    -- Routing strategy
    strategy VARCHAR(50),                   -- 'cost', 'latency', 'quality', 'rule_based', 'adaptive'
    
    -- Priority (lower = higher priority)
    priority INT DEFAULT 100,
    
    -- DSL for rule-based routing
    rules JSONB,                            -- Complex routing rules
    
    -- Fallback chain
    fallback_chain TEXT[],                  -- ["openai.gpt-4", "anthropic.claude-3", "mistral.large"]
    
    -- Constraints
    constraints JSONB,                      -- {cost_max: 0.10, latency_max: 5000}
    
    -- Model mappings (e.g., user requests 'gpt-4', map to preferred alternative)
    model_mappings JSONB,                   -- {"gpt-4": "gpt-4-turbo", "claude-3": "claude-3-opus"}
    
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_routing_policies_tenant_id ON routing_policies(tenant_id);
CREATE INDEX idx_routing_policies_priority ON routing_policies(priority);

-- Governance Policies
CREATE TABLE governance_policies (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    name VARCHAR(255) NOT NULL,
    description TEXT,
    
    -- Policy types
    policy_type VARCHAR(50),                -- 'pii_redaction', 'toxicity_filter', 'injection_detect', 'custom'
    
    -- Configuration
    config JSONB,                           -- Type-specific config
    
    -- Action to take
    action governance_action DEFAULT 'warn',
    
    -- Conditions
    conditions JSONB,                       -- When this policy applies
    
    is_enabled BOOLEAN DEFAULT true,
    priority INT DEFAULT 100,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_governance_policies_tenant_id ON governance_policies(tenant_id);
CREATE INDEX idx_governance_policies_type ON governance_policies(policy_type);

-- Rate Limit Configurations
CREATE TABLE rate_limit_configs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    api_key_id BIGINT REFERENCES api_keys(id) ON DELETE CASCADE,
    
    -- Limit dimensions
    limit_type VARCHAR(50),                 -- 'rpm', 'tpm', 'daily_tokens', 'concurrent_requests'
    limit_value BIGINT NOT NULL,
    
    -- Time window (in seconds)
    window_seconds INT DEFAULT 60,
    
    -- Enforcement
    is_hard_limit BOOLEAN DEFAULT true,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_rate_limit_configs_tenant_id ON rate_limit_configs(tenant_id);

-- ============================================================================
-- USAGE & BILLING TRACKING
-- ============================================================================

-- Request Logs (immutable event log)
CREATE TABLE request_logs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    api_key_id BIGINT REFERENCES api_keys(id),
    
    -- Request metadata
    request_id UUID UNIQUE NOT NULL,
    user_id BIGINT REFERENCES users(id),
    
    -- Model and provider info
    model_requested VARCHAR(255),
    model_used VARCHAR(255),
    provider_id BIGINT REFERENCES providers(id),
    
    -- Request details
    request_type VARCHAR(50),               -- 'completion', 'chat', 'embedding', 'image'
    endpoint VARCHAR(500),
    
    -- Tokens
    input_tokens INT,
    output_tokens INT,
    total_tokens INT,
    
    -- Cost calculation
    input_cost DECIMAL(15, 8),
    output_cost DECIMAL(15, 8),
    total_cost DECIMAL(15, 8),
    
    -- Status
    status request_status DEFAULT 'success',
    error_code VARCHAR(50),
    error_message TEXT,
    
    -- Performance
    latency_ms INT,
    time_to_first_token_ms INT,
    
    -- Fallback info
    fallback_provider_id BIGINT,
    fallback_count INT DEFAULT 0,
    
    -- Governance
    pii_detected BOOLEAN DEFAULT false,
    injection_detected BOOLEAN DEFAULT false,
    toxicity_score DECIMAL(5, 4),
    policies_triggered TEXT[],
    
    -- Timing
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    -- Partitioning support
    month_year DATE GENERATED ALWAYS AS (DATE_TRUNC('month', created_at)::date) STORED
);

CREATE INDEX idx_request_logs_tenant_created ON request_logs(tenant_id, created_at DESC);
CREATE INDEX idx_request_logs_request_id ON request_logs(request_id);
CREATE INDEX idx_request_logs_api_key_id ON request_logs(api_key_id);
CREATE INDEX idx_request_logs_status ON request_logs(status);
CREATE INDEX idx_request_logs_month_year ON request_logs(month_year);

-- Token Usage Summary (aggregated for billing)
CREATE TABLE token_usage_summary (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    -- Time bucket
    date DATE NOT NULL,
    hour INT,                               -- 0-23, NULL for daily summary
    
    -- Usage metrics
    total_requests BIGINT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    
    -- Cost
    total_cost DECIMAL(15, 8),
    
    -- Breakdown by model
    usage_by_model JSONB,                   -- {"gpt-4": {requests: 100, tokens: 10000, cost: 0.50}}
    
    -- Breakdown by provider
    usage_by_provider JSONB,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT summary_unique_per_tenant_time UNIQUE(tenant_id, date, hour)
);

CREATE INDEX idx_token_usage_summary_tenant_date ON token_usage_summary(tenant_id, date DESC);

-- Billing Events
CREATE TABLE billing_events (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    -- Event metadata
    event_id UUID UNIQUE NOT NULL,
    event_type billing_event_type,
    
    -- Amount
    amount DECIMAL(15, 8),
    currency VARCHAR(3) DEFAULT 'USD',
    
    -- Reference
    reference_type VARCHAR(50),             -- 'request', 'adjustment', 'refund'
    reference_id VARCHAR(255),
    
    description TEXT,
    
    -- Period
    billing_period_start DATE,
    billing_period_end DATE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_billing_events_tenant_created ON billing_events(tenant_id, created_at DESC);
CREATE INDEX idx_billing_events_event_id ON billing_events(event_id);

-- Invoices
CREATE TABLE invoices (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    invoice_number VARCHAR(100) UNIQUE NOT NULL,
    external_id VARCHAR(255),               -- Stripe invoice ID, etc.
    
    -- Period
    billing_period_start DATE NOT NULL,
    billing_period_end DATE NOT NULL,
    
    -- Amounts
    subtotal DECIMAL(15, 2),
    tax DECIMAL(15, 2),
    total DECIMAL(15, 2),
    
    -- Status
    status VARCHAR(50),                     -- 'draft', 'issued', 'paid', 'overdue', 'cancelled'
    
    -- Metadata
    line_items JSONB,                       -- [{"description": "GPT-4 usage", "amount": 100.00}]
    
    paid_at TIMESTAMP WITH TIME ZONE,
    due_at TIMESTAMP WITH TIME ZONE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invoices_tenant_created ON invoices(tenant_id, created_at DESC);
CREATE INDEX idx_invoices_status ON invoices(status);

-- ============================================================================
-- OBSERVABILITY & AUDIT
-- ============================================================================

-- Audit Logs (immutable compliance audit trail)
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    -- Actor
    user_id BIGINT REFERENCES users(id),
    actor_type VARCHAR(50),                 -- 'user', 'system', 'api'
    actor_id VARCHAR(255),
    
    -- Action
    action audit_action NOT NULL,
    resource_type VARCHAR(50),              -- 'api_key', 'policy', 'request'
    resource_id VARCHAR(255),
    
    -- Changes
    changes JSONB,                          -- Before/after values
    
    -- Context
    ip_address INET,
    user_agent TEXT,
    request_id UUID,
    
    -- Status
    success BOOLEAN DEFAULT true,
    error_message TEXT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_tenant_created ON audit_logs(tenant_id, created_at DESC);
CREATE INDEX idx_audit_logs_resource ON audit_logs(resource_type, resource_id);

-- Governance Violations
CREATE TABLE governance_violations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    request_log_id BIGINT REFERENCES request_logs(id),
    
    -- Violation details
    policy_id BIGINT REFERENCES governance_policies(id),
    violation_type VARCHAR(50),             -- 'pii', 'toxicity', 'injection'
    
    -- Severity
    severity VARCHAR(50),                   -- 'low', 'medium', 'high', 'critical'
    
    -- Details
    details JSONB,                          -- {detected: 'SSN', value: 'xxx-xx-1234', confidence: 0.99}
    
    -- Resolution
    action_taken governance_action,
    resolved_at TIMESTAMP WITH TIME ZONE,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_governance_violations_tenant ON governance_violations(tenant_id);
CREATE INDEX idx_governance_violations_policy_id ON governance_violations(policy_id);

-- ============================================================================
-- CACHING & SESSION MANAGEMENT
-- ============================================================================

-- Request Cache (populated by Redis, logged in PG for analytics)
CREATE TABLE request_cache (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    -- Hash of request (content + model + params)
    request_hash VARCHAR(255) UNIQUE NOT NULL,
    
    -- Cached response
    response_data JSONB,
    
    -- Metadata
    hits INT DEFAULT 0,
    ttl_seconds INT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    accessed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_request_cache_tenant_hash ON request_cache(tenant_id, request_hash);
CREATE INDEX idx_request_cache_expires_at ON request_cache(expires_at);

-- ============================================================================
-- PROVIDER HEALTH & METRICS
-- ============================================================================

-- Provider Health Status
CREATE TABLE provider_health_checks (
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    
    -- Status
    status provider_status,
    latency_ms INT,
    
    -- Error info
    error_code VARCHAR(50),
    error_message TEXT,
    
    -- Metrics
    success BOOLEAN,
    response_time_ms INT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_provider_health_checks_provider_created ON provider_health_checks(provider_id, created_at DESC);

-- Model Performance Metrics
CREATE TABLE model_performance_metrics (
    id BIGSERIAL PRIMARY KEY,
    model_id BIGINT NOT NULL REFERENCES models(id) ON DELETE CASCADE,
    provider_id BIGINT NOT NULL REFERENCES providers(id),
    
    -- Time window
    hour DATE NOT NULL,
    
    -- Request metrics
    total_requests BIGINT,
    successful_requests BIGINT,
    failed_requests BIGINT,
    
    -- Performance
    avg_latency_ms DECIMAL(10, 2),
    p50_latency_ms INT,
    p95_latency_ms INT,
    p99_latency_ms INT,
    
    -- Errors
    error_rate DECIMAL(5, 4),
    timeout_count INT,
    rate_limit_count INT,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT metrics_unique_per_model_hour UNIQUE(model_id, hour)
);

CREATE INDEX idx_model_performance_metrics_model_hour ON model_performance_metrics(model_id, hour DESC);

-- ============================================================================
-- MONITORING & ALERTING
-- ============================================================================

-- Alerts & Notifications
CREATE TABLE alerts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    
    -- Alert details
    alert_type VARCHAR(50),                 -- 'rate_limit', 'cost_threshold', 'error_spike', 'provider_down'
    severity VARCHAR(50),                   -- 'info', 'warning', 'critical'
    
    title VARCHAR(255),
    description TEXT,
    
    -- Context
    metric_name VARCHAR(100),
    metric_value DECIMAL(15, 8),
    threshold_value DECIMAL(15, 8),
    
    -- Status
    is_resolved BOOLEAN DEFAULT false,
    resolved_at TIMESTAMP WITH TIME ZONE,
    
    -- Metadata
    tags TEXT[],
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_alerts_tenant_created ON alerts(tenant_id, created_at DESC);
CREATE INDEX idx_alerts_severity ON alerts(severity);

-- ============================================================================
-- MATERIALIZED VIEWS FOR ANALYTICS
-- ============================================================================

-- Daily usage summary (refreshed hourly)
CREATE MATERIALIZED VIEW daily_usage_summary AS
SELECT 
    tenant_id,
    DATE(created_at)::date as usage_date,
    COUNT(*) as request_count,
    SUM(total_tokens) as total_tokens,
    SUM(total_cost) as total_cost,
    AVG(latency_ms) as avg_latency_ms,
    MAX(latency_ms) as max_latency_ms,
    COUNT(CASE WHEN status = 'failed' THEN 1 END)::FLOAT / COUNT(*) as error_rate
FROM request_logs
GROUP BY tenant_id, DATE(created_at)::date;

CREATE INDEX idx_daily_usage_summary_tenant_date 
ON daily_usage_summary(tenant_id, usage_date DESC);

-- Model popularity view
CREATE MATERIALIZED VIEW model_popularity AS
SELECT 
    model_used,
    COUNT(*) as request_count,
    SUM(total_tokens) as total_tokens,
    SUM(total_cost) as total_cost,
    AVG(latency_ms) as avg_latency_ms,
    COUNT(CASE WHEN status = 'success' THEN 1 END)::FLOAT / COUNT(*) as success_rate
FROM request_logs
WHERE created_at > CURRENT_DATE - INTERVAL '30 days'
GROUP BY model_used;

-- ============================================================================
-- TRIGGERS & FUNCTIONS
-- ============================================================================

-- Update updated_at timestamp
CREATE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_tenants_updated_at
    BEFORE UPDATE ON tenants
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_api_keys_updated_at
    BEFORE UPDATE ON api_keys
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- Auto-aggregate token usage
CREATE FUNCTION aggregate_token_usage()
RETURNS VOID AS $$
BEGIN
    INSERT INTO token_usage_summary (tenant_id, date, total_requests, input_tokens, output_tokens, total_tokens, total_cost)
    SELECT 
        tenant_id,
        DATE(created_at),
        COUNT(*),
        SUM(input_tokens),
        SUM(output_tokens),
        SUM(total_tokens),
        SUM(total_cost)
    FROM request_logs
    WHERE created_at > CURRENT_TIMESTAMP - INTERVAL '1 hour'
    GROUP BY tenant_id, DATE(created_at)
    ON CONFLICT (tenant_id, date, hour) DO UPDATE
    SET total_requests = EXCLUDED.total_requests,
        input_tokens = EXCLUDED.input_tokens,
        output_tokens = EXCLUDED.output_tokens,
        total_tokens = EXCLUDED.total_tokens,
        total_cost = EXCLUDED.total_cost,
        updated_at = CURRENT_TIMESTAMP;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- CONSTRAINTS & POLICIES
-- ============================================================================

-- Row-Level Security (RLS) for Multi-tenancy
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE request_logs ENABLE ROW LEVEL SECURITY;

-- API Key RLS Policy: Users can only see keys in their tenant
CREATE POLICY tenant_isolation_api_keys ON api_keys
    USING (tenant_id = (SELECT tenant_id FROM users WHERE id = current_user_id));

-- ============================================================================
-- GRANTS & PERMISSIONS
-- ============================================================================

-- Create read-only analytics role
CREATE ROLE astra_analytics_readonly;
GRANT USAGE ON SCHEMA public TO astra_analytics_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO astra_analytics_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO astra_analytics_readonly;

-- Create application role
CREATE ROLE astra_app;
GRANT USAGE ON SCHEMA public TO astra_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO astra_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO astra_app;
