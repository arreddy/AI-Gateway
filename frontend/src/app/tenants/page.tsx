"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { tenants, type Tenant } from "@/lib/api";
import { Table, Th, Td } from "@/components/ui/Table";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { Modal, Field, Input, Select } from "@/components/ui/Modal";
import { formatDate } from "@/lib/utils";
import { Plus } from "lucide-react";
import Link from "next/link";

export default function TenantsPage() {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", slug: "", email: "", tier: "free" });

  const { data = [], isLoading } = useQuery({ queryKey: ["tenants"], queryFn: tenants.list });

  const create = useMutation({
    mutationFn: () => tenants.create(form),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["tenants"] }); setOpen(false); setForm({ name: "", slug: "", email: "", tier: "free" }); },
  });

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Tenants</h1>
          <p className="mt-1 text-sm text-zinc-400">{data.length} registered tenants</p>
        </div>
        <Button onClick={() => setOpen(true)}><Plus className="h-4 w-4" /> New Tenant</Button>
      </div>

      {isLoading ? (
        <p className="text-sm text-zinc-500">Loading…</p>
      ) : (
        <Table>
          <thead>
            <tr><Th>Name</Th><Th>Slug</Th><Th>Email</Th><Th>Tier</Th><Th>Status</Th><Th>Created</Th><Th></Th></tr>
          </thead>
          <tbody>
            {data.map((t: Tenant) => (
              <tr key={t.externalId}>
                <Td className="font-medium text-zinc-100">{t.name}</Td>
                <Td><code className="text-xs text-zinc-400">{t.slug}</code></Td>
                <Td>{t.email}</Td>
                <Td><Badge label={t.tier} /></Td>
                <Td><Badge label={t.status} status={t.status} /></Td>
                <Td>{formatDate(t.createdAt)}</Td>
                <Td>
                  <Link href={`/tenants/${t.externalId}`} className="text-xs text-brand-light hover:underline">Manage →</Link>
                </Td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}

      <Modal title="Create Tenant" open={open} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          {[
            { label: "Name", key: "name" as const, placeholder: "Acme Corp" },
            { label: "Slug", key: "slug" as const, placeholder: "acme-corp" },
            { label: "Email", key: "email" as const, placeholder: "admin@acme.com" },
          ].map(({ label, key, placeholder }) => (
            <Field key={key} label={label}>
              <Input value={form[key]} onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))} placeholder={placeholder} />
            </Field>
          ))}
          <Field label="Tier">
            <Select value={form.tier} onChange={e => setForm(f => ({ ...f, tier: e.target.value }))}>
              {["free", "starter", "pro", "enterprise"].map(t => <option key={t} value={t}>{t}</option>)}
            </Select>
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
