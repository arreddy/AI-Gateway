import { cn } from "@/lib/utils";
import { type TdHTMLAttributes, type ThHTMLAttributes } from "react";

export function Table({ children }: { children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-zinc-800">
      <table className="w-full text-sm">{children}</table>
    </div>
  );
}

export function Th({ children, className, ...props }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      className={cn("bg-zinc-900 px-4 py-3 text-left text-xs font-semibold text-zinc-400 uppercase tracking-wider", className)}
      {...props}
    >
      {children}
    </th>
  );
}

export function Td({ children, className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return (
    <td
      className={cn("border-t border-zinc-800 bg-zinc-950 px-4 py-3 text-zinc-300", className)}
      {...props}
    >
      {children}
    </td>
  );
}
