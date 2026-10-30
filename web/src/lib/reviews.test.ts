import { describe, expect, it } from "vitest";
import { formatStayMonth, ratingLabel, reviewTextProblem } from "./reviews";

describe("reviews", () => {
  it("refuses long text and phone numbers like the backend", () => {
    expect(reviewTextProblem("Warm and quiet")).toBeNull();
    expect(reviewTextProblem("a".repeat(300))).toBeNull();
    expect(reviewTextProblem("a".repeat(301))).toBe("Please keep it to 300 characters.");
    expect(reviewTextProblem("call 98765 43210")).toBe("Please leave out phone numbers.");
    expect(reviewTextProblem("+91-98765-43210")).toBe("Please leave out phone numbers.");
    expect(reviewTextProblem("room 204, 2 nights")).toBeNull();
  });

  it("shows New until a rating exists", () => {
    expect(ratingLabel(null, 2)).toBe("New");
    expect(ratingLabel(4.4, 3)).toBe("4.4 · 3 reviews");
  });

  it("names the month of the stay", () => {
    expect(formatStayMonth("2026-11")).toBe("November 2026");
  });
});
