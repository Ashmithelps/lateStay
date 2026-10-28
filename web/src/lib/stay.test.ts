import { describe, expect, it } from "vitest";
import { formatDistance, formatPrice, formatTime } from "./format";
import { ARRIVAL_TIMES, fitLeave, leaveTimes, quickOffers, stayDates } from "./stay";

// 2026-10-28 19:45 IST
const EVENING = new Date("2026-10-28T14:15:00Z");
// 2026-10-29 00:40 IST
const AFTER_MIDNIGHT = new Date("2026-10-28T19:10:00Z");

describe("stayDates", () => {
  it("puts a late arrival tonight and the leave time the next morning", () => {
    expect(stayDates("23:30", "09:00", EVENING)).toEqual({ arrive: "2026-10-28T23:30", leave: "2026-10-29T09:00" });
  });
  it("puts an after-midnight arrival on the next date", () => {
    expect(stayDates("01:30", "09:00", EVENING)).toEqual({ arrive: "2026-10-29T01:30", leave: "2026-10-29T09:00" });
  });
  it("keeps tonight's date when it is already past midnight", () => {
    expect(stayDates("01:30", "09:00", AFTER_MIDNIGHT)).toEqual({ arrive: "2026-10-29T01:30", leave: "2026-10-29T09:00" });
  });
  it("handles early-morning arrivals", () => {
    expect(stayDates("05:30", "12:00", EVENING)).toEqual({ arrive: "2026-10-29T05:30", leave: "2026-10-29T12:00" });
  });
});

describe("time options", () => {
  it("only offers arrivals the backend accepts", () => {
    expect(ARRIVAL_TIMES[0]).toBe("21:00");
    expect(ARRIVAL_TIMES).toContain("03:00");
    expect(ARRIVAL_TIMES).not.toContain("03:30");
    expect(ARRIVAL_TIMES).toContain("04:00");
    expect(ARRIVAL_TIMES.at(-1)).toBe("09:00");
  });
  it("limits leave times to the slot", () => {
    expect(leaveTimes("23:30")[0]).toBe("00:30");
    expect(leaveTimes("23:30").at(-1)).toBe("10:00");
    expect(leaveTimes("05:30").at(-1)).toBe("14:00");
    expect(fitLeave("23:30", "13:00")).toBe("09:00");
  });
});

describe("quickOffers", () => {
  it("rounds down to 50 below the current offer", () => {
    expect(quickOffers(1300)).toEqual([1200, 1250]);
    expect(quickOffers(1170)).toEqual([1050, 1100]);
    expect(quickOffers(50)).toEqual([]);
  });
});

describe("format", () => {
  it("formats rupees, times and distances", () => {
    expect(formatPrice(1300)).toBe("₹1,300");
    expect(formatPrice(12500)).toBe("₹12,500");
    expect(formatTime("23:30")).toBe("11:30 pm");
    expect(formatTime("00:30:00")).toBe("12:30 am");
    expect(formatTime("2026-10-29T09:00")).toBe("9:00 am");
    expect(formatDistance(0.24)).toBe("240 m away");
    expect(formatDistance(1.53)).toBe("1.5 km away");
  });
});
