"use client";
import { useQuery } from "@tanstack/react-query";
import { health, observability, models, tenants } from "@/lib/api";
import { Card, CardTitle } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { Activity, Building2, Cpu, Zap } from "lucide-react";

function ServiceHealth({ name, fetcher }: { name: string; fetcher: () => Promise<{ status: string }> }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["health", name],
    queryFn: fetcher,
    refetchInterval: 30_000,
  });
  const status = isLoading ? "checking" : isError ? "unhealthy" : (data?.status ?? "unknown");
  return (
    <div className="flex items-center justify-between rounded-lg bg-zinc-800/60 px-4 py-3">
      <span className="text-sm text-zinc-300">{name}</span>
      <Badge label={status} status={status} />
    </div>
  );
}

export default function Dashboard() {
  const { data: metrics } = useQuery({ queryKey: ["obs-metrics"], queryFn: observability.metrics, refetchInterval: 15_000 });
  const { data: modelList } = useQuery({ queryKey: ["models"], queryFn: models.list });
  const { data: tenantList } = useQuery({ queryKey: ["tenants"], queryFn: tenants.list });

  const providerMetrics = (metrics?.by_provider ?? {}) as Record<string, { request_count: number; avg_latency_ms: number; total_tokens: number }>;
  const totalRequests = Object.values(providerMetrics).reduce((s, p) => s + (p.request_count ?? 0), 0);
  const totalTokens = Object.values(providerMetrics).reduce((s, p) => s + (p.total_tokens ?? 0), 0);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Dashboard</h1>
        <p className="mt-1 text-sm text-zinc-400">Real-time status of Astra Gateway services</p>
      </div>

      {/* stat cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {[
          { label: "Total Requests", value: totalRequests.toLocaleString(), icon: Activity, color: "text-brand-light" },
          { label: "Total Tokens", value: totalTokens.toLocaleString(), icon: Zap, color: "text-amber-400" },
          { label: "Active Models", value: modelList?.data?.length ?? "—", icon: Cpu, color: "text-emerald-400" },
          { label: "Tenants", value: tenantList?.length ?? "—", icon: Building2, color: "text-sky-400" },
        ].map(({ label, value, icon: Icon, color }) => (
          <Card key={label} className="flex items-center gap-4">
            <div className={`rounded-lg bg-zinc-800 p-2.5 ${color}`}>
              <Icon className="h-5 w-5" />
            </div>
            <div>
              <p className="text-2xl font-bold">{value}</p>
              <p className="text-xs text-zinc-400">{label}</p>
            </div>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* service health */}
        <Card>
          <CardTitle>Service Health</CardTitle>
          <div className="space-y-2">
            {[
              { name: "Gateway",      fetcher: health.gateway },
              { name: "Auth",         fetcher: health.auth },
              { name: "Routing",      fetcher: health.routing },
              { name: "Governance",   fetcher: health.governance },
              { name: "Observability",fetcher: health.observability },
            ].map((s) => <ServiceHealth key={s.name} {...s} />)}
          </div>
        </Card>

        {/* provider breakdown */}
        <Card>
          <CardTitle>Request Breakdown by Provider</CardTitle>
          {Object.keys(providerMetrics).length === 0 ? (
            <p className="text-sm text-zinc-500">No metrics yet — send some requests through the gateway.</p>
          ) : (
            <div className="space-y-3">
              {Object.entries(providerMetrics).map(([provider, stats]) => (
                <div key={provider} className="rounded-lg bg-zinc-800/60 px-4 py-3">
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium capitalize">{provider}</span>
                    <span className="text-xs text-zinc-400">{stats.request_count} reqs</span>
                  </div>
                  <div className="mt-1 flex gap-4 text-xs text-zinc-500">
                    <span>avg {stats.avg_latency_ms}ms</span>
                    <span>{stats.total_tokens?.toLocaleString()} tokens</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Card>
      </div>

      {/* available models */}
      <Card>
        <CardTitle>Available Models</CardTitle>
        <div className="flex flex-wrap gap-2">
          {modelList?.data?.map((m) => (
            <div key={m.id} className="flex items-center gap-2 rounded-md border border-zinc-700 bg-zinc-800 px-3 py-1.5 text-xs">
              <span className="font-mono text-zinc-200">{m.id}</span>
              <Badge label={m.provider} />
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}
