import type { Metadata } from "next";
import "./globals.css";
import Sidebar from "@/components/layout/Sidebar";
import Providers from "@/components/layout/Providers";

export const metadata: Metadata = {
  title: "Astra Gateway Portal",
  description: "Management portal for Astra AI Gateway",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className="dark">
      <body className="flex h-screen overflow-hidden">
        <Providers>
          <Sidebar />
          <main className="flex-1 overflow-y-auto">
            <div className="mx-auto max-w-7xl p-6">{children}</div>
          </main>
        </Providers>
      </body>
    </html>
  );
}
