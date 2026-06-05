"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { mcp, type McpServer, type McpTool } from "@/lib/api";
import { Table, Th, Td } from "@/components/ui/Table";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { Modal, Field, Input } from "@/components/ui/Modal";
import { Card, CardTitle } from "@/components/ui/Card";
import { formatDate } from "@/lib/utils";
import { Plus, RefreshCw, Trash2, Wrench } from "lucide-react";

export default function McpPage() {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", url: "", description: "" });

  const { data: serverData } = useQuery({ queryKey: ["mcp-servers"], queryFn: mcp.listServers });
  const { data: toolData } = useQuery({ queryKey: ["mcp-tools"], queryFn: mcp.listTools });

  const servers: McpServer[] = serverData?.servers ?? [];
  const tools: McpTool[] = toolData?.tools ?? [];

  const register = useMutation({
    mutationFn: () => mcp.register(form),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["mcp-servers"] }); setOpen(false); setForm({ name: "", url: "", description: "" }); },
  });

  const deregister = useMutation({
    mutationFn: mcp.deregister,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcp-servers"] }),
  });

  const discover = useMutation({
    mutationFn: mcp.discover,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["mcp-tools"] }),
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">MCP Servers</h1>
          <p className="mt-1 text-sm text-zinc-400">{servers.length} registered · {tools.length} tools available</p>
        </div>
        <Button onClick={() => setOpen(true)}><Plus className="h-4 w-4" /> Register Server</Button>
      </div>

      <Table>
        <thead>
          <tr><Th>Name</Th><Th>URL</Th><Th>Status</Th><Th>Registered</Th><Th>Actions</Th></tr>
        </thead>
        <tbody>
          {servers.map((s) => (
            <tr key={s.id}>
              <Td className="font-medium">{s.name}</Td>
              <Td><code className="text-xs text-zinc-400">{s.url}</code></Td>
              <Td><Badge label={s.status} status={s.status} /></Td>
              <Td>{formatDate(s.registeredAt)}</Td>
              <Td>
                <div className="flex gap-2">
                  <Button size="sm" variant="secondary" onClick={() => discover.mutate(s.id)} title="Discover tools">
                    <RefreshCw className="h-3 w-3" />
                  </Button>
                  <Button size="sm" variant="danger" onClick={() => deregister.mutate(s.id)}>
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              </Td>
            </tr>
          ))}
          {servers.length === 0 && (
            <tr><Td colSpan={5} className="text-center text-zinc-500">No MCP servers registered</Td></tr>
          )}
        </tbody>
      </Table>

      {tools.length > 0 && (
        <Card>
          <CardTitle><Wrench className="inline h-3.5 w-3.5 mr-1.5" />Available Tools ({tools.length})</CardTitle>
          <div className="grid gap-2 sm:grid-cols-2">
            {tools.map((t) => (
              <div key={t.name} className="rounded-lg bg-zinc-800/60 px-3 py-2.5">
                <p className="text-sm font-mono font-medium text-zinc-200">{t.name}</p>
                {t.description && <p className="mt-0.5 text-xs text-zinc-400 line-clamp-2">{t.description}</p>}
                <p className="mt-1 text-xs text-zinc-600">via {t.serverUrl}</p>
              </div>
            ))}
          </div>
        </Card>
      )}

      <Modal title="Register MCP Server" open={open} onClose={() => setOpen(false)}>
        <div className="space-y-4">
          <Field label="Name"><Input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="My MCP Server" /></Field>
          <Field label="URL"><Input value={form.url} onChange={e => setForm(f => ({ ...f, url: e.target.value }))} placeholder="http://my-server:3000" /></Field>
          <Field label="Description"><Input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} placeholder="Optional description" /></Field>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpen(false)}>Cancel</Button>
            <Button onClick={() => register.mutate()} disabled={register.isPending}>
              {register.isPending ? "Registering…" : "Register"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
