# Security Architecture & Governance

## Overview

Astra Gateway implements enterprise-grade security with zero-trust architecture, defense-in-depth, and compliance-ready design (SOC2, GDPR, HIPAA).

## Security Layers

### Layer 1: Network Security

#### TLS/SSL Encryption
```
Client ←──TLS 1.3──→ Load Balancer
                      ↓
                  Gateway Service
                      ↓
                  Internal Services (mTLS)
```

**Configuration:**
```yaml
tls:
  min_version: "1.3"
  cipher_suites:
    - TLS_AES_256_GCM_SHA384
    - TLS_CHACHA20_POLY1305_SHA256
    - TLS_AES_128_GCM_SHA256
  certificate_provider: "certmanager"
```

#### DDoS Protection
- AWS Shield (managed DDoS protection)
- Rate limiting at edge (WAF)
- Geo-blocking if needed

#### Network Policies
```yaml
# Only allow traffic from ingress controller to gateway
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: gateway-ingress-only
spec:
  podSelector:
    matchLabels:
      app: gateway-service
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app.kubernetes.io/name: ingress-nginx
```

### Layer 2: Authentication & Authorization

#### API Key Security

```go
// Key format: sk_prod_[32 random bytes]
// Never stored in plaintext
// Only SHA-256 hash in database

// Key rotation policy
// - Issued keys expire after 1 year
// - Can be manually rotated anytime
// - Old key remains valid for 30 days during transition
```

**Database:**
```sql
-- Never store the actual key
CREATE TABLE api_keys (
  id BIGINT PRIMARY KEY,
  key_hash VARCHAR(255) UNIQUE NOT NULL,  -- SHA-256
  key_preview VARCHAR(20),                 -- Last 20 chars only
  status VARCHAR(50),
  created_at TIMESTAMP,
  expires_at TIMESTAMP
);
```

#### JWT Tokens

```json
{
  "sub": "user_456",
  "org": "org_123",
  "iss": "astra-gateway",
  "aud": "astra-api",
  "iat": 1705334400,
  "exp": 1705420800,
  "permissions": ["completions:create", "models:list"],
  "rate_limits": { "rpm": 60000 }
}
```

**Validation:**
```go
// 1. Verify signature with RS256
// 2. Check expiration (standard JWT)
// 3. Validate claims (custom)
// 4. Check revocation list
// 5. Verify issuer and audience
// 6. Check sub claim matches user_id
```

#### OAuth2/OIDC

```go
// Supported providers:
// - Google (oauth2/google)
// - GitHub (oauth2/github)
// - Microsoft (oauth2/microsoft)
// - Custom OIDC provider

// Token exchange flow
Code → AccessToken → UserInfo → CreateUser/UpdateUser → JWTToken
```

#### RBAC (Role-Based Access Control)

```yaml
roles:
  admin:
    permissions: "*"
    manage_users: true
  member:
    permissions:
      - completions:create
      - models:list
      - usage:read
      - api_keys:create_self
  viewer:
    permissions:
      - models:list
      - usage:read
```

#### ABAC (Attribute-Based Access Control)

```go
// Fine-grained control based on attributes
// Request: Create completion for model GPT-4
// Attributes:
//   - User role: member
//   - API key permissions: [completions:create]
//   - Model tier: premium
//   - User tier: pro
//   - User budget remaining: $500
//   - Request IP: 203.0.113.1

// Decision: ALLOW if:
//   - permissions include "completions:create"
//   - user tier >= model tier
//   - budget remaining > estimated cost
//   - IP in allowlist
```

### Layer 3: Data Security

#### Encryption at Rest

```go
// PostgreSQL
// ├─ Transparent Data Encryption (TDE) enabled
// ├─ Master key stored in AWS KMS
// ├─ Keys rotated quarterly
// └─ Backups encrypted with same key

// Redis
// ├─ Optional encryption for sensitive data
// ├─ TTL for cache entries
// └─ No sensitive data stored (auth checks only)

// ClickHouse
// ├─ Replication to encrypted replicas
// ├─ Columnar encryption for sensitive columns
// └─ Backups to encrypted S3
```

