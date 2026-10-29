"use client";

import { useMutation } from "@tanstack/react-query";
import { Star } from "lucide-react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useState, type ReactNode } from "react";
import { AmenityList } from "@/components/AmenityList";
import { BottomBar } from "@/components/BottomBar";
import { Button, ButtonLink } from "@/components/Button";
import { MapView } from "@/components/MapView";
import { PageHeader } from "@/components/PageHeader";
import { PhotoCarousel } from "@/components/PhotoCarousel";
import { cx } from "@/components/ui";
import { api, ApiError, type HotelDetail, type RoomOption } from "@/lib/api";
import { formatDistance, formatPrice, formatTime, roomsFreeLabel } from "@/lib/format";
import { useHotel } from "@/lib/queries";
import { useLastSearchHref } from "@/lib/search-state";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-t border-hairline py-8">
      <h2 className="mb-5 text-22 font-semibold text-ink">{title}</h2>
      {children}
    </section>
  );
}

function Rule({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 border-b border-hairline py-4 last:border-b-0 sm:grid-cols-[180px_1fr] sm:gap-6">
      <dt className="text-16 font-medium text-ink">{term}</dt>
      <dd className="text-16 text-ink-secondary">{children}</dd>
    </div>
  );
}

function RoomPicker({ rooms, selected, onSelect }: { rooms: RoomOption[]; selected: string; onSelect: (id: string) => void }) {
  return (
    <fieldset>
      <legend className="sr-only">Choose a room</legend>
      <div className="divide-y divide-hairline rounded-card border border-hairline">
        {rooms.map((room) => (
          <label
            key={room.id}
            className={cx("flex min-h-16 cursor-pointer items-center gap-4 px-4 py-3 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ink")}
          >
            <input
              type="radio"
              name="room"
              value={room.id}
              checked={selected === room.id}
              onChange={() => onSelect(room.id)}
              className="size-5 accent-ink"
            />
            <span className="flex-1">
              <span className="block text-16 font-medium text-ink">{room.name}</span>
              <span className="block text-14 text-ink-secondary">Sleeps {room.maxGuests}</span>
            </span>
            <span className="text-16 text-ink">
              <span className="font-semibold tabular-nums">from {formatPrice(room.fromPrice)}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function checkInRules(hotel: HotelDetail): { term: string; text: string }[] {
  const rules = [];
  if (hotel.stay) {
    const s = hotel.stay;
    rules.push({
      term: "Arrival window",
      text: `${s.slotType === "LATE_NIGHT" ? "Late-night" : "Early-morning"} stay: arrive between ${formatTime(s.arriveFrom)} and ${formatTime(s.arriveUntil)}, leave by ${formatTime(s.leaveBy)}. You're arriving at ${formatTime(s.arrive)}.`,
    });
  } else {
    rules.push({
      term: "Arrival window",
      text: "Late-night stays: arrive 9:00 pm to 3:00 am, leave by 10:00 am. Early-morning stays: arrive 4:00 am to 9:00 am, leave by 2:00 pm.",
    });
  }
  rules.push({
    term: "ID needed",
    text: hotel.policies.localIdOk
      ? "A government photo ID for every guest. Local IDs are accepted."
      : "A government photo ID for every guest. Local IDs aren't accepted, so bring one from outside the state.",
  });
  rules.push({
    term: "Couples",
    text: hotel.policies.coupleFriendly ? "Couples are welcome." : "Ask the front desk before booking as a couple.",
  });
  if (hotel.policies.checkInNotes) rules.push({ term: "From the hotel", text: hotel.policies.checkInNotes });
  return rules;
}

export function HotelScreen({ initial }: { initial?: HotelDetail }) {
  const { slug } = useParams<{ slug: string }>();
  const stayId = useSearchParams().get("stay") || undefined;
  const router = useRouter();
  const hotel = useHotel(slug, stayId, initial);
  const [roomId, setRoomId] = useState<string | null>(null);
  const backHref = useLastSearchHref();

  const stay = hotel.data?.stay ?? null;
  const rooms = stay?.roomTypes ?? [];
  const room = rooms.find((r) => r.id === roomId) ?? rooms[0] ?? null;

  const start = useMutation({
    mutationFn: () => api.startNegotiation(stay!.stayRequestId, room!.id),
    onSuccess: (created) => router.push(`/haggle/${created.id}`),
  });

  const header = <PageHeader backHref={backHref} backLabel="Back to results" />;

  if (hotel.isPending) {
    return (
      <>
        {header}
        <main className="mx-auto max-w-[1120px] px-4 pt-4 sm:px-6 lg:px-10" aria-busy="true">
          <div className="aspect-[20/19] animate-pulse rounded-card bg-soft md:aspect-auto md:h-[440px]" />
          <div className="mt-6 h-7 w-1/2 animate-pulse rounded-control bg-soft" />
          <div className="mt-3 h-4 w-1/3 animate-pulse rounded-control bg-soft" />
        </main>
      </>
    );
  }
  if (hotel.isError) {
    const missing = hotel.error instanceof ApiError && hotel.error.status === 404;
    return (
      <>
        {header}
        <main className="mx-auto max-w-md px-4 py-16 text-center">
          <h1 className="text-22 font-semibold text-ink">{missing ? "This place isn't available" : "We couldn't load this place"}</h1>
          <p className="mt-2 text-16 text-ink-secondary">{missing ? "It may have stopped taking late arrivals." : "Please try again in a moment."}</p>
          <ButtonLink href={backHref} variant="outline" className="mt-6">
            Back to results
          </ButtonLink>
        </main>
      </>
    );
  }

  const h = hotel.data;
  const canHaggle = Boolean(stay && room && stay.roomsFree > 0);
  const startError =
    start.error instanceof ApiError && start.error.status === 409
      ? "That room has just gone. Pick another room or go back to the results."
      : start.isError
        ? "We couldn't start the chat. Please try again."
        : null;

  const price = room ? (
    <p className="text-16 text-ink">
      <span className="text-22 font-semibold tabular-nums">from {formatPrice(room.fromPrice)}</span> tonight
    </p>
  ) : null;

  return (
    <>
      {header}
      <main className="mx-auto max-w-[1120px] px-4 pb-32 pt-4 sm:px-6 md:pt-6 lg:px-10 lg:pb-16">
        <PhotoCarousel photos={h.photos} name={h.name} />

        <div className="mt-6 lg:grid lg:grid-cols-[1fr_360px] lg:gap-16">
          <div>
            <h1 className="text-26 font-semibold text-ink">{h.name}</h1>
            <p className="mt-1 flex flex-wrap items-center gap-x-2 text-16 text-ink-secondary">
              {h.rating !== null && (
                <span className="inline-flex items-center gap-1 text-ink tabular-nums">
                  <Star size={16} strokeWidth={1.5} className="fill-ink" aria-hidden />
                  <span className="sr-only">Rated </span>
                  {h.rating.toFixed(1)}
                </span>
              )}
              <span aria-hidden>·</span>
              <span>{h.town}</span>
              {stay && (
                <>
                  <span aria-hidden>·</span>
                  <span>{formatDistance(stay.distanceKm)}</span>
                </>
              )}
            </p>
            {stay && <p className="mt-2 text-16 font-medium text-pine">{roomsFreeLabel(stay.roomsFree)}</p>}

            {rooms.length > 1 && (
              <div className="mt-8">
                <h2 className="mb-4 text-18 font-semibold text-ink">Choose a room</h2>
                <RoomPicker rooms={rooms} selected={room?.id ?? ""} onSelect={setRoomId} />
              </div>
            )}

            <div className="mt-8">
              <Section title="What this place has">
                <AmenityList codes={h.policies.coupleFriendly ? [...h.amenities, "couple_friendly"] : h.amenities} />
              </Section>
              <Section title="Late check-in rules">
                <dl>
                  {checkInRules(h).map((r) => (
                    <Rule key={r.term} term={r.term}>
                      {r.text}
                    </Rule>
                  ))}
                </dl>
              </Section>
              <Section title="Where you'll be">
                <div className="h-64 overflow-hidden rounded-card">
                  <MapView
                    points={[{ id: h.id, lat: h.lat, lng: h.lng, label: room ? formatPrice(room.fromPrice) : h.name, title: h.name }]}
                    center={[h.lat, h.lng]}
                    interactive={false}
                  />
                </div>
                <p className="mt-3 text-16 text-ink-secondary">{h.address}</p>
              </Section>
            </div>
          </div>

          <aside className="hidden lg:block">
            <div className="sticky top-24 rounded-card border border-hairline p-6">
              {canHaggle ? (
                <>
                  {price}
                  <p className="mt-1 text-14 text-ink-secondary">
                    {room!.name} · sleeps {room!.maxGuests} · arrive {formatTime(stay!.arrive)}
                  </p>
                  <Button fullWidth className="mt-5" onClick={() => start.mutate()} disabled={start.isPending}>
                    {start.isPending ? "Opening chat…" : "Start haggling"}
                  </Button>
                  <p className="mt-3 text-14 text-ink-secondary">
                    Chat with the front desk to agree a price. It can only come down from here.
                  </p>
                  {startError && <p className="mt-3 text-14 text-error">{startError}</p>}
                </>
              ) : (
                <>
                  <p className="text-18 font-semibold text-ink">See tonight&apos;s price</p>
                  <p className="mt-1 text-14 text-ink-secondary">
                    {stay ? "No room here fits your search tonight." : "Search with your arrival time to see prices and rooms free."}
                  </p>
                  <ButtonLink href="/" variant="outline" fullWidth className="mt-5">
                    Search for tonight
                  </ButtonLink>
                </>
              )}
            </div>
          </aside>
        </div>
      </main>

      <BottomBar
        left={
          canHaggle ? (
            <>
              <p className="text-16 text-ink">
                <span className="font-semibold tabular-nums">from {formatPrice(room!.fromPrice)}</span> tonight
              </p>
              <p className="truncate text-12 text-ink-secondary">{startError ?? room!.name}</p>
            </>
          ) : (
            <p className="text-14 text-ink-secondary">Search to see tonight&apos;s price</p>
          )
        }
        action={
          canHaggle ? (
            <Button onClick={() => start.mutate()} disabled={start.isPending} className="shrink-0">
              {start.isPending ? "Opening…" : "Start haggling"}
            </Button>
          ) : (
            <ButtonLink href="/" variant="outline" className="shrink-0">
              Search
            </ButtonLink>
          )
        }
      />
    </>
  );
}
