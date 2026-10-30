"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Minus, Plus } from "lucide-react";
import { useState, type FormEvent } from "react";
import { cx, focusRing } from "@/components/ui";
import type { AdminApi, AdminFloorRule, AdminHotel, AdminRoomType, FloorRuleBody, SlotType } from "@/lib/admin-api";
import { amenity, AMENITY_CODES } from "@/lib/amenities";
import { formatDay, formatPrice } from "@/lib/format";
import { inputClass, LabelledInput, PanelMessage, SmallButton } from "./admin-ui";
import { PhotoManager } from "./PhotoManager";
import { WhatsAppLog } from "./WhatsAppLog";

/** Hotels with tonight's rooms-free stepper on the left; the selected hotel's details, rooms and floor rules on the right. */
export function HotelsPanel({ api }: { api: AdminApi }) {
  const hotels = useQuery({ queryKey: ["admin", "hotels"], queryFn: api.hotels });
  const tonight = useQuery({ queryKey: ["admin", "tonight"], queryFn: api.tonight, refetchInterval: 5000 });
  const [selected, setSelected] = useState<string | null>(null);

  if (!hotels.data) return <PanelMessage>{hotels.error?.message ?? "Loading hotels…"}</PanelMessage>;
  const current = hotels.data.find((h) => h.id === (selected ?? hotels.data[0]?.id));
  const free = new Map(tonight.data?.hotels.map((h) => [h.hotelId, h.roomsFree]) ?? []);
  const rows = new Map(tonight.data?.hotels.map((h) => [h.hotelId, h]) ?? []);

  return (
    <div className="grid gap-8 lg:grid-cols-[380px_1fr]">
      <section aria-labelledby="hotels-heading" className="min-w-0">
        <h2 id="hotels-heading" className="flex items-baseline justify-between border-b border-ink pb-2 text-14 font-semibold text-ink">
          Hotels
          {tonight.data && (
            <span className="text-12 font-medium text-ink-secondary">Rooms free, night of {formatDay(tonight.data.nightOf)}</span>
          )}
        </h2>
        <ul className="divide-y divide-hairline">
          {hotels.data.map((h) => (
            <li key={h.id} className={cx("flex items-center gap-2 py-2 pl-2", current?.id === h.id && "bg-soft")}>
              <button
                type="button"
                onClick={() => setSelected(h.id)}
                aria-current={current?.id === h.id ? "true" : undefined}
                className={cx("min-h-11 min-w-0 flex-1 rounded-control text-left text-14", focusRing)}
              >
                <span className={cx("block truncate font-semibold", h.active ? "text-ink" : "text-ink-secondary")}>
                  {h.name}
                </span>
                <span className="block truncate text-12 text-ink-secondary">
                  {h.town}
                  {!h.active && " · inactive"}
                  {rows.get(h.id) && rows.get(h.id)!.available !== (free.get(h.id) ?? 0) &&
                    ` · ${rows.get(h.id)!.available} sellable`}
                  {(rows.get(h.id)?.reliabilityFailures ?? 0) > 0 &&
                    ` · ${rows.get(h.id)!.reliabilityFailures} failed`}
                </span>
              </button>
              {tonight.data && <RoomsFree api={api} hotel={h} nightOf={tonight.data.nightOf} value={free.get(h.id) ?? 0} />}
            </li>
          ))}
        </ul>
      </section>
      {current && (
        <section aria-label={`Edit ${current.name}`} className="min-w-0">
          <HotelForm key={current.id} api={api} hotel={current} />
          <PhotoManager key={`photos-${current.id}`} api={api} hotel={current} />
          <WhatsAppLog key={`wa-${current.id}`} api={api} hotel={current} />
          <RoomTypes key={`rooms-${current.id}`} api={api} hotel={current} />
        </section>
      )}
    </div>
  );
}

