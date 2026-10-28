import { Suspense } from "react";
import { HaggleScreen } from "./HaggleScreen";

export default function HagglePage() {
  return (
    <Suspense fallback={null}>
      <HaggleScreen />
    </Suspense>
  );
}
