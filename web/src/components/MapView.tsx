"use client";

import dynamic from "next/dynamic";

export type MapPoint = { id: string; lat: number; lng: number; label: string; title: string };

export type MapViewProps = {
  points: MapPoint[];
  center: [number, number];
  selectedId?: string | null;
  onSelect?: (id: string) => void;
  interactive?: boolean;
  className?: string;
};

/** OpenStreetMap with price-pill markers. Leaflet needs the browser, so this loads on the client only. */
export const MapView = dynamic<MapViewProps>(() => import("./MapViewInner"), {
  ssr: false,
  loading: () => <div className="h-full w-full animate-pulse bg-soft" aria-hidden />,
});
