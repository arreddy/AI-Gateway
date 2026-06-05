"use client";
import { useState, useEffect } from "react";
import { getApiKey, setApiKey, clearApiKey } from "@/lib/apiKey";
import { Key, Check, X } from "lucide-react";

export default function ApiKeyWidget() {
  const [stored, setStored] = useState("");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");

  useEffect(() => { setStored(getApiKey()); }, []);

  function save() {
    setApiKey(draft.trim());
    setStored(draft.trim());
    setEditing(false);
  }

  function clear() {
    clearApiKey();
    setStored("");
    setDraft("");
    setEditing(false);
  }

  if (editing) {
    return (
      <div className="border-t border-zinc-800 px-3 py-3 space-y-2">
        <p className="text-xs text-zinc-400">Paste your gateway API key:</p>
        <input
          autoFocus
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onKeyDown={e => e.key === "Enter" && save()}
          placeholder="sk-astra-…"
          className="w-full rounded bg-zinc-800 border border-zinc-700 px-2 py-1.5 text-xs text-zinc-100 placeholder-zinc-600 focus:outline-none focus:border-brand"
        />
        <div className="flex gap-1">
          <button onClick={save} className="flex items-center gap-1 rounded bg-brand px-2 py-1 text-xs text-white hover:bg-brand-dark">
            <Check className="h-3 w-3" /> Save
          </button>
          <button onClick={() => setEditing(false)} className="flex items-center gap-1 rounded bg-zinc-700 px-2 py-1 text-xs text-zinc-300 hover:bg-zinc-600">
            <X className="h-3 w-3" /> Cancel
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="border-t border-zinc-800 px-3 py-3">
      {stored ? (
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-1.5 text-xs text-emerald-400">
            <Key className="h-3 w-3" />
            <span className="font-mono">{stored.slice(0, 12)}…</span>
          </div>
          <div className="flex gap-1">
            <button onClick={() => { setDraft(stored); setEditing(true); }} className="text-xs text-zinc-500 hover:text-zinc-300">edit</button>
            <button onClick={clear} className="text-xs text-red-500 hover:text-red-400">clear</button>
          </div>
        </div>
      ) : (
        <button
          onClick={() => { setDraft(""); setEditing(true); }}
          className="flex w-full items-center gap-1.5 rounded px-2 py-1.5 text-xs text-zinc-500 hover:bg-zinc-800 hover:text-zinc-300"
        >
          <Key className="h-3 w-3" /> Set gateway API key
        </button>
      )}
    </div>
  );
}
