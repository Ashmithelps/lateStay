import {
  Bus,
  CircleParking,
  Clock,
  Droplets,
  Flame,
  Heart,
  Heater,
  IdCard,
  Mountain,
  PlugZap,
  UtensilsCrossed,
  Wifi,
  type LucideIcon,
} from "lucide-react";

export type FilterCode = "heater" | "parking" | "couple_friendly" | "near_bus_stand" | "late_check_in";

export const FILTERS: { code: FilterCode; label: string; icon: LucideIcon }[] = [
  { code: "heater", label: "Heater", icon: Heater },
  { code: "parking", label: "Parking", icon: CircleParking },
  { code: "couple_friendly", label: "Couple-friendly", icon: Heart },
  { code: "near_bus_stand", label: "Near bus stand", icon: Bus },
  { code: "late_check_in", label: "Late check-in", icon: Clock },
];

const AMENITIES: Record<string, { label: string; icon: LucideIcon }> = {
  heater: { label: "Room heater", icon: Heater },
  hot_water: { label: "Hot water all night", icon: Droplets },
  couple_friendly: { label: "Couple-friendly", icon: Heart },
  parking: { label: "Parking", icon: CircleParking },
  late_check_in: { label: "Late check-in", icon: Clock },
  near_bus_stand: { label: "Near the bus stand", icon: Bus },
  wifi: { label: "Wi-Fi", icon: Wifi },
  mountain_view: { label: "Mountain view", icon: Mountain },
  power_backup: { label: "Power backup", icon: PlugZap },
  food: { label: "Food on request", icon: UtensilsCrossed },
  geyser: { label: "Geyser", icon: Flame },
  local_id: { label: "Local ID accepted", icon: IdCard },
};

/** Amenity codes the admin editor offers. */
export const AMENITY_CODES = Object.keys(AMENITIES);

export function amenity(code: string): { label: string; icon: LucideIcon | null } {
  const known = AMENITIES[code];
  if (known) return known;
  const words = code.replace(/_/g, " ");
  return { label: words.charAt(0).toUpperCase() + words.slice(1), icon: null };
}