function RoomsFree({ api, hotel, nightOf, value }: { api: AdminApi; hotel: AdminHotel; nightOf: string; value: number }) {
  const queryClient = useQueryClient();
  const set = useMutation({
    mutationFn: (rooms: number) => api.setRoomsFree(hotel.id, nightOf, rooms),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["admin", "tonight"] }),
  });
  const shown = set.isPending && set.variables !== undefined ? set.variables : value;
  const step = (delta: number) => set.mutate(Math.max(0, shown + delta));
  const stepClass = cx(
    "inline-flex size-10 items-center justify-center rounded-full border border-ink-secondary text-ink hover:border-ink",
    "disabled:cursor-not-allowed disabled:opacity-40",
    focusRing,
  );
  return (
    <div className="flex shrink-0 items-center gap-1.5" role="group" aria-label={`Rooms free tonight at ${hotel.name}`}>
      <button type="button" className={stepClass} onClick={() => step(-1)} disabled={shown === 0} aria-label="One fewer room">
        <Minus size={16} strokeWidth={1.5} aria-hidden />
      </button>
      <span className="w-7 text-center text-14 font-semibold text-ink tabular-nums" aria-live="polite">
        {shown}
      </span>
      <button type="button" className={stepClass} onClick={() => step(1)} aria-label="One more room">
        <Plus size={16} strokeWidth={1.5} aria-hidden />
      </button>
    </div>
  );
}

function HotelForm({ api, hotel }: { api: AdminApi; hotel: AdminHotel }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState(hotel);
  const save = useMutation({
    mutationFn: () => {
      const { id: _id, photos: _photos, ...body } = draft;
      void _id;
      void _photos;
      return api.updateHotel(hotel.id, body);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["admin", "hotels"] }),
  });
  const field = <K extends keyof AdminHotel>(key: K, value: AdminHotel[K]) => setDraft((d) => ({ ...d, [key]: value }));
  const toggleAmenity = (code: string) =>
    field("amenities", draft.amenities.includes(code) ? draft.amenities.filter((a) => a !== code) : [...draft.amenities, code]);

  return (
    <form
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        save.mutate();
      }}
      className="pb-6"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-18 font-semibold text-ink">{hotel.name}</h2>
        <label className="flex min-h-11 items-center gap-2 text-14 text-ink">
          <input type="checkbox" className="size-5 accent-ink" checked={draft.active} onChange={(e) => field("active", e.target.checked)} />
          Active in search
        </label>
      </div>
      <div className="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <LabelledInput label="Name" value={draft.name} onChange={(e) => field("name", e.target.value)} required />
        <LabelledInput label="Town" value={draft.town} onChange={(e) => field("town", e.target.value)} required />
        <LabelledInput label="WhatsApp number" value={draft.whatsappNumber} onChange={(e) => field("whatsappNumber", e.target.value)} />
        <LabelledInput
          label="Rooms in all"
          type="number"
          min="1"
          max="500"
          value={draft.totalRooms}
          onChange={(e) => field("totalRooms", Number(e.target.value))}
        />
        <LabelledInput label="Address" className="sm:col-span-2" value={draft.address} onChange={(e) => field("address", e.target.value)} />
        <LabelledInput label="Latitude" type="number" step="any" value={draft.lat} onChange={(e) => field("lat", Number(e.target.value))} />
        <LabelledInput label="Longitude" type="number" step="any" value={draft.lng} onChange={(e) => field("lng", Number(e.target.value))} />
      </div>
      <fieldset className="mt-4">
        <legend className="text-12 font-medium text-ink-secondary">Amenities</legend>
        <div className="mt-1 flex flex-wrap gap-x-4">
          {AMENITY_CODES.map((code) => (
            <label key={code} className="flex min-h-10 items-center gap-2 text-14 text-ink">
              <input type="checkbox" className="size-4 accent-ink" checked={draft.amenities.includes(code)} onChange={() => toggleAmenity(code)} />
              {amenity(code).label}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset className="mt-3">
        <legend className="text-12 font-medium text-ink-secondary">Policies (used by the Couple-friendly and local ID filters)</legend>
        <div className="mt-1 flex flex-wrap gap-x-4">
          <label className="flex min-h-10 items-center gap-2 text-14 text-ink">
            <input
              type="checkbox"
              className="size-4 accent-ink"
              checked={draft.policies.coupleFriendly}
              onChange={(e) => field("policies", { ...draft.policies, coupleFriendly: e.target.checked })}
            />
            Unmarried couples welcome
          </label>
          <label className="flex min-h-10 items-center gap-2 text-14 text-ink">
            <input
              type="checkbox"
              className="size-4 accent-ink"
              checked={draft.policies.localIdOk}
              onChange={(e) => field("policies", { ...draft.policies, localIdOk: e.target.checked })}
            />
            Local (same-state) ID accepted
          </label>
        </div>
      </fieldset>
      <LabelledInput
        label="Check-in notes"
        className="mt-3"
        value={draft.policies.checkInNotes ?? ""}
        onChange={(e) => field("policies", { ...draft.policies, checkInNotes: e.target.value || null })}
      />
      <div className="mt-4 flex items-center gap-3">
        <SmallButton type="submit" variant="ink" disabled={save.isPending}>
          {save.isPending ? "Saving…" : "Save hotel"}
        </SmallButton>
        {save.isSuccess && <span className="text-14 text-pine">Saved</span>}
        {save.error && (
          <span role="alert" className="text-14 text-error">
            {save.error.message}
          </span>
        )}
      </div>
    </form>
  );
}

function RoomTypes({ api, hotel }: { api: AdminApi; hotel: AdminHotel }) {
  const rooms = useQuery({ queryKey: ["admin", "rooms", hotel.id], queryFn: () => api.roomTypes(hotel.id) });
  return (
    <div className="pt-6">
      <h3 className="text-16 font-semibold text-ink">Room types and floor rules</h3>
      {!rooms.data ? (
        <PanelMessage>Loading rooms…</PanelMessage>
      ) : (
        <div className="mt-2 divide-y divide-hairline border-y border-hairline">
          {rooms.data.map((room) => (
            <RoomType key={room.id} api={api} hotel={hotel} room={room} />
          ))}
          <NewRoomType api={api} hotel={hotel} />
        </div>
      )}
    </div>
  );
}

function RoomFields({ value, onChange }: { value: Omit<AdminRoomType, "id" | "hotelId">; onChange: (v: Omit<AdminRoomType, "id" | "hotelId">) => void }) {
  return (
    <>
      <LabelledInput label="Room" value={value.name} onChange={(e) => onChange({ ...value, name: e.target.value })} required />
      <LabelledInput
        label="List price (₹)"
        type="number"
        min="1"
        value={value.listPrice}
        onChange={(e) => onChange({ ...value, listPrice: Number(e.target.value) })}
      />
      <LabelledInput
        label="Sleeps"
        type="number"
        min="1"
        max="20"
        value={value.maxGuests}
        onChange={(e) => onChange({ ...value, maxGuests: Number(e.target.value) })}
      />
    </>
  );
}

function RoomType({ api, hotel, room }: { api: AdminApi; hotel: AdminHotel; room: AdminRoomType }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState({ name: room.name, listPrice: room.listPrice, maxGuests: room.maxGuests });
  const save = useMutation({
    mutationFn: () => api.updateRoomType(hotel.id, room.id, draft),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["admin", "rooms", hotel.id] }),
  });
  return (
    <div className="py-4">
      <form
        className="grid items-end gap-3 sm:grid-cols-[2fr_1fr_1fr_auto]"
        onSubmit={(e) => {
          e.preventDefault();
          save.mutate();
        }}
      >
        <RoomFields value={draft} onChange={setDraft} />
        <SmallButton type="submit" disabled={save.isPending}>
          Save room
        </SmallButton>
      </form>
      {save.error && (
        <p role="alert" className="mt-1 text-12 text-error">
          {save.error.message}
        </p>
      )}
      <FloorRules api={api} room={room} />
    </div>
  );
}

