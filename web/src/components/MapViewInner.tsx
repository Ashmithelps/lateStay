"use client";

import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { useEffect, useMemo } from "react";
import { MapContainer, Marker, TileLayer, useMap } from "react-leaflet";
import type { MapPoint, MapViewProps } from "./MapView";

function escapeHtml(text: string) {
  return text.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

function pin(point: MapPoint, selected: boolean) {
  return L.divIcon({
    className: "price-pin-wrap",
    html: `<span class="price-pin" data-selected="${selected}">${escapeHtml(point.label)}</span>`,
    iconSize: [0, 0],
  });
}

function FitBounds({ points, center }: { points: MapPoint[]; center: [number, number] }) {
  const map = useMap();
  const key = points.map((p) => p.id).join(",");
  useEffect(() => {
    if (points.length > 1) {
      map.fitBounds(L.latLngBounds(points.map((p) => [p.lat, p.lng])), { padding: [48, 48], maxZoom: 16 });
    } else {
      map.setView(points[0] ? [points[0].lat, points[0].lng] : center, 15);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, map]);
  return null;
}

/** Pans so the selected pin isn't hidden under the hotel card that floats over the bottom of the map. */
function KeepSelectedVisible({ points, selectedId }: { points: MapPoint[]; selectedId?: string | null }) {
  const map = useMap();
  useEffect(() => {
    const p = points.find((point) => point.id === selectedId);
    if (!p) return;
    const size = map.getSize();
    const at = map.latLngToContainerPoint([p.lat, p.lng]);
    const safeBottom = size.y - 260;
    if (at.y > safeBottom || at.y < 60) map.panBy([0, at.y - Math.min(safeBottom, size.y / 2.5)], { animate: true });
  }, [map, points, selectedId]);
  return null;
}

function AttributionPrefix() {
  const map = useMap();
  useEffect(() => {
    map.attributionControl.setPrefix('<a href="https://leafletjs.com">Leaflet</a>');
  }, [map]);
  return null;
}

export default function MapViewInner({ points, center, selectedId, onSelect, interactive = true, className }: MapViewProps) {
  const icons = useMemo(
    () => new Map(points.map((p) => [p.id, pin(p, p.id === selectedId)])),
    [points, selectedId],
  );
  return (
    <MapContainer
      center={center}
      zoom={14}
      scrollWheelZoom={interactive}
      dragging={interactive}
      zoomControl={interactive}
      doubleClickZoom={interactive}
      touchZoom={interactive}
      keyboard={interactive}
      className={className}
      style={{ height: "100%", width: "100%" }}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <FitBounds points={points} center={center} />
      <KeepSelectedVisible points={points} selectedId={selectedId} />
      <AttributionPrefix />
      {points.map((p) => (
        <Marker
          key={p.id}
          position={[p.lat, p.lng]}
          icon={icons.get(p.id)}
          title={p.title}
          zIndexOffset={p.id === selectedId ? 1000 : 0}
          keyboard={interactive}
          eventHandlers={onSelect ? { click: () => onSelect(p.id) } : undefined}
        />
      ))}
    </MapContainer>
  );
}
