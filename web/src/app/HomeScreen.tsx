"use client";

import { Ticket } from "lucide-react";
import Link from "next/link";
import { useLatestBooking } from "@/lib/bookings-store";

import { useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Button } from "@/components/Button";
import { EmptyState } from "@/components/EmptyState";
import { FilterChips } from "@/components/FilterChips";
import { HotelCard, HotelCardSkeleton } from "@/components/HotelCard";
import { MapToggle } from "@/components/MapToggle";
import { MapView } from "@/components/MapView";
import { LegalFooter } from "@/components/LegalPage";
import { OfflineNote } from "@/components/OfflineNote";
import { RoomNowButton } from "@/components/RoomNowButton";
import { SearchPill, type SearchSection } from "@/components/SearchPill";
import dynamic from "next/dynamic";
import { Wordmark } from "@/components/Wordmark";
import { cx, focusRing } from "@/components/ui";
import type { FilterCode } from "@/lib/amenities";
import { formatPrice, formatTime } from "@/lib/format";
import { useSearchResults, type InitialSearch } from "@/lib/queries";
import { analyticsId, firstTouch } from "@/lib/attribution";
import { api } from "@/lib/api";
import { nearFirst, NEAR_KM, roomNow } from "@/lib/room-now";
import { currentPosition } from "@/lib/native/plugins";
import { placeTag, track } from "@/lib/track";
import { readSearch, rememberSearch, writeSearch, type SearchState } from "@/lib/search-state";
import { stayDates } from "@/lib/stay";
import { TOWNS } from "@/lib/towns";

// Only mounted once the traveller opens it, so its code stays out of the first load.
const SearchSheet = dynamic(() => import("@/components/SearchSheet").then((m) => m.SearchSheet));

const noSubscribe = () => () => {};

const container = "mx-auto w-full max-w-[1440px] px-4 sm:px-6 lg:px-10";

