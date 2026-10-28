import type { ReactNode } from "react";

/** Mobile sticky bar at the bottom of the screen: summary on the left, one action on the right. */
export function BottomBar({ left, action }: { left: ReactNode; action: ReactNode }) {
  return (
    <div data-fixed className="fixed inset-x-0 bottom-0 z-[900] border-t border-hairline bg-canvas px-4 pb-[max(12px,env(safe-area-inset-bottom))] pt-3 shadow-float lg:hidden">
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">{left}</div>
        {action}
      </div>
    </div>
  );
}
