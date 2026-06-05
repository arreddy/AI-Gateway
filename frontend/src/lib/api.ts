// Central API client — all backend calls go through the runtime proxy route
// /api/proxy/<service>/... which resolves env vars at server startup (not build time)

const BASE = {
  gateway:       "/api/proxy/gateway",
  auth:          "/api/proxy/auth",
  routing:       "/api/proxy/routing",
  governance:    "/api/proxy/governance",
  observability: "/api/proxy/observability",
};

import { getApiKey } from "./apiKey";

async function req<T>(url: string, init?: RequestInit, auth = false): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (auth) {
    const key = getApiKey();
    if (key) headers["Authorization"] = `Bearer ${key}`;
  }
  const res = await fetch(url, { headers: { ...headers, ...(init?.headers ?? {}) }, ...init });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`${res.status} ${text}`);
  }
  return res.json() as Promise<T>;
}

// Gateway calls require an API key (enforced by AuthInterceptor)
function greq<T>(url: string, init?: RequestInit) {
  return req<T>(url, init, true);
}

// ── Health ────────────────────────────────────────────────────────────────────

export const health = {
  gateway:       () => greq<{ status: string }>(`${BASE.gateway}/v1/health`),
  auth:          () => req<{ status: string }>(`${BASE.auth}/v1/auth/health`),
  routing:       () => req<{ status: string }>(`${BASE.routing}/v1/routing/health`),
  governance:    () => req<{ status: string }>(`${BASE.governance}/v1/governance/health`),
  observability: () => req<{ status: string }>(`${BASE.observability}/v1/observability/health`),
};

// ── Tenants ───────────────────────────────────────────────────────────────────

export interface Tenant {
  id: string; externalId: string; name: string; slug: string; email: string;
  status: string; tier: string; rateLimitRpm: number; createdAt: string;
}

