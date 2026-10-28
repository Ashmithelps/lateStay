import { Suspense } from "react";
import { HotelScreen } from "./HotelScreen";

export default function HotelPage() {
  return (
    <Suspense fallback={null}>
      <HotelScreen />
    </Suspense>
  );
}
