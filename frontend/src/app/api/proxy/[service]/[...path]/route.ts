import { NextRequest, NextResponse } from "next/server";

const SERVICES: Record<string, string> = {
  gateway:       process.env.GATEWAY_URL      ?? "http://localhost:8080",
  auth:          process.env.AUTH_URL         ?? "http://localhost:8083",
  routing:       process.env.ROUTING_URL      ?? "http://localhost:8084",
  governance:    process.env.GOVERNANCE_URL   ?? "http://localhost:8085",
  observability: process.env.OBSERVABILITY_URL ?? "http://localhost:8086",
};

async function forward(
  request: NextRequest,
  { params }: { params: { service: string; path: string[] } }
) {
  const base = SERVICES[params.service];
  if (!base) return NextResponse.json({ error: "Unknown service" }, { status: 404 });

  const target = `${base}/${params.path.join("/")}`;

  // Forward auth + content-type from the browser request
  const headers: Record<string, string> = { "content-type": "application/json" };
  const auth = request.headers.get("authorization");
  if (auth) headers["authorization"] = auth;

  const init: RequestInit = { method: request.method, headers };
  if (!["GET", "HEAD"].includes(request.method)) {
    const text = await request.text();
    if (text) init.body = text;
  }

  try {
    const upstream = await fetch(target, init);
    const text = await upstream.text();
    let body: unknown;
    try { body = JSON.parse(text); } catch { body = text; }
    return NextResponse.json(body, { status: upstream.status });
  } catch (err) {
    return NextResponse.json(
      { error: "Upstream unavailable", detail: String(err) },
      { status: 502 }
    );
  }
}

export const GET    = forward;
export const POST   = forward;
export const PUT    = forward;
export const PATCH  = forward;
export const DELETE = forward;
