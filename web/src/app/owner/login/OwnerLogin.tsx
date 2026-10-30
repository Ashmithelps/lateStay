"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Wordmark } from "@/components/Wordmark";
import { ownerApi } from "@/lib/owner-api";
import { OwnerSignedOut } from "../OwnerScreen";

/** Opens the WhatsApp link: trades the one-time token for the owner cookie, then shows the owner page. */
export function OwnerLogin({ token }: { token: string }) {
  const router = useRouter();
  const [failed, setFailed] = useState(!token);
  const started = useRef(false);

  useEffect(() => {
    if (!token || started.current) return;
    started.current = true;
    ownerApi
      .signIn(token)
      // replace: the token leaves the address bar and the history.
      .then(() => router.replace("/owner"))
      .catch(() => setFailed(true));
  }, [token, router]);

  if (failed) return <OwnerSignedOut expired />;
  return (
    <main className="mx-auto flex min-h-dvh max-w-[560px] flex-col px-4 py-6">
      <Wordmark />
      <p className="mt-10 text-16 text-ink-secondary" role="status">
        Signing you in…
      </p>
    </main>
  );
}
