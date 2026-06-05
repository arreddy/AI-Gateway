"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useParams, useRouter } from "next/navigation";
import { tenants, apiKeys, type ApiKey } from "@/lib/api";
import { Card, CardTitle } from "@/components/ui/Card";
import { Badge } from "@/components/ui/Badge";
import { Button } from "@/components/ui/Button";
import { Table, Th, Td } from "@/components/ui/Table";
import { Modal, Field, Input, Select } from "@/components/ui/Modal";
import { formatDate } from "@/lib/utils";
import { ArrowLeft, Copy, Plus, Trash2 } from "lucide-react";
import Link from "next/link";

export default function TenantDetailPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const qc = useQueryClient();
  const [openKey, setOpenKey] = useState(false);
  const [openStatus, setOpenStatus] = useState(false);
  const [openTier, setOpenTier] = useState(false);
  const [keyForm, setKeyForm] = useState({ name: "" });
  const [newKey, setNewKey] = useState<string | null>(null);
  const [statusVal, setStatusVal] = useState("active");
  const [tierVal, setTierVal] = useState("free");

  const { data: tenant, isLoading } = useQuery({
    queryKey: ["tenant", id],
    queryFn: () => tenants.get(id),
  });

  const { data: keys = [] } = useQuery({
    queryKey: ["api-keys", id],
    queryFn: () => apiKeys.list(id),
    enabled: !!id,
  });

  const createKey = useMutation({
    mutationFn: () => apiKeys.create(id, keyForm),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["api-keys", id] });
      setOpenKey(false);
      setKeyForm({ name: "" });
      if ("key" in data) setNewKey((data as { key: string }).key);
    },
  });

  const revokeKey = useMutation({
    mutationFn: apiKeys.revoke,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["api-keys", id] }),
  });

  const updateStatus = useMutation({
    mutationFn: () => tenants.setStatus(id, statusVal),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["tenant", id] }); qc.invalidateQueries({ queryKey: ["tenants"] }); setOpenStatus(false); },
  });

  const updateTier = useMutation({
    mutationFn: () => tenants.setTier(id, tierVal),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["tenant", id] }); qc.invalidateQueries({ queryKey: ["tenants"] }); setOpenTier(false); },
  });

  if (isLoading) return <p className="text-sm text-zinc-500 p-6">Loading…</p>;
  if (!tenant)   return <p className="text-sm text-red-400 p-6">Tenant not found.</p>;

  return (
    <div className="space-y-6">
      {/* header */}
      <div className="flex items-center gap-4">
        <button onClick={() => router.back()} className="text-zinc-400 hover:text-zinc-100">
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="flex-1">
          <h1 className="text-2xl font-bold">{tenant.name}</h1>
          <p className="mt-0.5 text-sm text-zinc-400 font-mono">{tenant.externalId}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" size="sm" onClick={() => { setStatusVal(tenant.status); setOpenStatus(true); }}>Change Status</Button>
          <Button variant="secondary" size="sm" onClick={() => { setTierVal(tenant.tier); setOpenTier(true); }}>Change Tier</Button>
        </div>
      </div>

      {/* overview cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[
          { label: "Status",       value: <Badge label={tenant.status} status={tenant.status} /> },
          { label: "Tier",         value: <Badge label={tenant.tier} /> },
          { label: "Rate Limit",   value: `${(tenant.rateLimitRpm ?? 0).toLocaleString()} rpm` },
          { label: "Created",      value: formatDate(tenant.createdAt) },
        ].map(({ label, value }) => (
          <Card key={label}>
            <p className="text-xs text-zinc-500 mb-1">{label}</p>
            <div className="text-sm font-medium">{value}</div>
          </Card>
        ))}
      </div>

      {/* tenant details */}
      <Card>
        <CardTitle>Details</CardTitle>
        <dl className="grid grid-cols-2 gap-x-6 gap-y-3 text-sm">
          {[
            { k: "Email",    v: tenant.email },
            { k: "Slug",     v: <code className="text-xs text-zinc-400">{(tenant as { slug?: string }).slug}</code> },
          ].map(({ k, v }) => (
            <div key={k}>
              <dt className="text-zinc-500">{k}</dt>
              <dd className="mt-0.5 text-zinc-200">{v}</dd>
            </div>
          ))}
        </dl>
      </Card>

      {/* api keys */}
      <div>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-zinc-300 uppercase tracking-wider">API Keys ({(keys as ApiKey[]).length})</h2>
          <Button size="sm" onClick={() => setOpenKey(true)}><Plus className="h-3 w-3" /> New Key</Button>
        </div>

        {newKey && (
          <Card className="mb-3 border-emerald-600/30 bg-emerald-950/30">
            <p className="mb-2 text-xs font-semibold text-emerald-400">Copy this key now — it will not be shown again</p>
            <div className="flex items-center gap-2">
              <code className="flex-1 rounded bg-zinc-900 px-3 py-2 text-xs text-zinc-200 break-all">{newKey}</code>
              <Button size="sm" variant="secondary" onClick={() => navigator.clipboard.writeText(newKey!)}>
                <Copy className="h-3 w-3" />
              </Button>
            </div>
            <Button size="sm" variant="ghost" className="mt-2" onClick={() => setNewKey(null)}>Dismiss</Button>
          </Card>
        )}

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
                  <Button size="sm" variant="danger" onClick={() => revokeKey.mutate(k.externalId)}>
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </Td>
              </tr>
            ))}
            {(keys as ApiKey[]).length === 0 && (
              <tr><Td colSpan={6} className="text-center text-zinc-500">No API keys yet</Td></tr>
            )}
          </tbody>
        </Table>
      </div>

      {/* modals */}
      <Modal title="Create API Key" open={openKey} onClose={() => setOpenKey(false)}>
        <div className="space-y-4">
          <Field label="Key Name">
            <Input value={keyForm.name} onChange={e => setKeyForm({ name: e.target.value })} placeholder="Production Key" />
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpenKey(false)}>Cancel</Button>
            <Button onClick={() => createKey.mutate()} disabled={createKey.isPending}>
              {createKey.isPending ? "Creating…" : "Create"}
            </Button>
          </div>
        </div>
      </Modal>

      <Modal title="Change Status" open={openStatus} onClose={() => setOpenStatus(false)}>
        <div className="space-y-4">
          <Field label="Status">
            <Select value={statusVal} onChange={e => setStatusVal(e.target.value)}>
              {["active", "suspended", "trial", "deleted"].map(s => <option key={s} value={s}>{s}</option>)}
            </Select>
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpenStatus(false)}>Cancel</Button>
            <Button onClick={() => updateStatus.mutate()} disabled={updateStatus.isPending}>
              {updateStatus.isPending ? "Saving…" : "Save"}
            </Button>
          </div>
        </div>
      </Modal>

      <Modal title="Change Tier" open={openTier} onClose={() => setOpenTier(false)}>
        <div className="space-y-4">
          <Field label="Tier">
            <Select value={tierVal} onChange={e => setTierVal(e.target.value)}>
              {["free", "starter", "pro", "enterprise"].map(t => <option key={t} value={t}>{t}</option>)}
            </Select>
          </Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpenTier(false)}>Cancel</Button>
            <Button onClick={() => updateTier.mutate()} disabled={updateTier.isPending}>
              {updateTier.isPending ? "Saving…" : "Save"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
