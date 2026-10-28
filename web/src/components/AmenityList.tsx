import { Check } from "lucide-react";
import { amenity } from "@/lib/amenities";

export function AmenityList({ codes }: { codes: string[] }) {
  const items = codes.map(amenity);
  return (
    <ul className="grid grid-cols-1 gap-x-8 gap-y-4 sm:grid-cols-2">
      {items.map(({ label, icon: Icon }) => {
        const Shown = Icon ?? Check;
        return (
          <li key={label} className="flex items-center gap-4 text-16 text-ink">
            <Shown size={24} strokeWidth={1.5} aria-hidden />
            {label}
          </li>
        );
      })}
    </ul>
  );
}
