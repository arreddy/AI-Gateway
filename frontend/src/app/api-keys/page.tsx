"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { tenants, apiKeys, type Tenant, type ApiKey } from "@/lib/api";
import { Table, Th, Td } from "@/components/ui/Table";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { Modal, Field, Input, Select } from "@/components/ui/Modal";
import { Card } from "@/components/ui/Card";
import { formatDate } from "@/lib/utils";
import { Plus, Copy, Trash2 } from "lucide-react";

export default function ApiKeysPage() {
  const qc = useQueryClient();
  const [tenantId, setTenantId] = useState("");
  const [open, setOpen] = useState(false);
  const [newKey, setNewKey] = useState<string | null>(null);
  const [form, setForm] = useState({ name: "" });

  const { data: tenantList = [] } = useQuery({ queryKey: ["tenants"], queryFn: tenants.list });
  const { data: keys = [], isLoading } = useQuery({
    queryKey: ["api-keys", tenantId],
    queryFn: () => apiKeys.list(tenantId),
    enabled: !!tenantId,
  });

  const create = useMutation({
    mutationFn: () => apiKeys.create(tenantId, form),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["api-keys", tenantId] });
      setOpen(false);
      if ("key" in data) setNewKey((data as { key: string }).key);
    },
  });

  const revoke = useMutation({
    mutationFn: apiKeys.revoke,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["api-keys", tenantId] }),
  });

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">API Keys</h1>
          <p className="mt-1 text-sm text-zinc-400">Manage API keys per tenant</p>
        </div>
        <Button onClick={() => setOpen(true)} disabled={!tenantId}><Plus className="h-4 w-4" /> New Key</Button>
      </div>

      <div className="flex items-center gap-3">
        <label className="text-sm text-zinc-400">Tenant:</label>
        <select
          className="rounded-md border border-zinc-700 bg-zinc-800 px-3 py-1.5 text-sm text-zinc-100"
          value={tenantId}
          onChange={e => setTenantId(e.target.value)}
        >
          <option value="">Select a tenant…</option>
          {(tenantList as Tenant[]).map((t: Tenant) => (
            <option key={t.externalId} value={t.externalId}>{t.name}</option>
          ))}
        </select>
      </div>

      {newKey && (
        <Card className="border-emerald-600/30 bg-emerald-950/30">
          <p className="mb-2 text-xs font-semibold text-emerald-400">Key created — copy it now, it will not be shown again</p>
          <div className="flex items-center gap-2">
            <code className="flex-1 rounded bg-zinc-900 px-3 py-2 text-xs text-zinc-200 break-all">{newKey}</code>
            <Button size="sm" variant="secondary" onClick={() => navigator.clipboard.writeText(newKey)}>
              <Copy className="h-3 w-3" />
            </Button>
          </div>
          <Button size="sm" variant="ghost" className="mt-2" onClick={() => setNewKey(null)}>Dismiss</Button>
        </Card>
      )}

      {tenantId && (
        isLoading ? <p className="text-sm text-zinc-500">Loading…</p> : (
          <Table>
            <thead>
              <tr><Th>Name</Th><Th>Preview</Th><Th>Status</Th><Th>Total Requests</Th><Th>Created</Th><Th></Th></tr>
            </thead>
            <tbody>
              {(keys as ApiKey[]).map((k: ApiKey) => (
                <tr key={k.externalId}>
                  <Td className="font-medium">{k.name}</Td>
                  <Td><code className="text-xs text-zinc-400">…{k.keyPreview}</code></Td>
                  <Td><Badge label={k.status} status={k.status} /></Td>
                  <Td>{k.totalRequests?.toLocaleString()}</Td>
                  <Td>{formatDate(k.createdAt)}</Td>
                  <Td>
                    <Button size="sm" variant="danger" onClick={() => revoke.mutate(k.externalId)}>
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )
      )}

      <Modal title="Create API Key" open={open} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          <Field label="Key Name">
            <Input value={form.name} onChange={e => setForm({ name: e.target.value })} placeholder="Production Key" />
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
