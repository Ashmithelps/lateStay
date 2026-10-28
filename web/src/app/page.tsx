import { HealthStatus } from "@/components/health-status";

export default function Home() {
  return (
    <main className="mx-auto w-full max-w-screen-md px-4 py-10">
      <h1 className="text-32 font-semibold text-ink">LateStay</h1>
      <div className="mt-2">
        <HealthStatus />
      </div>
    </main>
  );
}
