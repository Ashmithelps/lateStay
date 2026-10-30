"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { RoomNowButton } from "@/components/RoomNowButton";
import { roomNow } from "@/lib/room-now";
import { writeSearch } from "@/lib/search-params";
import { townById } from "@/lib/towns";
import { track } from "@/lib/track";

/** "I need a room now" on a town page: searches from the town's centre, arriving now. */
export function TownRoomNow({ slug, name, lat, lng }: { slug: string; name: string; lat: number; lng: number }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  return (
    <RoomNowButton
      busy={busy}
      onClick={() => {
        setBusy(true);
        track("room_now_tapped", { located: false });
        const times = roomNow();
        const known = townById(slug);
        const query = writeSearch({
          place: known ? known.id : "here",
          placeLabel: known ? known.name : name,
          lat: known ? known.lat : lat,
          lng: known ? known.lng : lng,
          arrive: times.arrive,
          leave: times.leave,
          guests: times.guests,
          filters: [],
          now: true,
        });
        router.push(`/?${query}`);
      }}
    />
  );
}
