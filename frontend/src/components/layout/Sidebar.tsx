"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard, Building2, Key, Server, Bot,
  GitBranch, Shield, Activity, ChevronRight,
} from "lucide-react";
import { cn } from "@/lib/utils";

const NAV = [
  { href: "/",              label: "Dashboard",       icon: LayoutDashboard },
  { href: "/tenants",       label: "Tenants",         icon: Building2 },
  { href: "/api-keys",      label: "API Keys",        icon: Key },
  { href: "/mcp",           label: "MCP Servers",     icon: Server },
  { href: "/a2a",           label: "A2A Agents",      icon: Bot },
  { href: "/routing",       label: "Routing",         icon: GitBranch },
  { href: "/governance",    label: "Governance",      icon: Shield },
  { href: "/observability", label: "Observability",   icon: Activity },
];

export default function Sidebar() {
  const path = usePathname();
  return (
    <aside className="flex h-screen w-60 flex-col border-r border-zinc-800 bg-zinc-900">
      {/* logo */}
      <div className="flex h-14 items-center gap-2 border-b border-zinc-800 px-5">
        <span className="flex h-7 w-7 items-center justify-center rounded-md bg-brand text-xs font-bold">A</span>
        <span className="font-semibold tracking-tight">Astra Gateway</span>
      </div>

      {/* nav */}
      <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-0.5">
        {NAV.map(({ href, label, icon: Icon }) => {
          const active = href === "/" ? path === "/" : path.startsWith(href);
          return (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex items-center gap-2.5 rounded-md px-3 py-2 text-sm font-medium transition-colors",
                active
                  ? "bg-brand/15 text-brand-light"
                  : "text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100"
              )}
            >
              <Icon className="h-4 w-4 shrink-0" />
              {label}
              {active && <ChevronRight className="ml-auto h-3 w-3" />}
            </Link>
          );
        })}
      </nav>

      {/* footer */}
      <div className="border-t border-zinc-800 px-5 py-3 text-xs text-zinc-600">
        v1.0 · Astra Gateway
      </div>
    </aside>
  );
}
