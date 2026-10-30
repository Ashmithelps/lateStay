"use client";

import Link from "next/link";
import { cx, focusRing } from "@/components/ui";
import { TABS, type TabId } from "@/lib/native/tabs";

/** The app's bottom tabs: hairline top border, lucide icons, ink for the current tab, no ember. App only. */
export function TabBar({ active }: { active: TabId }) {
  return (
    <nav
      aria-label="Main"
      data-fixed
      className="native-tabbar fixed inset-x-0 bottom-0 z-[1000] border-t border-hairline bg-canvas pb-[env(safe-area-inset-bottom)]"
    >
      <ul className="mx-auto grid h-14 max-w-[560px] grid-cols-3">
        {TABS.map(({ id, label, href, icon: Icon }) => (
          <li key={id} className="flex">
            <Link
              href={href}
              aria-current={active === id ? "page" : undefined}
              className={cx(
                "flex flex-1 flex-col items-center justify-center gap-0.5 text-12 font-medium",
                active === id ? "text-ink" : "text-ink-secondary",
                focusRing,
              )}
            >
              <Icon size={24} strokeWidth={1.5} aria-hidden />
              {label}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  );
}
