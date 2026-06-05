"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { routing, type RoutingPolicy } from "@/lib/api";
import { Table, Th, Td } from "@/components/ui/Table";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { Modal, Field, Input, Select } from "@/components/ui/Modal";
import { Card, CardTitle } from "@/components/ui/Card";
import { Plus, Trash2 } from "lucide-react";

export default function RoutingPage() {
  const qc = useQueryClient();
  const [tenantId, setTenantId] = useState(1);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", strategy: "latency", priority: 100, tenantId });

  const { data: metrics } = useQuery({ queryKey: ["routing-metrics"], queryFn: routing.metrics, refetchInterval: 15_000 });
  const { data: policies = [] } = useQuery({
    queryKey: ["routing-policies", tenantId],
    queryFn: () => routing.listPolicies(tenantId),
  });

  const create = useMutation({
    mutationFn: () => routing.createPolicy({ ...form, tenantId }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["routing-policies"] }); setOpen(false); },
  });

  const del = useMutation({
    mutationFn: routing.deletePolicy,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["routing-policies"] }),
  });

  const providerMetrics = (metrics ?? {}) as Record<string, { cost_per_1k_tokens: number; avg_latency_ms: number; quality_score: number; request_count: number }>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Routing</h1>
          <p className="mt-1 text-sm text-zinc-400">Provider metrics and routing policies</p>
        </div>
        <Button onClick={() => setOpen(true)}><Plus className="h-4 w-4" /> New Policy</Button>
      </div>

      {/* provider metrics */}
      <Card>
        <CardTitle>Provider Performance</CardTitle>
        <div className="grid gap-3 sm:grid-cols-3">
          {Object.entries(providerMetrics).map(([provider, stats]) => (
            <div key={provider} className="rounded-lg bg-zinc-800/60 p-4 space-y-2">
              <p className="font-semibold capitalize">{provider}</p>
              <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                {[
                  ["Cost/1k", `$${stats.cost_per_1k_tokens}`],
                  ["Latency", `${stats.avg_latency_ms}ms`],
                  ["Quality", `${(stats.quality_score * 100).toFixed(0)}%`],
                  ["Requests", stats.request_count],
                ].map(([label, val]) => (
                  <div key={label as string}>
                    <span className="text-zinc-500">{label}</span>
                    <span className="ml-1 text-zinc-200">{val}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </Card>

      {/* policies table */}
      <div>
        <div className="mb-3 flex items-center gap-3">
          <span className="text-sm text-zinc-400">Tenant ID:</span>
          <Input
            type="number" value={tenantId}
            onChange={e => setTenantId(Number(e.target.value))}
            className="w-24"
          />
        </div>
        <Table>
          <thead>
            <tr><Th>Name</Th><Th>Strategy</Th><Th>Priority</Th><Th>Active</Th><Th></Th></tr>
          </thead>
          <tbody>
            {(policies as RoutingPolicy[]).map((p: RoutingPolicy) => (
              <tr key={p.id}>
                <Td className="font-medium">{p.name}</Td>
                <Td><Badge label={p.strategy} /></Td>
                <Td>{p.priority}</Td>
                <Td><Badge label={p.isActive ? "active" : "inactive"} status={p.isActive ? "active" : "deleted"} /></Td>
                <Td>
                  <Button size="sm" variant="danger" onClick={() => del.mutate(p.id)}>
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </Td>
              </tr>
            ))}
            {(policies as RoutingPolicy[]).length === 0 && (
              <tr><Td colSpan={5} className="text-center text-zinc-500">No routing policies</Td></tr>
            )}
          </tbody>
        </Table>
      </div>

      <Modal title="Create Routing Policy" open={open} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          <Field label="Name"><Input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Default latency policy" /></Field>
          <Field label="Strategy">
            <Select value={form.strategy} onChange={e => setForm(f => ({ ...f, strategy: e.target.value }))}>
              {["latency", "cost", "quality", "rule_based", "adaptive"].map(s => <option key={s} value={s}>{s}</option>)}
            </Select>
          </Field>
          <Field label="Priority (lower = higher)">
            <Input type="number" value={form.priority} onChange={e => setForm(f => ({ ...f, priority: Number(e.target.value) }))} />
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
            <Button onClick={() => create.mutate()} disabled={create.isPending}>
              {create.isPending ? "Creating…" : "Create"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