export function HomeScreen({ initial }: { initial?: InitialSearch }) {
  const router = useRouter();
  const params = useSearchParams();
  const queryString = params.toString();
  const search = useMemo(() => readSearch(new URLSearchParams(queryString)), [queryString]);
  const showMap = params.get("view") === "map";

  const apiQuery = useMemo(() => {
    const dates = stayDates(search.arrive, search.leave);
    return {
      lat: search.lat,
      lng: search.lng,
      arrive: dates.arrive,
      leave: dates.leave,
      guests: search.guests,
      filters: search.filters.length ? search.filters.join(",") : undefined,
    };
  }, [search]);
  const results = useSearchResults(apiQuery, initial);

  const [sheet, setSheet] = useState<{
    open: boolean;
    section: SearchSection;
    initial?: SearchState;
    notice?: string;
  }>({ open: false, section: "where" });
  const [locating, setLocating] = useState(false);
  // Only known on the client: whether "room now" had to wait for late check-in to open.
  const opensAt = useSyncExternalStore(
    noSubscribe,
    () => (search.now ? roomNow().opensAt : undefined),
    () => undefined,
  );
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const pill = useRef<HTMLDivElement>(null);

  useEffect(() => {
    rememberSearch(writeSearch(search));
  }, [search]);

  // Each new search's funnel steps. The server-rendered first one is tagged with this browser's UTM tags and
  // analytics id here, since the server couldn't see them.
  const stayRequestId = results.data?.stayRequestId;
  const resultCount = results.data?.results.length ?? 0;
  const tracked = useRef<string | null>(null);
  useEffect(() => {
    if (!stayRequestId || tracked.current === stayRequestId) return;
    const first = tracked.current === null && initial?.data.stayRequestId === stayRequestId;
    tracked.current = stayRequestId;
    if (first) {
      const touch = firstTouch();
      api.attributeStay(stayRequestId, { ...touch, analyticsId: analyticsId() }).catch(() => {});
    }
    const context = { stayRequestId };
    track("search_started", { place: placeTag(search.place), guests: search.guests, room_now: !!search.now }, context);
    if (resultCount > 0) track("results_viewed", { results: Math.min(resultCount, 50), room_now: !!search.now }, context);
  }, [stayRequestId, resultCount, initial, search.place, search.guests, search.now]);

  const go = useCallback(
    (next: SearchState, view: "list" | "map" = showMap ? "map" : "list") => {
      const query = writeSearch(next);
      router.push(view === "map" ? `/?${query}&view=map` : `/?${query}`, {
        scroll: false,
      });
    },
    [router, showMap],
  );

  const closeSheet = useCallback(() => {
    setSheet((s) => ({ ...s, open: false }));
    pill.current?.querySelector<HTMLElement>("button")?.focus();
  }, []);

  /** One tap: here, arriving now (next quarter hour), 2 guests. Without location, the town picker with the same times. */
  function needRoomNow() {
    const times = roomNow();
    const base: SearchState = { ...search, arrive: times.arrive, leave: times.leave, guests: times.guests, filters: [], now: true };
    const pickTown = () =>
      setSheet({
        open: true,
        section: "where",
        initial: base,
        // Non-breaking space: "5:15 am" stays on one line.
        notice: `We couldn't get your location. Pick a town: arriving ${formatTime(times.arrive).replace(" ", "\u00a0")}, ${times.guests} guests.`,
      });
    setLocating(true);
    // The app asks with its own permission text; the website uses the browser's.
    currentPosition().then(
      (pos) => {
        setLocating(false);
        track("room_now_tapped", { located: true });
        go({ ...base, place: "here", placeLabel: "Your location", lat: pos.lat, lng: pos.lng }, "list");
      },
      () => {
        setLocating(false);
        track("room_now_tapped", { located: false });
        pickTown();
      },
    );
  }

  function toggleFilter(code: FilterCode) {
    const filters = search.filters.includes(code)
      ? search.filters.filter((f) => f !== code)
      : [...search.filters, code];
    go({ ...search, filters });
  }

  const data = results.data;
  const hotels = data?.results ?? [];
  const hrefFor = (slug: string) => `/hotels/${slug}?stay=${data?.stayRequestId ?? ""}`;
  const selected = hotels.find((h) => h.id === selectedId) ?? null;

  const header = (
    <header className="sticky top-0 z-[1001] border-b border-hairline bg-canvas">
      <div className={cx(container, "grid gap-3 py-3 md:grid-cols-[1fr_auto_1fr] md:items-center md:py-4")}>
        <div className="flex items-center justify-between md:contents">
          <Wordmark className="justify-self-start" />
          <YourBookingLink className="md:hidden" />
        </div>
        <SearchPill ref={pill} search={search} onOpen={(section) => setSheet({ open: true, section })} />
        <div className="hidden justify-self-end md:block">
          <YourBookingLink />
        </div>
      </div>
      <div className={cx(container, "pb-3")}>
        <FilterChips selected={search.filters} onToggle={toggleFilter} />
      </div>
    </header>
  );

  const summary = (
    <div className="mb-6">
      <h1 className="text-18 font-semibold text-ink">
        {results.isPending
          ? `Finding rooms near ${search.place === "here" ? "you" : search.placeLabel}`
          : `${hotels.length} ${hotels.length === 1 ? "place" : "places"} with rooms tonight near ${search.place === "here" ? "you" : search.placeLabel}`}
      </h1>
      <p className="text-14 text-ink-secondary">
        Arrive {formatTime(search.arrive)} · leave {formatTime(search.leave)} · {search.guests}{" "}
        {search.guests === 1 ? "guest" : "guests"} · within 10 km
      </p>
      {opensAt && (
        <p className="mt-1 text-14 text-ink-secondary">Late check-in starts at {formatTime(opensAt)}, so this is for then.</p>
      )}
    </div>
  );

  const grid = "grid grid-cols-1 gap-x-6 gap-y-10 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4";
  const cards = (list: typeof hotels, offset = 0) => (
    <div className={grid}>
      {list.map((hotel, i) => (
        <HotelCard key={hotel.id} hotel={hotel} href={hrefFor(hotel.slug)} priority={offset + i === 0} />
      ))}
    </div>
  );

  let body;
  if (results.isError) {
    body = (
      <div className="py-16 text-center">
        <p className="text-16 text-error">We couldn&apos;t load rooms right now.</p>
        <Button variant="outline" className="mt-4" onClick={() => results.refetch()}>
          Try again
        </Button>
      </div>
    );
  } else if (results.isPending) {
    body = (
      <div className="grid grid-cols-1 gap-x-6 gap-y-10 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4" aria-busy="true">
        {Array.from({ length: 8 }, (_, i) => (
          <HotelCardSkeleton key={i} />
        ))}
      </div>
    );
  } else if (hotels.length === 0) {
    const others = TOWNS.filter((t) => t.id !== search.place);
    body = (
      <EmptyState
        title="No rooms free nearby right now"
        body={
          search.filters.length
            ? "Nothing within 10 km matches every filter. Try removing one, or look in a nearby town."
            : "Nothing within 10 km has a room for this time. Try a nearby town or another arrival time."
        }
        actions={[
          ...(search.filters.length
            ? [
                {
                  label: "Remove filters",
                  href: `/?${writeSearch({ ...search, filters: [] })}`,
                },
              ]
            : []),
          ...others.slice(0, 2).map((t) => ({
            label: `Try ${t.name}`,
            href: `/?${writeSearch({ ...search, place: t.id, placeLabel: t.name, lat: t.lat, lng: t.lng })}`,
          })),
        ]}
      />
    );
  } else if (search.now) {
    const { near, further } = nearFirst(hotels);
    body = (
      <div className="space-y-10">
        <section aria-labelledby="near-title">
          <h2 id="near-title" className="mb-4 text-16 font-semibold text-ink">
            Within {NEAR_KM} km
          </h2>
          {near.length ? cards(near) : <p className="text-14 text-ink-secondary">Nothing free this close. The nearest are below.</p>}
        </section>
        {further.length > 0 && (
          <section aria-labelledby="further-title" className="border-t border-hairline pt-8">
            <h2 id="further-title" className="mb-4 text-16 font-semibold text-ink">
              Further away
            </h2>
            {cards(further, near.length)}
          </section>
        )}
      </div>
    );
  } else {
    body = cards(hotels);
  }

  return (
    <div className={cx(showMap && "flex h-dvh flex-col")}>
      {header}
      {showMap ? (
        <main className="relative min-h-0 flex-1">
          <MapView
            points={hotels.map((h) => ({
              id: h.id,
              lat: h.lat,
              lng: h.lng,
              label: formatPrice(h.fromPrice),
              title: h.name,
            }))}
            center={[search.lat, search.lng]}
            selectedId={selectedId}
            onSelect={setSelectedId}
          />
          {selected && (
            <div className="absolute inset-x-4 bottom-24 z-[1000] rounded-card border border-hairline bg-canvas p-3 md:left-6 md:right-auto md:w-[380px]">
              <HotelCard hotel={selected} href={hrefFor(selected.slug)} compact />
            </div>
          )}
        </main>
      ) : (
        <>
          <main className={cx(container, "pb-12 pt-6")}>
            <OfflineNote className="mb-4">
              You&apos;re offline. Showing your last search; prices may have changed.
            </OfflineNote>
            <RoomNowButton busy={locating} onClick={needRoomNow} className="mb-6" />
            {(results.isPending || hotels.length > 0) && summary}
            {body}
          </main>
          {/* Room for the floating "Show map" pill below the footer. */}
          <div className="pb-20">
            <LegalFooter />
          </div>
        </>
      )}
      {(hotels.length > 0 || showMap) && (
        <MapToggle
          showingMap={showMap}
          onToggle={() => {
            setSelectedId(null);
            go(search, showMap ? "list" : "map");
          }}
        />
      )}
      {sheet.open && (
        <SearchSheet
          section={sheet.section}
          initial={sheet.initial ?? search}
          notice={sheet.notice}
          onClose={closeSheet}
          onSearch={(next) => {
            setSheet({ open: false, section: sheet.section });
            go(next);
          }}
        />
      )}
    </div>
  );
}

/** Shown when this device booked something in the last day (bookings are remembered by id, no login). */
function YourBookingLink({ className }: { className?: string }) {
  const latest = useLatestBooking();
  if (!latest) return null;
  return (
    <Link
      href={`/pass/${latest.id}`}
      className={cx(
        "inline-flex min-h-11 items-center gap-2 rounded-full px-3 text-14 font-semibold text-ink hover:bg-soft",
        focusRing,
        className,
      )}
    >
      <Ticket size={20} strokeWidth={1.5} aria-hidden />
      Your booking
    </Link>
  );
}
