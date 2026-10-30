"use client";

import { useMutation } from "@tanstack/react-query";
import { Star } from "lucide-react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { type ReactNode, useEffect, useRef, useState } from "react";
import { AmenityList } from "@/components/AmenityList";
import { BottomBar } from "@/components/BottomBar";
import { Button, ButtonLink } from "@/components/Button";
import { MapView } from "@/components/MapView";
import { PageHeader } from "@/components/PageHeader";
import { PhotoCarousel } from "@/components/PhotoCarousel";
import { RatingBadge } from "@/components/RatingBadge";
import { cx } from "@/components/ui";
import { track } from "@/lib/track";
import { api, ApiError, type HotelDetail, type RoomOption } from "@/lib/api";
import { formatDistance, formatPrice, formatTime, roomOptionLabel, roomsFreeLabel } from "@/lib/format";
import { formatStayMonth, tagLabel } from "@/lib/reviews";
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

/** Two options can start with the same room type (a group's pairs), so options are keyed by all their rooms. */
function optionKey(room: RoomOption): string {
  return room.roomTypeIds.join("+");
}

function RoomPicker({ rooms, selected, onSelect }: { rooms: RoomOption[]; selected: string; onSelect: (key: string) => void }) {
  return (
    <fieldset>
      <legend className="sr-only">Choose a room</legend>
      <div className="divide-y divide-hairline rounded-card border border-hairline">
        {rooms.map((room) => (
          <label
            key={optionKey(room)}
            className={cx("flex min-h-16 cursor-pointer items-center gap-4 px-4 py-3 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ink")}
          >
            <input
              type="radio"
              name="room"
              value={optionKey(room)}
              checked={selected === optionKey(room)}
              onChange={() => onSelect(optionKey(room))}
              className="size-5 accent-ink"
            />
            <span className="flex-1">
              <span className="block text-16 font-medium text-ink">{roomOptionLabel(room)}</span>
              <span className="block text-14 text-ink-secondary">
                {room.rooms > 1 ? `Sleeps ${room.maxGuests} together` : `Sleeps ${room.maxGuests}`}
              </span>
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

function Reviews({ reviews }: { reviews: HotelDetail["reviews"] }) {
  const { rating, tags, recent } = reviews;
  if (rating.count === 0) {
    return (
      <Section title="Reviews">
        <p className="text-16 text-ink-secondary">
          New on LateStay: no reviews yet. A rating shows once three guests have stayed and rated it.
        </p>
      </Section>
    );
  }
  return (
    <Section title="Reviews">
      <p className="flex items-center gap-2 text-18 font-semibold text-ink">
        {rating.value !== null ? (
          <>
            <Star size={18} strokeWidth={1.5} className="fill-ink" aria-hidden />
            <span className="tabular-nums">{rating.value.toFixed(1)}</span>
            <span className="text-16 font-normal text-ink-secondary">
              · {rating.count} {rating.count === 1 ? "review" : "reviews"}
            </span>
          </>
        ) : (
          <>
            New{" "}
            <span className="text-16 font-normal text-ink-secondary">
              · {rating.count} {rating.count === 1 ? "review" : "reviews"} so far
            </span>
          </>
        )}
      </p>
      {tags.length > 0 && (
        <ul className="mt-4 flex flex-wrap gap-2" aria-label="What guests mentioned">
          {tags.map((t) => (
            <li key={t.tag} className="rounded-full bg-soft px-3 py-1.5 text-14 text-ink">
              {tagLabel(t.tag)} <span className="text-ink-secondary tabular-nums">{t.count}</span>
            </li>
          ))}
        </ul>
      )}
      <ul className="mt-6 divide-y divide-hairline">
        {recent.map((r, i) => (
          <li key={i} className="py-4 first:pt-0">
            <p className="flex items-center gap-2 text-14 text-ink-secondary">
              <span className="inline-flex items-center gap-1 text-ink tabular-nums">
                <Star size={14} strokeWidth={1.5} className="fill-ink" aria-hidden />
                <span className="sr-only">Rated </span>
                {r.stars}
              </span>
              <span aria-hidden>·</span>
              <span>Stayed {formatStayMonth(r.stayedIn)}</span>
              {r.tags.length > 0 && (
                <>
                  <span aria-hidden>·</span>
                  <span>{r.tags.map(tagLabel).join(", ")}</span>
                </>
              )}
            </p>
            {r.text && <p className="mt-1 max-w-prose text-16 text-ink">{r.text}</p>}
          </li>
        ))}
      </ul>
    </Section>
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
  const room = rooms.find((r) => optionKey(r) === roomId) ?? rooms[0] ?? null;

  const start = useMutation({
    mutationFn: () => api.startNegotiation(stay!.stayRequestId, room!.roomTypeIds),
    onSuccess: (created) => {
      track("haggle_started", { hotel: slug }, { stayRequestId: stay?.stayRequestId, negotiationId: created.id });
      router.push(`/haggle/${created.id}`);
    },
  });

  const openedFor = useRef<string | null>(null);
  useEffect(() => {
    const key = `${slug}:${stayId ?? ""}`;
    if (openedFor.current === key) return;
    openedFor.current = key;
    track("hotel_opened", { hotel: slug }, { stayRequestId: stayId });
  }, [slug, stayId]);

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
              <RatingBadge rating={h.reviews.rating.value} count={h.reviews.rating.count} showCount size={16} />
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

            {room && room.rooms > 1 && (
              <p className="mt-4 max-w-prose text-16 text-ink-secondary">
                No single room here sleeps {stay!.guests}, so this is {room.rooms} rooms ({room.name}) side by side,
                booked and priced together.
              </p>
            )}
            {rooms.length > 1 && (
              <div className="mt-8">
                <h2 className="mb-4 text-18 font-semibold text-ink">
                  {room && room.rooms > 1 ? "Choose your rooms" : "Choose a room"}
                </h2>
                <RoomPicker rooms={rooms} selected={room ? optionKey(room) : ""} onSelect={setRoomId} />
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
              <Reviews reviews={h.reviews} />
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
                    {roomOptionLabel(room!)} · sleeps {room!.maxGuests} · arrive {formatTime(stay!.arrive)}
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
              <p className="truncate text-12 text-ink-secondary">{startError ?? roomOptionLabel(room!)}</p>
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
