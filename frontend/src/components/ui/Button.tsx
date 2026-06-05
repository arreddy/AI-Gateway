import { cn } from "@/lib/utils";
import { type ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "danger" | "ghost";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: "sm" | "md";
}

const styles: Record<Variant, string> = {
  primary:   "bg-brand text-white hover:bg-brand-dark",
  secondary: "bg-zinc-700 text-zinc-100 hover:bg-zinc-600",
  danger:    "bg-red-600 text-white hover:bg-red-700",
  ghost:     "text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800",
};

export function Button({ variant = "primary", size = "md", className, ...props }: ButtonProps) {
  return (
    <button
      className={cn(
        "inline-flex items-center gap-1.5 rounded-lg font-medium transition-colors disabled:opacity-50 disabled:cursor-not-allowed",
        size === "sm" ? "px-2.5 py-1.5 text-xs" : "px-3.5 py-2 text-sm",
        styles[variant],
        className
      )}
      {...props}
    />
  );
}
