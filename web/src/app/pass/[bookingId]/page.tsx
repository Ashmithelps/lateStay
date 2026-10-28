import { Suspense } from "react";
import { PassScreen } from "./PassScreen";

export default function PassPage() {
  return (
    <Suspense fallback={null}>
      <PassScreen />
    </Suspense>
  );
}
