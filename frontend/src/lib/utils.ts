import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatDate(iso: string | number) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}

export function statusColor(status: string) {
  switch (status?.toLowerCase()) {
    case "active":
    case "healthy":
    case "registered":
    case "success":
      return "bg-emerald-500/10 text-emerald-400 ring-emerald-500/20";
    case "suspended":
    case "degraded":
    case "warn":
      return "bg-amber-500/10 text-amber-400 ring-amber-500/20";
    case "deleted":
    case "revoked":
    case "failed":
    case "unhealthy":
      return "bg-red-500/10 text-red-400 ring-red-500/20";
    default:
      return "bg-zinc-500/10 text-zinc-400 ring-zinc-500/20";
  }
}
