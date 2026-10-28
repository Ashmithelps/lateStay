"use client";

import { useEffect, useState } from "react";

type Health = { status: string; postgis: string };

type State =
  | { kind: "loading" }
  | { kind: "ok"; health: Health }
  | { kind: "error" };

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export function HealthStatus() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    fetch(`${API_URL}/api/health`, { signal: controller.signal, cache: "no-store" })
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json() as Promise<Health>;
      })
      .then((health) => setState({ kind: "ok", health }))
      .catch(() => {
        if (!controller.signal.aborted) setState({ kind: "error" });
      });

    return () => controller.abort();
  }, []);

  if (state.kind === "loading") {
    return <p className="text-14 text-ink-secondary">Checking backend…</p>;
  }

  if (state.kind === "error") {
    return <p className="text-14 text-error">Backend unreachable</p>;
  }

  const postgisVersion = state.health.postgis.split(" ")[0];

  return (
    <p className="flex items-center gap-2 text-14 text-ink-secondary">
      <span aria-hidden className="size-2 rounded-full bg-pine" />
      Backend {state.health.status} · PostGIS {postgisVersion}
    </p>
  );
}
