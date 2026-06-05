import { cn } from "@/lib/utils";

export function Card({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("rounded-xl border border-zinc-800 bg-zinc-900 p-5", className)}>
      {children}
    </div>
  );
}

export function CardTitle({ children }: { children: React.ReactNode }) {
  return <h3 className="mb-4 text-sm font-semibold text-zinc-300 uppercase tracking-wider">{children}</h3>;
}
