"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { governance, type GovernancePolicy } from "@/lib/api";
import { Table, Th, Td } from "@/components/ui/Table";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { Modal, Field, Input, Select } from "@/components/ui/Modal";
import { Card, CardTitle } from "@/components/ui/Card";
import { Plus, PowerOff, ShieldCheck } from "lucide-react";

export default function GovernancePage() {
  const qc = useQueryClient();
  const [tenantId, setTenantId] = useState(1);
  const [open, setOpen] = useState(false);
  const [checkOpen, setCheckOpen] = useState(false);
  const [form, setForm] = useState({ name: "", policyType: "pii_redaction", action: "block", priority: 100, tenantId });
  const [check, setCheck] = useState({ content: "", type: "prompt" });
  const [checkResult, setCheckResult] = useState<{ safe: boolean; issues: string[]; action: string } | null>(null);

  const { data: policies = [] } = useQuery({
    queryKey: ["gov-policies", tenantId],
    queryFn: () => governance.listPolicies(tenantId),
  });

  const create = useMutation({
    mutationFn: () => governance.createPolicy({ ...form, tenantId }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["gov-policies"] }); setOpen(false); },
  });

  const disable = useMutation({
    mutationFn: governance.disablePolicy,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["gov-policies"] }),
  });

  const checkContent = useMutation({
    mutationFn: () => governance.checkContent(check.content, check.type),
    onSuccess: (data) => setCheckResult(data),
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Governance</h1>
          <p className="mt-1 text-sm text-zinc-400">Content policies and safety checks</p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setCheckOpen(true)}>
            <ShieldCheck className="h-4 w-4" /> Check Content
          </Button>
          <Button onClick={() => setOpen(true)}><Plus className="h-4 w-4" /> New Policy</Button>
        </div>
      </div>

      <div className="mb-3 flex items-center gap-3">
        <span className="text-sm text-zinc-400">Tenant ID:</span>
        <Input type="number" value={tenantId} onChange={e => setTenantId(Number(e.target.value))} className="w-24" />
      </div>

      <Table>
        <thead>
          <tr><Th>Name</Th><Th>Type</Th><Th>Action</Th><Th>Priority</Th><Th>Enabled</Th><Th></Th></tr>
        </thead>
        <tbody>
          {(policies as GovernancePolicy[]).map((p: GovernancePolicy) => (
            <tr key={p.id}>
              <Td className="font-medium">{p.name}</Td>
              <Td><Badge label={p.policyType} /></Td>
              <Td><Badge label={p.action} status={p.action === "block" ? "deleted" : p.action === "warn" ? "suspended" : "active"} /></Td>
              <Td>{p.priority}</Td>
              <Td><Badge label={p.isEnabled ? "enabled" : "disabled"} status={p.isEnabled ? "active" : "deleted"} /></Td>
              <Td>
                {p.isEnabled && (
                  <Button size="sm" variant="secondary" onClick={() => disable.mutate(p.id)}>
                    <PowerOff className="h-3 w-3" />
                  </Button>
                )}
              </Td>
            </tr>
          ))}
          {(policies as GovernancePolicy[]).length === 0 && (
            <tr><Td colSpan={6} className="text-center text-zinc-500">No governance policies</Td></tr>
          )}
        </tbody>
      </Table>

      {/* Create Policy Modal */}
      <Modal title="Create Governance Policy" open={open} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          <Field label="Name"><Input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Block PII" /></Field>
          <Field label="Policy Type">
            <Select value={form.policyType} onChange={e => setForm(f => ({ ...f, policyType: e.target.value }))}>
              {["pii_redaction", "toxicity_filter", "injection_detect", "custom"].map(t => <option key={t} value={t}>{t}</option>)}
            </Select>
          </Field>
          <Field label="Action">
            <Select value={form.action} onChange={e => setForm(f => ({ ...f, action: e.target.value }))}>
              {["allow", "block", "redact", "warn", "quarantine"].map(a => <option key={a} value={a}>{a}</option>)}
            </Select>
          </Field>
          <Field label="Priority"><Input type="number" value={form.priority} onChange={e => setForm(f => ({ ...f, priority: Number(e.target.value) }))} /></Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
            <Button onClick={() => create.mutate()} disabled={create.isPending}>{create.isPending ? "Creating…" : "Create"}</Button>
          </div>
        </div>
      </Modal>

      {/* Check Content Modal */}
      <Modal title="Check Content Against Policies" open={checkOpen} onClose={() => { setCheckOpen(false); setCheckResult(null); }}>
        <div className="space-y-4">
          <Field label="Content">
            <textarea className="w-full rounded-md border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:border-brand focus:outline-none focus:ring-1 focus:ring-brand" rows={4} value={check.content} onChange={e => setCheck(c => ({ ...c, content: e.target.value }))} placeholder="Enter text to check…" />
          </Field>
          <Field label="Type">
            <Select value={check.type} onChange={e => setCheck(c => ({ ...c, type: e.target.value }))}>
              <option value="prompt">Prompt</option>
              <option value="response">Response</option>
            </Select>
          </Field>
          {checkResult && (
            <Card className={checkResult.safe ? "border-emerald-600/30" : "border-red-600/30"}>
              <div className="flex items-center gap-2">
                <Badge label={checkResult.safe ? "SAFE" : "UNSAFE"} status={checkResult.safe ? "active" : "deleted"} />
                <Badge label={`action: ${checkResult.action}`} />
              </div>
              {checkResult.issues.length > 0 && (
                <ul className="mt-2 space-y-1">
                  {checkResult.issues.map(i => <li key={i} className="text-xs text-zinc-400">• {i}</li>)}
                </ul>
              )}
            </Card>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => { setCheckOpen(false); setCheckResult(null); }}>Close</Button>
            <Button onClick={() => checkContent.mutate()} disabled={checkContent.isPending || !check.content}>
              {checkContent.isPending ? "Checking…" : "Check"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
