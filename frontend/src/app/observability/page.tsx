"use client";
import { useQuery } from "@tanstack/react-query";
import { observability } from "@/lib/api";
import { Card, CardTitle } from "@/components/ui/Card";
import { Activity } from "lucide-react";

export default function ObservabilityPage() {
  const { data, isLoading, dataUpdatedAt } = useQuery({
    queryKey: ["obs-metrics"],
    queryFn: observability.metrics,
    refetchInterval: 10_000,
  });

  const byProvider = (data?.by_provider ?? {}) as Record<string, {
    request_count: number; total_tokens: number; avg_latency_ms: number;
  }>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Observability</h1>
          <p className="mt-1 text-sm text-zinc-400">
            Live metrics · {data ? `${data.total_recorded} events · updated ${new Date(dataUpdatedAt).toLocaleTimeString()}` : "loading…"}
          </p>
        </div>
        <div className="flex items-center gap-2 rounded-full bg-emerald-950 px-3 py-1 text-xs text-emerald-400">
          <Activity className="h-3 w-3 animate-pulse" /> Live
        </div>
      </div>

      {isLoading ? (
        <p className="text-sm text-zinc-500">Loading metrics…</p>
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {Object.entries(byProvider).map(([provider, stats]) => (
              <Card key={provider}>
                <CardTitle>{provider}</CardTitle>
                <div className="space-y-3">
                  {[
                    { label: "Requests", value: stats.request_count?.toLocaleString() },
                    { label: "Total Tokens", value: stats.total_tokens?.toLocaleString() },
                    { label: "Avg Latency", value: `${stats.avg_latency_ms}ms` },
                  ].map(({ label, value }) => (
                    <div key={label} className="flex justify-between text-sm">
                      <span className="text-zinc-400">{label}</span>
                      <span className="font-mono font-medium">{value}</span>
                    </div>
                  ))}
                </div>
              </Card>
            ))}
            {Object.keys(byProvider).length === 0 && (
              <div className="col-span-3 text-center text-sm text-zinc-500 py-12">
                No metrics yet. Send some requests through the gateway to see data here.
              </div>
            )}
          </div>

          {/* ClickHouse + external links */}
          <Card>
            <CardTitle>External Dashboards</CardTitle>
            <div className="flex flex-wrap gap-3">
              {[
                { label: "Grafana", url: "http://localhost:3000", desc: "Dashboards & alerts" },
                { label: "Prometheus", url: "http://localhost:9090", desc: "Metrics explorer" },
                { label: "Jaeger", url: "http://localhost:16686", desc: "Distributed tracing" },
                { label: "ClickHouse", url: "http://localhost:8123/play", desc: "Raw analytics SQL" },
              ].map(({ label, url, desc }) => (
                <a key={label} href={url} target="_blank" rel="noopener noreferrer"
                  className="flex flex-col rounded-lg border border-zinc-700 bg-zinc-800 px-4 py-3 hover:border-brand/60 hover:bg-zinc-700 transition-colors">
                  <span className="text-sm font-semibold text-brand-light">{label} ↗</span>
                  <span className="text-xs text-zinc-400">{desc}</span>
                </a>
              ))}
            </div>
          </Card>
        </>
      )}
    </div>
  );
}
