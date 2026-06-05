"use client";
import { useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { a2a, type A2aAgent } from "@/lib/api";
import { Table, Th, Td } from "@/components/ui/Table";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { Modal, Field, Input } from "@/components/ui/Modal";
import { Card, CardTitle } from "@/components/ui/Card";
import { formatDate } from "@/lib/utils";
import { Plus, RefreshCw, Trash2, Send } from "lucide-react";

export default function A2aPage() {
  const qc = useQueryClient();
  const [openRegister, setOpenRegister] = useState(false);
  const [openTask, setOpenTask] = useState<string | null>(null);
  const [form, setForm] = useState({ name: "", url: "", description: "" });
  const [taskMsg, setTaskMsg] = useState("");
  const [taskResult, setTaskResult] = useState<object | null>(null);

  const { data: agentData } = useQuery({ queryKey: ["a2a-agents"], queryFn: a2a.list });
  const agents: A2aAgent[] = agentData?.agents ?? [];

  const register = useMutation({
    mutationFn: () => a2a.register({ ...form, discover: true }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["a2a-agents"] }); setOpenRegister(false); setForm({ name: "", url: "", description: "" }); },
  });

  const deregister = useMutation({
    mutationFn: a2a.deregister,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["a2a-agents"] }),
  });

  const discover = useMutation({
    mutationFn: a2a.discover,
    onSuccess: () => qc.invalidateQueries({ queryKey: ["a2a-agents"] }),
  });

  const sendTask = useMutation({
    mutationFn: ({ id, message }: { id: string; message: string }) => a2a.sendTask(id, message),
    onSuccess: (data) => { setTaskResult(data as object); setTaskMsg(""); },
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">A2A Agents</h1>
          <p className="mt-1 text-sm text-zinc-400">{agents.length} registered agents</p>
        </div>
        <Button onClick={() => setOpenRegister(true)}><Plus className="h-4 w-4" /> Register Agent</Button>
      </div>

      <Table>
        <thead>
          <tr><Th>Name</Th><Th>URL</Th><Th>Skills</Th><Th>Capabilities</Th><Th>Status</Th><Th>Registered</Th><Th>Actions</Th></tr>
        </thead>
        <tbody>
          {agents.map((agent) => (
            <tr key={agent.id}>
              <Td className="font-medium">{agent.name}</Td>
              <Td><code className="text-xs text-zinc-400">{agent.url}</code></Td>
              <Td>
                <div className="flex flex-wrap gap-1">
                  {(agent.skills ?? []).map(s => <Badge key={s} label={s} />)}
                  {(agent.skills ?? []).length === 0 && <span className="text-xs text-zinc-600">none</span>}
                </div>
              </Td>
              <Td>
                <div className="flex flex-wrap gap-1">
                  {(agent.capabilities ?? []).map(c => <Badge key={c} label={c} />)}
                </div>
              </Td>
              <Td><Badge label={agent.status} status={agent.status} /></Td>
              <Td>{formatDate(agent.registeredAt)}</Td>
              <Td>
                <div className="flex gap-2">
                  <Button size="sm" variant="secondary" onClick={() => discover.mutate(agent.id)} title="Refresh card">
                    <RefreshCw className="h-3 w-3" />
                  </Button>
                  <Button size="sm" variant="secondary" onClick={() => { setOpenTask(agent.id); setTaskResult(null); }} title="Send task">
                    <Send className="h-3 w-3" />
                  </Button>
                  <Button size="sm" variant="danger" onClick={() => deregister.mutate(agent.id)}>
                    <Trash2 className="h-3 w-3" />
                  </Button>
                </div>
              </Td>
            </tr>
          ))}
          {agents.length === 0 && (
            <tr><Td colSpan={7} className="text-center text-zinc-500">No A2A agents registered</Td></tr>
          )}
        </tbody>
      </Table>

      {/* Register Modal */}
      <Modal title="Register A2A Agent" open={openRegister} onClose={() => setOpenRegister(false)}>
        <div className="space-y-4">
          <Field label="Name"><Input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} placeholder="Summarizer Agent" /></Field>
          <Field label="URL"><Input value={form.url} onChange={e => setForm(f => ({ ...f, url: e.target.value }))} placeholder="http://agent:8082" /></Field>
          <Field label="Description"><Input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} placeholder="What this agent does" /></Field>
          <p className="text-xs text-zinc-500">Agent card will be auto-discovered from <code>/.well-known/agent.json</code></p>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => setOpenRegister(false)}>Cancel</Button>
            <Button onClick={() => register.mutate()} disabled={register.isPending}>
              {register.isPending ? "Registering…" : "Register"}
            </Button>
          </div>
        </div>
      </Modal>

      {/* Send Task Modal */}
      <Modal title="Send Task to Agent" open={!!openTask} onClose={() => { setOpenTask(null); setTaskResult(null); }}>
        <div className="space-y-4">
          <Field label="Message">
            <textarea
              className="w-full rounded-md border border-zinc-700 bg-zinc-800 px-3 py-2 text-sm text-zinc-100 placeholder-zinc-500 focus:border-brand focus:outline-none focus:ring-1 focus:ring-brand"
              rows={3}
              value={taskMsg}
              onChange={e => setTaskMsg(e.target.value)}
              placeholder="Summarise the Q3 report…"
            />
          </Field>
          {taskResult && (
            <Card className="border-zinc-700">
              <CardTitle>Result</CardTitle>
              <pre className="max-h-40 overflow-auto text-xs text-zinc-300 whitespace-pre-wrap">
                {JSON.stringify(taskResult, null, 2)}
              </pre>
            </Card>
          )}
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="secondary" onClick={() => { setOpenTask(null); setTaskResult(null); }}>Close</Button>
            <Button onClick={() => openTask && sendTask.mutate({ id: openTask, message: taskMsg })} disabled={sendTask.isPending || !taskMsg}>
              {sendTask.isPending ? "Sending…" : "Send Task"}
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