**Encryption Implementation:**
```go
// Encrypt sensitive fields
func encryptValue(value string, masterKey []byte) (string, error) {
    ciphertext := encrypt(value, masterKey, "AES-256-GCM")
    return base64.StdEncoding.EncodeToString(ciphertext), nil
}

// Fields encrypted:
// - Provider API keys
// - BYOK (Bring Your Own Key) credentials
// - PII in audit logs
```

#### Data Residency & Compliance

```yaml
# Data residency controls
residency_controls:
  us_region:
    allowed_providers: ["us-east-1", "us-west-2"]
    prohibited_providers: ["eu-west-1"]
  
  eu_region:
    allowed_providers: ["eu-west-1", "eu-central-1"]
    prohibited_providers: ["us-east-1"]
```

#### Multi-Tenancy Isolation

```go
// Row-level security enforces tenant isolation
// Every query automatically filtered by tenant_id

// SELECT * FROM request_logs WHERE tenant_id = current_tenant_id
// UPDATE api_keys SET status = 'revoked' 
//   WHERE tenant_id = current_tenant_id AND id = key_id

// Data access:
// - Tenant A cannot see Tenant B's data
// - Cross-tenant joins impossible
// - Backups include only single tenant
```

### Layer 4: Input Validation & Protection

#### Request Validation

```go
// 1. Schema validation
// ├─ Check required fields
// ├─ Validate data types
// ├─ Verify field ranges
// └─ Limit request size (50MB)

// 2. Content validation
// ├─ Validate JSON/JSON-RPC format
// ├─ Check message length limits
// ├─ Verify model names are known
// └─ Validate parameter ranges

// 3. Business logic validation
// ├─ Check API key is active
// ├─ Verify quota available
// ├─ Confirm provider configured
// └─ Validate model exists
```

**Implementation:**
```go
type ChatCompletionRequest struct {
    Model       string         `json:"model" validate:"required,min=1,max=255"`
    Messages    []ChatMessage  `json:"messages" validate:"required,min=1,max=32"`
    Temperature *float32       `json:"temperature" validate:"omitempty,gte=0,lte=2"`
    MaxTokens   *int           `json:"max_tokens" validate:"omitempty,gte=1,lte=4096"`
}

// Validate using struct tags
if err := validate.Struct(req); err != nil {
    return errors.New("validation failed")
}
```

#### Injection Detection

```go
// 1. Prompt Injection Detection
//    Uses pattern matching + ML model
//    Examples: "Ignore previous instructions", "System override"

// 2. Command Injection Protection
//    No shell execution of user input
//    All external calls use safe APIs

// 3. SQL Injection Prevention
//    Parameterized queries only
//    ORM prevents direct SQL assembly
//    No string concatenation in queries

// 4. XML/XXE Prevention
//    XML parsing disabled by default
//    Strict DTD validation if enabled
```

### Layer 5: Audit & Compliance

#### Immutable Audit Logs

```sql
-- Append-only audit log
-- Cannot be modified or deleted (except by superuser)
CREATE TABLE audit_logs (
    id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,      -- 'create', 'read', 'update', 'delete'
    resource_type VARCHAR(50),         -- 'api_key', 'policy', 'request'
    resource_id VARCHAR(255),
    actor_type VARCHAR(50),            -- 'user', 'system', 'api'
    actor_id VARCHAR(255),
    changes JSONB,                     -- Before/after values
    ip_address INET,
    user_agent TEXT,
    request_id UUID,
    success BOOLEAN,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Prevent deletion
CREATE TRIGGER prevent_audit_log_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION raise_exception();
```

#### Compliance Tracking

**SOC2 Controls:**
```yaml
controls:
  CC6.1: Logical access controls
  CC6.2: Sessions timeout
  CC7.2: System monitoring
  CC9.2: Incident reporting
```

