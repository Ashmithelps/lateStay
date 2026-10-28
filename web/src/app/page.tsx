import { Suspense } from "react";
import { HomeScreen } from "./HomeScreen";

export default function Home() {
  return (
    <Suspense fallback={null}>
      <HomeScreen />
    </Suspense>
  );
}
