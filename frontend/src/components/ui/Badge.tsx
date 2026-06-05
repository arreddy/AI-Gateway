import { cn, statusColor } from "@/lib/utils";

export function Badge({ label, status }: { label: string; status?: string }) {
  return (
    <span className={cn(
      "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset",
      status ? statusColor(status) : "bg-zinc-800 text-zinc-300 ring-zinc-700"
    )}>
      {label}
    </span>
  );
}
