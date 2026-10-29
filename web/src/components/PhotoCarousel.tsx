"use client";

import { useRef, useState } from "react";
import { Photo } from "./Photo";
import { cx, focusRing } from "./ui";

/** Mobile: swipeable photos with dots. Desktop (md+): one large photo and four small. */
/** Same sizes in the mobile carousel and the desktop grid, so the first photo is preloaded once. */
const FIRST_SIZES = "(min-width: 768px) 50vw, 100vw";

export function PhotoCarousel({ photos, name }: { photos: string[]; name: string }) {
  const track = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState(0);

  function onScroll() {
    const el = track.current;
    if (el) setIndex(Math.round(el.scrollLeft / el.clientWidth));
  }

  function goTo(i: number) {
    track.current?.scrollTo({ left: i * track.current.clientWidth, behavior: "smooth" });
  }

  return (
    <>
      <div className="relative md:hidden">
        <div
          ref={track}
          onScroll={onScroll}
          className="no-scrollbar flex snap-x snap-mandatory overflow-x-auto rounded-card"
          aria-roledescription="carousel"
          aria-label={`${name} photos`}
        >
          {photos.map((src, i) => (
            <div key={src} className="w-full shrink-0 snap-center" aria-roledescription="slide" aria-label={`Photo ${i + 1} of ${photos.length}`}>
              <Photo src={src} alt={`${name}, photo ${i + 1}`} sizes={i === 0 ? FIRST_SIZES : "100vw"} priority={i === 0} className="aspect-[20/19] w-full" />
            </div>
          ))}
        </div>
        {photos.length > 1 && (
          <div className="absolute inset-x-0 bottom-2 flex justify-center">
            {photos.map((src, i) => (
              <button
                key={src}
                type="button"
                onClick={() => goTo(i)}
                aria-label={`Show photo ${i + 1}`}
                aria-current={i === index}
                className={cx("inline-flex size-6 items-center justify-center rounded-full", focusRing)}
              >
                <span className={cx("size-2 rounded-full transition-colors", i === index ? "bg-canvas" : "bg-canvas/50")} />
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="hidden h-[440px] grid-cols-4 grid-rows-2 gap-2 overflow-hidden rounded-card md:grid">
        {photos.slice(0, 5).map((src, i) => (
          <Photo
            key={src}
            src={src}
            alt={`${name}, photo ${i + 1}`}
            sizes={i === 0 ? FIRST_SIZES : "25vw"}
            priority={i === 0}
            className={cx("h-full w-full", i === 0 && "col-span-2 row-span-2")}
          />
        ))}
      </div>
    </>
  );
}
