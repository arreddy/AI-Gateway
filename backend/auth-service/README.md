# Authentication & Authorization Service

Service: `auth-service`
Language: Go 1.21+
Purpose: API key management, JWT verification, RBAC enforcement

## Responsibilities

1. **API Key Management**
   - Key generation and storage (SHA-256 hash)
   - Key rotation and revocation
   - Permission scoping
   - Expiration management

2. **Token Verification**
   - JWT signature validation
   - Token expiration check
   - Claims extraction
   - Refresh token handling

3. **OAuth2/OIDC Support**
   - Integration with Google, GitHub, Microsoft
   - Token exchange
   - User provisioning
   - Federated identity

4. **RBAC/ABAC Enforcement**
   - Role-based access control
   - Attribute-based access control
   - Permission checking
   - Resource-level access

5. **Tenant Context Management**
   - Tenant isolation
   - User-to-tenant mapping
   - Team management
   - Organization hierarchies

## Architecture

### Authentication Flow
```
Request with API Key
  ↓
[Cache Lookup] - Check Redis for cached claims
  │
  ├─ Cache Hit → Return cached claims (TTL: 5 min)
  │
  └─ Cache Miss
       ↓
    [Verify Signature]
      ├─ Extract key from request
      ├─ Hash and lookup in DB
      ├─ Check expiration
      ├─ Check status (not revoked)
      └─ Extract claims
       ↓
    [Extract Permissions]
      ├─ Base permissions from key
      ├─ Role-based permissions
      ├─ Organization permissions
      └─ Resource-specific permissions
       ↓
    [Cache Claims] - Store in Redis with TTL
       ↓
    Return Claims Object
```

### Claims Structure
```json
{
  "api_key_id": "key_a_xyz123",
  "tenant_id": "org_123",
  "user_id": "user_456",
  "permissions": [
    "completions:create",
    "models:list",
    "usage:read"
  ],
  "rate_limits": {
    "rpm": 60000,
    "tpm": 90000000,
    "daily_tokens": 100000000
  },
  "resource_quotas": {
    "monthly_budget_usd": 10000,
    "monthly_spent_usd": 2350
  },
  "constraints": {
    "allowed_models": ["gpt-4", "gpt-3.5-turbo"],
    "allowed_ip_addresses": ["203.0.113.0/24"],
    "data_residency": "us-east"
  },
  "issued_at": 1705334400,
  "expires_at": 1705420800
}
```

### Permission Hierarchy

```
Organization
├─ Admin
│  ├─ All permissions
│  └─ User management
├─ Member
│  ├─ completions:create
│  ├─ models:list
│  ├─ usage:read
│  └─ api_keys:create (own keys)
└─ Viewer
   ├─ models:list
   └─ usage:read
```

## Configuration

```yaml
# auth-config.yaml
server:
  port: 9000
  grpc_port: 9001

jwt:
  secret_key: "${JWT_SECRET_KEY}"
  issuer: "astra-gateway"
  audience: "astra-api"
  expiration_hours: 24

api_key:
  prefix: "sk_prod_"
  prefix_test: "sk_test_"
  length: 32
  hash_algorithm: "sha256"

cache:
  redis_url: "redis://redis:6379"
  ttl_seconds: 300  # 5 minutes

database:
  url: "postgresql://user:pass@postgres:5432/astra"
  pool_size: 20
  max_lifetime: 1800

oauth2:
  google:
    client_id: "${GOOGLE_CLIENT_ID}"
    client_secret: "${GOOGLE_CLIENT_SECRET}"
  github:
    client_id: "${GITHUB_CLIENT_ID}"
    client_secret: "${GITHUB_CLIENT_SECRET}"
```

## Key Implementation Details

### API Key Validation
```go
// Fast path: Cache hit
if claims := cache.Get(keyHash); claims != nil {
    return claims, nil
}

// Slow path: Database lookup
key := db.GetAPIKey(keyHash)
if key == nil {
    return nil, ErrInvalidKey
}

if key.Status == StatusRevoked {
    return nil, ErrKeyRevoked
}

if time.Now().After(key.ExpiresAt) {
    return nil, ErrKeyExpired
}

claims := buildClaims(key)
cache.Set(keyHash, claims, 5*time.Minute)
return claims, nil
```

### Role-Based Permission Expansion
```go
// User has "member" role
// Expand to actual permissions
basePerms := map[string][]string{
    "admin": {"*"},
    "member": {
        "completions:create",
        "models:list",
        "usage:read",
        "api_keys:create_self",
    },
    "viewer": {
        "models:list",
        "usage:read",
    },
}

// Add resource-specific constraints
permissions := append(basePerms["member"],
    buildResourceConstraints(user.Resources)...
)
```

### OAuth2 Token Exchange
```go
// Google OAuth callback
func handleGoogleCallback(code string) {
    // Exchange code for token
    token := googleOAuth.Exchange(code)
    
    // Get user info
    userInfo := googleOAuth.GetUserInfo(token)
    
    // Upsert user in DB
    user := db.UpsertUser(userInfo)
    
    // Create session
    jwtToken := jwt.Sign(buildClaims(user), secret)
    
    return jwtToken
}
```

## Endpoints (gRPC)

### service/auth.proto
```protobuf
service AuthService {
  rpc VerifyAPIKey(VerifyAPIKeyRequest) returns (Claims) {}
  rpc VerifyJWT(VerifyJWTRequest) returns (Claims) {}
  rpc GetUserInfo(GetUserInfoRequest) returns (User) {}
  rpc CheckPermission(CheckPermissionRequest) returns (CheckPermissionResponse) {}
}

message VerifyAPIKeyRequest {
  string api_key = 1;
  string ip_address = 2;
  string user_agent = 3;
}

message Claims {
  int64 api_key_id = 1;
  int64 tenant_id = 2;
  int64 user_id = 3;
  repeated string permissions = 4;
  RateLimits rate_limits = 5;
  ResourceQuotas resource_quotas = 6;
  AuthConstraints constraints = 7;
  int64 issued_at = 8;
  int64 expires_at = 9;
}
```

## Deployment

```bash
# Docker
docker build -t astra-auth-service:latest .
docker run -p 9000:9000 astra-auth-service:latest

# Kubernetes
kubectl apply -f infrastructure/k8s/auth-service-deployment.yaml
```

## Security Considerations

1. **Key Storage**
   - Never store plaintext keys
   - Always hash with SHA-256
   - Use proper salting for key generation

2. **Token Security**
   - Short expiration times (24 hours)
   - Refresh tokens separate from access tokens
   - Revocation checks on every request

3. **Rate Limiting**
   - Per-key rate limits
   - Gradual token bucket for burst protection
   - Separate RPM and TPM limits

4. **Audit Trail**
   - Log all key operations (creation, rotation, revocation)
   - Log failed authentication attempts
   - Immutable audit logs in database

5. **Encryption**
   - All secrets encrypted at rest
   - TLS 1.3 for all connections
   - mTLS for service-to-service communication

## Testing

```bash
make test                 # Unit tests
make test-integration    # Integration tests
make load-test          # Load testing with k6
```
