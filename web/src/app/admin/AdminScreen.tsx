"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/Button";
import { TextField } from "@/components/Field";
import { Wordmark } from "@/components/Wordmark";
import { cx, focusRing } from "@/components/ui";
import { adminApi, signIn, signOut, useAdminCredentials } from "@/lib/admin-api";
import { BookingsBoard } from "./BookingsBoard";
import { HotelsPanel } from "./HotelsPanel";
import { NegotiationsPanel } from "./NegotiationsPanel";

const TABS = [
  { id: "bookings", label: "Bookings" },
  { id: "negotiations", label: "Negotiations" },
  { id: "hotels", label: "Hotels" },
] as const;
type Tab = (typeof TABS)[number]["id"];

/** The admin panel. Every call is checked by the backend with HTTP basic auth. */
export function AdminScreen() {
  const credentials = useAdminCredentials();
  const params = useSearchParams();
  const router = useRouter();
  const tab: Tab = TABS.some((t) => t.id === params.get("tab")) ? (params.get("tab") as Tab) : "bookings";

  if (!credentials) return <SignIn />;
  const api = adminApi(credentials);

  return (
    <div className="min-h-dvh bg-canvas">
      <header className="sticky top-0 z-10 border-b border-hairline bg-canvas">
        <div className="mx-auto flex max-w-[1440px] flex-wrap items-center gap-x-6 gap-y-1 px-4 py-2 lg:px-8">
          <div className="flex items-center gap-2">
            <Wordmark />
            <span className="text-14 font-medium text-ink-secondary">Admin</span>
          </div>
          <nav aria-label="Admin sections" className="order-3 -mx-1 flex w-full gap-1 overflow-x-auto no-scrollbar sm:order-none sm:w-auto">
            {TABS.map((t) => (
              <button
                key={t.id}
                type="button"
                aria-current={tab === t.id ? "page" : undefined}
                onClick={() => router.replace(`/admin?tab=${t.id}`)}
                className={cx(
                  "min-h-11 shrink-0 border-b-2 px-3 text-14 font-semibold transition-colors",
                  tab === t.id ? "border-ink text-ink" : "border-transparent text-ink-secondary hover:text-ink",
                  focusRing,
                )}
              >
                {t.label}
              </button>
            ))}
          </nav>
          <button
            type="button"
            onClick={signOut}
            className={cx("ml-auto min-h-11 rounded-control px-3 text-14 font-medium text-ink hover:bg-soft", focusRing)}
          >
            Sign out
          </button>
        </div>
      </header>
      <main className="mx-auto max-w-[1440px] px-4 py-6 lg:px-8">
        {tab === "bookings" && <BookingsBoard api={api} />}
        {tab === "negotiations" && <NegotiationsPanel api={api} />}
        {tab === "hotels" && <HotelsPanel api={api} />}
      </main>
    </div>
  );
}

function SignIn() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(username, password);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't sign in.");
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-dvh max-w-[400px] flex-col justify-center px-4 py-12">
      <Wordmark />
      <h1 className="mt-6 text-26 font-semibold text-ink">Admin sign-in</h1>
      <p className="mt-1 text-14 text-ink-secondary">Use ADMIN_USERNAME and ADMIN_PASSWORD from the backend&apos;s .env.</p>
      <form onSubmit={submit} className="mt-6 grid gap-4">
        <TextField id="username" label="Username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
        <TextField
          id="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {error && (
          <p role="alert" className="text-14 text-error">
            {error}
          </p>
        )}
        <Button type="submit" variant="ink" fullWidth disabled={busy || !username || !password}>
          {busy ? "Signing in…" : "Sign in"}
        </Button>
      </form>
    </main>
  );
}