function NewRoomType({ api, hotel }: { api: AdminApi; hotel: AdminHotel }) {
  const queryClient = useQueryClient();
  const empty = { name: "", listPrice: 1200, maxGuests: 2 };
  const [draft, setDraft] = useState(empty);
  const create = useMutation({
    mutationFn: () => api.createRoomType(hotel.id, draft),
    onSuccess: () => {
      setDraft(empty);
      void queryClient.invalidateQueries({ queryKey: ["admin", "rooms", hotel.id] });
    },
  });
  return (
    <form
      className="grid items-end gap-3 py-4 sm:grid-cols-[2fr_1fr_1fr_auto]"
      onSubmit={(e) => {
        e.preventDefault();
        create.mutate();
      }}
    >
      <RoomFields value={draft} onChange={setDraft} />
      <SmallButton type="submit" disabled={!draft.name || create.isPending}>
        Add room type
      </SmallButton>
      {create.error && (
        <p role="alert" className="text-12 text-error sm:col-span-4">
          {create.error.message}
        </p>
      )}
    </form>
  );
}

const DAYS = ["M", "T", "W", "T", "F", "S", "S"];
const DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
const NEW_RULE: FloorRuleBody = { slotType: "LATE_NIGHT", weekdays: 127, seasonStart: null, seasonEnd: null, floorPrice: 700, minHours: 0 };

