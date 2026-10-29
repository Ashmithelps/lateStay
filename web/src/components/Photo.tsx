"use client";

import Image from "next/image";
import { useState } from "react";
import { cx } from "./ui";

/**
 * A photo that fills its box, with a soft skeleton until it has loaded. {@code priority} is for the page's LCP
 * image: preloaded with high fetch priority and shown without the fade, so it paints as soon as it arrives.
 * {@code eager} skips lazy loading for other photos above the fold.
 */
export function Photo({ src, alt, sizes, priority, eager, className }: {
  src: string | null | undefined;
  alt: string;
  sizes: string;
  priority?: boolean;
  eager?: boolean;
  className?: string;
}) {
  const [loaded, setLoaded] = useState(false);
  return (
    <div className={cx("relative overflow-hidden bg-soft", !loaded && "animate-pulse", className)}>
      {src && (
        <Image
          src={src}
          alt={alt}
          fill
          sizes={sizes}
          preload={priority}
          fetchPriority={priority ? "high" : undefined}
          loading={priority || eager ? "eager" : "lazy"}
          onLoad={() => setLoaded(true)}
          className={cx("object-cover transition-opacity duration-200 ease-out", loaded || priority ? "opacity-100" : "opacity-0")}
        />
      )}
    </div>
  );
}
