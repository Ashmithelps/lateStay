"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight, GripVertical, ImagePlus, Star, Trash2 } from "lucide-react";
import { useRef, useState, type DragEvent } from "react";
import { Photo } from "@/components/Photo";
import { cx, focusRing } from "@/components/ui";
import type { AdminApi, AdminHotel, AdminPhoto, RejectedPhoto } from "@/lib/admin-api";
import { PanelMessage, SmallButton } from "./admin-ui";

const MAX_BYTES = 10 * 1024 * 1024;

/**
 * A hotel's photos: drop or pick several JPEG/PNG files (10 MB each; the backend makes EXIF-free WebP at 400, 800
 * and 1600 px), drag to reorder (or use the arrows), make one the cover, delete. The cover is shown first everywhere.
 */
export function PhotoManager({ api, hotel }: { api: AdminApi; hotel: AdminHotel }) {
  const queryClient = useQueryClient();
  const key = ["admin", "photos", hotel.id];
  const photos = useQuery({ queryKey: key, queryFn: () => api.photos(hotel.id) });
  const [rejected, setRejected] = useState<RejectedPhoto[]>([]);
  const [dragging, setDragging] = useState<number | null>(null);
  const [over, setOver] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);
  const input = useRef<HTMLInputElement>(null);

  const done = (list: AdminPhoto[]) => {
    queryClient.setQueryData(key, list);
    void queryClient.invalidateQueries({ queryKey: ["admin", "hotels"] });
  };
  const upload = useMutation({
    mutationFn: (files: File[]) => api.uploadPhotos(hotel.id, files),
    onSuccess: (result) => {
      done(result.photos);
      setRejected((r) => [...r, ...result.rejected]);
    },
  });
  const reorder = useMutation({
    mutationFn: (ids: number[]) => api.reorderPhotos(hotel.id, ids),
    onMutate: (ids) => {
      const byId = new Map(photos.data?.map((p) => [p.id, p]));
      queryClient.setQueryData(key, ids.map((id, i) => ({ ...byId.get(id)!, sortOrder: i, cover: i === 0 })));
    },
    onSuccess: done,
    onError: () => void queryClient.invalidateQueries({ queryKey: key }),
  });
  const cover = useMutation({ mutationFn: (id: number) => api.coverPhoto(hotel.id, id), onSuccess: done });
  const remove = useMutation({
    mutationFn: (id: number) => api.deletePhoto(hotel.id, id),
    onSuccess: (list) => {
      setConfirmDelete(null);
      done(list);
    },
  });

  function add(files: FileList | File[]) {
    const list = Array.from(files);
    const tooBig = list.filter((f) => f.size > MAX_BYTES);
    setRejected(tooBig.map((f) => ({ fileName: f.name, code: "TOO_LARGE", message: "Photos can be up to 10 MB." })));
    const ok = list.filter((f) => f.size <= MAX_BYTES);
    if (ok.length) upload.mutate(ok);
  }

  function move(from: number, to: number) {
    const ids = photos.data!.map((p) => p.id);
    if (to < 0 || to >= ids.length || from === to) return;
    const [id] = ids.splice(from, 1);
    ids.splice(to, 0, id);
    reorder.mutate(ids);
  }

  function onDropFiles(e: DragEvent) {
    e.preventDefault();
    setOver(false);
    if (e.dataTransfer.files.length) add(e.dataTransfer.files);
  }

  const list = photos.data ?? [];
  const error = upload.error ?? reorder.error ?? cover.error ?? remove.error;

  return (
    <section aria-labelledby={`photos-${hotel.id}`} className="border-t border-hairline py-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 id={`photos-${hotel.id}`} className="text-16 font-semibold text-ink">
          Photos
        </h3>
        <p className="text-12 text-ink-secondary">The first photo is the cover. Drag to reorder.</p>
      </div>

      <div
        onDragOver={(e) => {
          if (e.dataTransfer.types.includes("Files")) {
            e.preventDefault();
            setOver(true);
          }
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDropFiles}
        className={cx(
          "mt-3 flex flex-col items-center justify-center gap-2 rounded-card border border-dashed px-4 py-6 text-center",
          over ? "border-ink bg-soft" : "border-ink-secondary",
        )}
      >
        <ImagePlus size={24} strokeWidth={1.5} className="text-ink-secondary" aria-hidden />
        <p className="text-14 text-ink">
          {upload.isPending ? "Uploading and resizing…" : "Drop JPEG or PNG photos here"}
        </p>
        <p className="text-12 text-ink-secondary">Up to 10 MB each. Location and camera data are removed.</p>
        <input
          ref={input}
          type="file"
          multiple
          // image/jpeg makes iPhones convert HEIC photos to JPEG when picking.
          accept="image/jpeg,image/png"
          className="sr-only"
          id={`photo-input-${hotel.id}`}
          onChange={(e) => {
            if (e.target.files) add(e.target.files);
            e.target.value = "";
          }}
        />
        <SmallButton onClick={() => input.current?.click()} disabled={upload.isPending}>
          Choose photos
        </SmallButton>
      </div>

      {rejected.length > 0 && (
        <ul className="mt-3 grid gap-1" aria-label="Photos not added">
          {rejected.map((r, i) => (
            <li key={`${r.fileName}-${i}`} className="text-12 text-error">
              <span className="font-semibold">{r.fileName}:</span> {r.message}
            </li>
          ))}
          <li>
            <button type="button" className={cx("text-12 text-ink underline underline-offset-4", focusRing)} onClick={() => setRejected([])}>
              Dismiss
            </button>
          </li>
        </ul>
      )}
      {error && (
        <p role="alert" className="mt-3 text-12 text-error">
          {error.message}
        </p>
      )}

      {photos.isPending ? (
        <PanelMessage>Loading photos…</PanelMessage>
      ) : list.length === 0 ? (
        <PanelMessage>No photos yet. Hotels with photos get far more bookings.</PanelMessage>
      ) : (
        <ol className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
          {list.map((p, i) => (
            <li
              key={p.id}
              draggable
              onDragStart={(e) => {
                setDragging(i);
                e.dataTransfer.effectAllowed = "move";
              }}
              onDragOver={(e) => {
                if (dragging !== null) e.preventDefault();
              }}
              onDrop={(e) => {
                e.preventDefault();
                if (dragging !== null) move(dragging, i);
                setDragging(null);
              }}
              onDragEnd={() => setDragging(null)}
              className={cx("grid gap-1.5", dragging === i && "opacity-50")}
            >
              <div className="relative">
                <Photo src={p.thumbnailUrl} alt={`${hotel.name}, photo ${i + 1}`} sizes="200px" className="aspect-[4/3] rounded-control" />
                <span className="absolute left-1.5 top-1.5 inline-flex size-7 cursor-grab items-center justify-center rounded-full bg-canvas text-ink" aria-hidden>
                  <GripVertical size={16} strokeWidth={1.5} />
                </span>
                {p.cover && (
                  <span className="absolute bottom-1.5 left-1.5 rounded-full bg-canvas px-2 py-0.5 text-12 font-semibold text-ink">
                    Cover
                  </span>
                )}
              </div>
              {confirmDelete === p.id ? (
                <div className="flex flex-wrap items-center gap-1">
                  <SmallButton variant="ink" onClick={() => remove.mutate(p.id)} disabled={remove.isPending}>
                    Delete
                  </SmallButton>
                  <SmallButton variant="ghost" onClick={() => setConfirmDelete(null)}>
                    Keep
                  </SmallButton>
                </div>
              ) : (
                <div className="flex items-center gap-0.5">
                  <IconButton label="Move earlier" onClick={() => move(i, i - 1)} disabled={i === 0 || reorder.isPending}>
                    <ChevronLeft size={16} strokeWidth={1.5} />
                  </IconButton>
                  <IconButton label="Move later" onClick={() => move(i, i + 1)} disabled={i === list.length - 1 || reorder.isPending}>
                    <ChevronRight size={16} strokeWidth={1.5} />
                  </IconButton>
                  <IconButton label="Make cover" onClick={() => cover.mutate(p.id)} disabled={p.cover || cover.isPending}>
                    <Star size={16} strokeWidth={1.5} />
                  </IconButton>
                  <IconButton label="Delete photo" onClick={() => setConfirmDelete(p.id)} className="ml-auto">
                    <Trash2 size={16} strokeWidth={1.5} />
                  </IconButton>
                </div>
              )}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function IconButton({ label, className, children, ...props }: { label: string } & React.ComponentProps<"button">) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      className={cx(
        "inline-flex size-10 items-center justify-center rounded-full text-ink hover:bg-soft disabled:cursor-not-allowed disabled:text-hairline",
        focusRing,
        className,
      )}
      {...props}
    >
      <span aria-hidden>{children}</span>
    </button>
  );
}
