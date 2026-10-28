import { FILTERS, type FilterCode } from "@/lib/amenities";
import { Chip } from "./Chip";

export function FilterChips({ selected, onToggle }: { selected: FilterCode[]; onToggle: (code: FilterCode) => void }) {
  return (
    <div role="group" aria-label="Filters" className="no-scrollbar -mx-4 flex gap-2 overflow-x-auto px-4 py-1 sm:mx-0 sm:px-0">
      {FILTERS.map(({ code, label, icon }) => (
        <Chip key={code} icon={icon} selected={selected.includes(code)} onClick={() => onToggle(code)}>
          {label}
        </Chip>
      ))}
    </div>
  );
}
