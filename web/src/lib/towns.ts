export type Town = { id: string; name: string; area: string; lat: number; lng: number };

export const TOWNS: Town[] = [
  { id: "manali", name: "Manali", area: "Mall Road", lat: 32.2432, lng: 77.1892 },
  { id: "old-manali", name: "Old Manali", area: "Village", lat: 32.2558, lng: 77.183 },
  { id: "kasol", name: "Kasol", area: "Market", lat: 32.0099, lng: 77.3149 },
];

export const DEFAULT_TOWN = TOWNS[0];

export function townById(id: string | null | undefined): Town | undefined {
  return TOWNS.find((t) => t.id === id);
}