**GDPR Compliance:**
```go
// Right to be forgotten
func DeleteUserData(ctx context.Context, userID int64) error {
    // 1. Archive data to compliance storage
    backup, err := archiveUserData(ctx, userID)
    
    // 2. Delete from production
    deleteUserData(ctx, userID)
    
    // 3. Log in audit trail
    auditLog(ctx, "data_deletion", userID, backup)
    
    return nil
}
```

**HIPAA Compliance:**
```go
// Audit controls for PHI
// - All PHI access logged
// - User identification recorded
// - Access justification required
// - Breach notification procedures
// - Encryption of PHI both in transit and at rest
```

#### Governance Policy Engine

```go
// Policy example: PII Redaction
policy := GovernancePolicy{
    Type: "pii_redaction",
    Config: map[string]interface{}{
        "pii_types": []string{"ssn", "credit_card", "email"},
        "action": "redact",
        "pattern_ssn": `\d{3}-\d{2}-\d{4}`,
    },
    Action: "redact",
}

// During request processing
violations := governanceEngine.Check(request, policies)
for _, v := range violations {
    if v.Action == "redact" {
        request.Content = redactPII(request.Content)
    } else if v.Action == "block" {
        return errors.New("policy violation: blocked")
    } else if v.Action == "warn" {
        logging.Warn("policy violation", v)
    }
}
```

## Zero Trust Architecture

```
Every Request
  ├─ 1. Verify Identity
  │  ├─ API Key validation
  │  ├─ Token signature check
  │  └─ Revocation list check
  │
  ├─ 2. Verify Device
  │  ├─ Client certificate verification
  │  ├─ Mutual TLS
  │  └─ Device fingerprinting (optional)
  │
  ├─ 3. Verify Network
  │  ├─ IP whitelisting
  │  ├─ Geo-location check
  │  └─ VPN/Proxy detection
  │
  ├─ 4. Verify Resource
  │  ├─ Tenant isolation check
  │  ├─ API key permission check
  │  └─ Rate limit check
  │
  ├─ 5. Verify Context
  │  ├─ Request signature validation
  │  ├─ Timestamp freshness
  │  └─ Nonce validation
  │
  └─ Decision: Allow, Deny, or Challenge
```

## Incident Response

```yaml
incident_response:
  detection:
    - Automated alerting on security events
    - Real-time monitoring of audit logs
    - Anomaly detection ML models

  response_procedures:
    - Isolate affected service
    - Preserve evidence (logs, metrics)
    - Notify security team
    - Begin investigation
    - Communicate with affected customers

  recovery:
    - Patch or rollback
    - Verify remediation
    - Resume normal operations
    - Post-incident review
```

## Secrets Management

```go
// All secrets managed by HashiCorp Vault
// ├─ API keys for external providers
// ├─ Database credentials
// ├─ JWT signing keys
// ├─ Encryption keys
// └─ Webhooks signing keys

// Secret rotation
// ├─ Automatic rotation every 90 days
// ├─ Gradual rollover (old + new key valid)
// └─ No service downtime during rotation
```

## Third-Party Security

```yaml
# Dependencies
# - Regularly scanned with Snyk, WhiteSource
# - Security updates applied within 24 hours
# - No vulnerable dependencies in production

# Provider APIs
# - API keys stored encrypted
# - Rate limiting per provider
# - Provider health monitoring
# - Fallback mechanisms
```

## Security Checklist

- [ ] TLS 1.3 enabled for all traffic
- [ ] API keys hashed with SHA-256
- [ ] JWTs signed with RS256
- [ ] All database queries parameterized
- [ ] Row-level security enforced
- [ ] Audit logs immutable and encrypted
- [ ] Rate limiting active
- [ ] DDoS protection enabled
- [ ] Secrets encrypted at rest
- [ ] Regular penetration testing
- [ ] Incident response plan in place
- [ ] SOC2 audit scheduled
- [ ] GDPR compliance verified
- [ ] Data residency policies enforced
