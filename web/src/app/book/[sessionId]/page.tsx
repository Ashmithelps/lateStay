import { Suspense } from "react";
import { BookScreen } from "./BookScreen";

export default function BookPage() {
  return (
    <Suspense fallback={null}>
      <BookScreen />
    </Suspense>
  );
}