export const tenants = {
  list: () => req<Tenant[]>(`${BASE.auth}/v1/tenants`),
  get:  (id: string) => req<Tenant>(`${BASE.auth}/v1/tenants/${id}`),
  create: (body: Record<string, unknown>) =>
    req<Tenant>(`${BASE.auth}/v1/tenants`, { method: "POST", body: JSON.stringify(body) }),
  setStatus: (id: string, status: string) =>
    req<Tenant>(`${BASE.auth}/v1/tenants/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) }),
  setTier: (id: string, tier: string) =>
    req<Tenant>(`${BASE.auth}/v1/tenants/${id}/tier`, { method: "PATCH", body: JSON.stringify({ tier }) }),
};

// ── API Keys ──────────────────────────────────────────────────────────────────

export interface ApiKey {
  id: string; externalId: string; name: string; keyPreview: string;
  status: string; totalRequests: number; createdAt: string; expiresAt: string | null;
}

export const apiKeys = {
  list: (tenantId: string) =>
    req<ApiKey[]>(`${BASE.auth}/v1/tenants/${tenantId}/api-keys`),
  create: (tenantId: string, body: Record<string, unknown>) =>
    req<ApiKey & { key: string }>(`${BASE.auth}/v1/tenants/${tenantId}/api-keys`,
      { method: "POST", body: JSON.stringify(body) }),
  revoke: (keyId: string) =>
    req<void>(`${BASE.auth}/v1/api-keys/${keyId}`, { method: "DELETE" }),
};

// ── MCP Servers ───────────────────────────────────────────────────────────────

export interface McpServer {
  id: string; name: string; url: string; description: string; status: string; registeredAt: number;
}
export interface McpTool {
  name: string; description: string; serverId: string; serverUrl: string;
}

export const mcp = {
  listServers: () =>
    greq<{ servers: McpServer[]; count: number }>(`${BASE.gateway}/v1/mcp/servers`),
  register: (body: Record<string, unknown>) =>
    greq<McpServer>(`${BASE.gateway}/v1/mcp/servers`, { method: "POST", body: JSON.stringify(body) }),
  deregister: (id: string) =>
    greq<void>(`${BASE.gateway}/v1/mcp/servers/${id}`, { method: "DELETE" }),
  discover: (id: string) =>
    greq<{ tools: McpTool[]; count: number }>(`${BASE.gateway}/v1/mcp/servers/${id}/discover`,
      { method: "POST" }),
  listTools: () =>
    greq<{ tools: McpTool[]; count: number }>(`${BASE.gateway}/v1/mcp/tools`),
};

// ── A2A Agents ────────────────────────────────────────────────────────────────

export interface A2aAgent {
  id: string; name: string; url: string; description: string;
  skills: string[]; capabilities: string[]; status: string; registeredAt: number;
}

export const a2a = {
  list: () =>
    greq<{ agents: A2aAgent[]; count: number }>(`${BASE.gateway}/v1/a2a/agents`),
  register: (body: Record<string, unknown>) =>
    greq<A2aAgent>(`${BASE.gateway}/v1/a2a/agents`, { method: "POST", body: JSON.stringify(body) }),
  deregister: (id: string) =>
    greq<void>(`${BASE.gateway}/v1/a2a/agents/${id}`, { method: "DELETE" }),
  discover: (id: string) =>
    greq<A2aAgent>(`${BASE.gateway}/v1/a2a/agents/${id}/discover`, { method: "POST" }),
  sendTask: (id: string, message: string) =>
    greq<unknown>(`${BASE.gateway}/v1/a2a/agents/${id}/tasks`,
      { method: "POST", body: JSON.stringify({ message }) }),
};

// ── Routing ───────────────────────────────────────────────────────────────────

export interface RoutingPolicy {
  id: number; name: string; strategy: string; priority: number; isActive: boolean;
}

export const routing = {
  metrics: () =>
    req<Record<string, unknown>>(`${BASE.routing}/v1/routing/metrics`),
  listPolicies: (tenantId: number) =>
    req<RoutingPolicy[]>(`${BASE.routing}/v1/routing-policies/tenant/${tenantId}`),
  createPolicy: (body: Record<string, unknown>) =>
    req<RoutingPolicy>(`${BASE.routing}/v1/routing-policies`,
      { method: "POST", body: JSON.stringify(body) }),
  deletePolicy: (id: number) =>
    req<void>(`${BASE.routing}/v1/routing-policies/${id}`, { method: "DELETE" }),
};

// ── Governance ────────────────────────────────────────────────────────────────

export interface GovernancePolicy {
  id: number; name: string; policyType: string; action: string; priority: number; isEnabled: boolean;
}

export const governance = {
  listPolicies: (tenantId: number) =>
    req<GovernancePolicy[]>(`${BASE.governance}/v1/governance-policies/tenant/${tenantId}`),
  createPolicy: (body: Record<string, unknown>) =>
    req<GovernancePolicy>(`${BASE.governance}/v1/governance-policies`,
      { method: "POST", body: JSON.stringify(body) }),
  disablePolicy: (id: number) =>
    req<void>(`${BASE.governance}/v1/governance-policies/${id}/disable`, { method: "PATCH" }),
  checkContent: (content: string, type: string) =>
    req<{ safe: boolean; issues: string[]; action: string }>(
      `${BASE.governance}/v1/governance/check`,
      { method: "POST", body: JSON.stringify({ content, type }) }),
};

// ── Observability ─────────────────────────────────────────────────────────────

export const observability = {
  metrics: () =>
    req<{ by_provider: Record<string, unknown>; total_recorded: number }>(
      `${BASE.observability}/v1/observability/metrics`),
};

// ── Models ────────────────────────────────────────────────────────────────────

export interface Model { id: string; owned_by: string; provider: string; }

export const models = {
  list: () => greq<{ data: Model[] }>(`${BASE.gateway}/v1/models`),
};
