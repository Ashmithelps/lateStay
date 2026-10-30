"use client";

import { useQuery } from "@tanstack/react-query";
import { MapPin } from "lucide-react";
import { MapView } from "@/components/MapView";
import { cx, focusRing } from "@/components/ui";
import { Wordmark } from "@/components/Wordmark";
import { API_URL, type SharedStay } from "@/lib/api";
import { formatDay, formatTime } from "@/lib/format";

const STATUS: Record<SharedStay["status"], { label: string; body: string; pine?: boolean }> = {
  WAITING: { label: "Booked", body: "Paid and waiting for the hotel to confirm. That usually takes a few minutes." },
  CONFIRMED: { label: "Confirmed by the hotel", body: "The hotel is holding the room for the arrival time below.", pine: true },
  CHECKED_IN: { label: "Checked in", body: "They've arrived and checked in.", pine: true },
  FINISHED: { label: "Stay finished", body: "The stay is over." },
  ENDED: { label: "No longer booked", body: "This booking was cancelled or fell through, so they won't be staying here." },
};

/** Polls the API every minute (no cookies, nothing cached), so the status moves without a reload. */
async function fetchStay(token: string): Promise<SharedStay | "GONE"> {
  const response = await fetch(`${API_URL}/api/stays/${encodeURIComponent(token)}`, {
    cache: "no-store",
    credentials: "omit",
  });
  if (response.status === 404 || response.status === 410) return "GONE";
  if (!response.ok) throw new Error(String(response.status));
  return (await response.json()) as SharedStay;
}

export function SharedStayScreen({ token, initial, gone }: {
  token: string;
  initial?: SharedStay;
  gone?: "EXPIRED" | "TURNED_OFF";
}) {
  const query = useQuery({
    queryKey: ["shared-stay", token],
    queryFn: () => fetchStay(token),
    initialData: initial,
    enabled: !gone,
    refetchInterval: 60_000,
    retry: 1,
  });
  const data = gone ? "GONE" : query.data;

  return (
    <>
      <header className="border-b border-hairline">
        <div className="mx-auto flex h-16 max-w-[640px] items-center px-4 sm:px-6">
          <Wordmark />
        </div>
      </header>
      <main className="mx-auto max-w-[640px] px-4 pb-16 pt-8 sm:px-6">
        {data === "GONE" || !data ? (
          <div className="py-12 text-center">
            <h1 className="text-22 font-semibold text-ink">
              {gone === "TURNED_OFF" ? "This link was turned off" : "This link has expired"}
            </h1>
            <p className="mt-2 text-16 text-ink-secondary">
              Shared stays stop showing a day after the stay ends, or when the traveller turns the link off.
            </p>
          </div>
        ) : (
          <Stay stay={data} />
        )}
      </main>
    </>
  );
}

function Stay({ stay }: { stay: SharedStay }) {
  const status = STATUS[stay.status];
  const directions = `https://www.google.com/maps/dir/?api=1&destination=${stay.lat},${stay.lng}`;
  const address = stay.address.includes(stay.town) ? stay.address : `${stay.address}, ${stay.town}`;
  return (
    <>
      <p className="text-14 text-ink-secondary">Someone shared their stay with you</p>
      <h1 className="mt-1 text-26 font-semibold text-ink">{stay.hotelName}</h1>
      <p className="mt-1 text-16 text-ink-secondary">{address}</p>

      <dl className="mt-8 divide-y divide-hairline border-y border-hairline">
        <div className="grid gap-1 py-4 sm:grid-cols-[160px_1fr] sm:gap-6">
          <dt className="text-16 text-ink-secondary">Status</dt>
          <dd>
            <span className={cx("text-16 font-semibold", status.pine ? "text-pine" : "text-ink")}>{status.label}</span>
            <span className="block text-14 text-ink-secondary">{status.body}</span>
          </dd>
        </div>
        <div className="grid gap-1 py-4 sm:grid-cols-[160px_1fr] sm:gap-6">
          <dt className="text-16 text-ink-secondary">Arriving around</dt>
          <dd className="text-16 font-semibold text-ink">
            {formatTime(stay.arriveAround)}, {formatDay(stay.arriveAround)}
          </dd>
        </div>
      </dl>

      <div className="mt-8 h-64 overflow-hidden rounded-card">
        <MapView
          points={[{ id: "stay", lat: stay.lat, lng: stay.lng, label: stay.hotelName, title: stay.hotelName }]}
          center={[stay.lat, stay.lng]}
          interactive={false}
        />
      </div>
      <a
        href={directions}
        target="_blank"
        rel="noreferrer"
        className={cx(
          "mt-4 inline-flex min-h-12 items-center gap-2 rounded-control border border-ink px-5 text-16 font-semibold text-ink",
          "hover:bg-soft",
          focusRing,
        )}
      >
        <MapPin size={20} strokeWidth={1.5} aria-hidden />
        Open in Maps
      </a>
      <p className="mt-8 text-14 text-ink-secondary">
        This page shows only the hotel, its address, the arrival time and the booking&apos;s status. It stops working a
        day after the stay ends.
      </p>
    </>
  );
}
