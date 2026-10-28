"use client";

import Image from "next/image";
import { useState } from "react";
import { cx } from "./ui";

/** A photo that fills its box, with a soft skeleton until it has loaded. */
export function Photo({ src, alt, sizes, priority, className }: {
  src: string | null | undefined;
  alt: string;
  sizes: string;
  priority?: boolean;
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
          priority={priority}
          onLoad={() => setLoaded(true)}
          className={cx("object-cover transition-opacity duration-200 ease-out", loaded ? "opacity-100" : "opacity-0")}
        />
      )}
    </div>
  );
}
