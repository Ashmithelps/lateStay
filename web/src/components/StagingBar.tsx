/** Only on staging (NEXT_PUBLIC_APP_ENV=staging): a thin hairline bar so nobody mistakes it for the live site. */
export function StagingBar() {
  if (process.env.NEXT_PUBLIC_APP_ENV !== "staging") return null;
  return (
    <div role="note" className="border-b border-hairline bg-canvas px-4 py-1 text-center text-12 text-ink-secondary">
      Staging · test payments
    </div>
  );
}
