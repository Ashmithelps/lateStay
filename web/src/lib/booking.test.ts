import { describe, expect, it } from "vitest";
import { BOOKINGS_KEY, latestBooking, readBookings, saveBooking, type SavedBooking } from "./bookings-store";
import { formatIndianMobile, normaliseIndianMobile } from "./phone";

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => void data.set(key, value),
  };
}

const booking = (id: string, savedAt = 1_000): SavedBooking => ({ id, sessionId: `s-${id}`, hotelName: "Pine Crest", savedAt });

describe("normaliseIndianMobile", () => {
  it.each(["9876543210", "98765 43210", "+91 98765 43210", "+919876543210", "919876543210", "09876543210", "98765-43210"])(
    "accepts %s",
    (input) => expect(normaliseIndianMobile(input)).toBe("+919876543210"),
  );

  it.each(["", "12345", "5876543210", "987654321", "98765432101", "+1 415 555 0100", "98765abcde"])(
    "rejects %s",
    (input) => expect(normaliseIndianMobile(input)).toBeNull(),
  );

  it("formats for display", () => {
    expect(formatIndianMobile("+919876543210")).toBe("+91 98765 43210");
  });
});

describe("saved bookings", () => {
  it("keeps the newest first without duplicates", () => {
    const storage = memoryStorage();
    saveBooking(booking("a"), storage);
    saveBooking(booking("b"), storage);
    saveBooking(booking("a", 2_000), storage);
    expect(readBookings(storage).map((b) => b.id)).toEqual(["a", "b"]);
  });

  it("ignores broken or foreign data", () => {
    expect(readBookings(memoryStorage({ [BOOKINGS_KEY]: "not json" }))).toEqual([]);
    expect(readBookings(memoryStorage({ [BOOKINGS_KEY]: '[{"id":1},{"id":"x","sessionId":"y","savedAt":5}]' }))).toEqual([
      { id: "x", sessionId: "y", savedAt: 5 },
    ]);
    expect(readBookings(null)).toEqual([]);
  });

  it("only offers a booking saved in the last day", () => {
    const day = 24 * 60 * 60 * 1000;
    expect(latestBooking([booking("a", 0)], day - 1)?.id).toBe("a");
    expect(latestBooking([booking("a", 0)], day)).toBeNull();
    expect(latestBooking([])).toBeNull();
  });
});

describe("Sentry scrubbing", async () => {
  const { scrubEvent, scrubPhones } = await import("./sentry-scrub");

  it("masks phone numbers and drops personal fields", () => {
    expect(scrubPhones("call +91 98765 43210 or 9812345678")).toBe("call +91•••••3210 or •••••5678");
    expect(scrubPhones("booking 4821 for ₹1,300 at 1794416400")).toBe("booking 4821 for ₹1,300 at 1794416400");
    const event = scrubEvent({
      type: undefined,
      message: "Payment failed for 919876543210",
      exception: { values: [{ value: "Bad phone 09876543210" }] },
      request: { data: { phone: "9876543210" }, cookies: { a: "b" }, query_string: "phone=9876543210" },
      user: { id: "x" },
    });
    expect(event.message).toBe("Payment failed for +91•••••3210");
    expect(event.exception?.values?.[0].value).toBe("Bad phone •••••3210");
    expect(event.request?.data).toBeUndefined();
    expect(event.user).toBeUndefined();
  });
});