function FloorRules({ api, room }: { api: AdminApi; room: AdminRoomType }) {
  const rules = useQuery({ queryKey: ["admin", "rules", room.id], queryFn: () => api.floorRules(room.id) });
  return (
    <div className="mt-3 rounded-control bg-soft p-3">
      <p className="text-12 font-medium text-ink-secondary">
        Floor rules (the lowest price the engine may reach; never shown to travellers)
      </p>
      {rules.data?.map((rule) => (
        <FloorRuleRow key={rule.id} api={api} room={room} rule={rule} />
      ))}
      <FloorRuleRow api={api} room={room} />
    </div>
  );
}

function FloorRuleRow({ api, room, rule }: { api: AdminApi; room: AdminRoomType; rule?: AdminFloorRule }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<FloorRuleBody>(rule ? { ...rule } : NEW_RULE);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["admin", "rules", room.id] });
  const save = useMutation({
    mutationFn: () => (rule ? api.updateFloorRule(room.id, rule.id, draft) : api.createFloorRule(room.id, draft)),
    onSuccess: () => {
      if (!rule) setDraft(NEW_RULE);
      void refresh();
    },
  });
  const remove = useMutation({ mutationFn: () => api.deleteFloorRule(room.id, rule!.id), onSuccess: refresh });
  const toggleDay = (i: number) => {
    const next = draft.weekdays ^ (1 << i);
    if (next > 0) setDraft({ ...draft, weekdays: next });
  };

  return (
    <form
      className="mt-3 flex flex-wrap items-end gap-3 border-t border-hairline pt-3 first-of-type:border-t-0"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <label className="grid gap-1 text-12 font-medium text-ink-secondary">
        Slot
        <select
          className={cx(inputClass, "w-36 bg-canvas")}
          value={draft.slotType}
          onChange={(e) => setDraft({ ...draft, slotType: e.target.value as SlotType })}
        >
          <option value="LATE_NIGHT">Late night</option>
          <option value="EARLY_MORNING">Early morning</option>
        </select>
      </label>
      <fieldset className="grid gap-1">
        <legend className="text-12 font-medium text-ink-secondary">Nights</legend>
        <div className="flex gap-1">
          {DAYS.map((day, i) => {
            const on = (draft.weekdays & (1 << i)) !== 0;
            return (
              <button
                key={i}
                type="button"
                aria-pressed={on}
                aria-label={DAY_NAMES[i]}
                onClick={() => toggleDay(i)}
                className={cx(
                  "size-10 rounded-full border text-12 font-semibold",
                  on ? "border-ink bg-ink text-canvas" : "border-ink-secondary bg-canvas text-ink",
                  focusRing,
                )}
              >
                {day}
              </button>
            );
          })}
        </div>
      </fieldset>
      <LabelledInput
        label="Season from"
        type="date"
        className="w-40"
        value={draft.seasonStart ?? ""}
        onChange={(e) => setDraft({ ...draft, seasonStart: e.target.value || null })}
      />
      <LabelledInput
        label="to"
        type="date"
        className="w-40"
        value={draft.seasonEnd ?? ""}
        onChange={(e) => setDraft({ ...draft, seasonEnd: e.target.value || null })}
      />
      <LabelledInput
        label={`Floor (list ${formatPrice(room.listPrice)})`}
        type="number"
        min="1"
        max={room.listPrice}
        className="w-36"
        value={draft.floorPrice}
        onChange={(e) => setDraft({ ...draft, floorPrice: Number(e.target.value) })}
      />
      <LabelledInput
        label="Min hours"
        type="number"
        min="0"
        max="24"
        className="w-24"
        value={draft.minHours}
        onChange={(e) => setDraft({ ...draft, minHours: Number(e.target.value) })}
      />
      <div className="flex gap-2">
        <SmallButton type="submit" variant={rule ? "outline" : "ink"} disabled={save.isPending}>
          {rule ? "Save rule" : "Add rule"}
        </SmallButton>
        {rule && (
          <SmallButton variant="ghost" onClick={() => remove.mutate()} disabled={remove.isPending}>
            Delete
          </SmallButton>
        )}
      </div>
      {(save.error || remove.error) && (
        <p role="alert" className="w-full text-12 text-error">
          {(save.error ?? remove.error)?.message}
        </p>
      )}
    </form>
  );
}
