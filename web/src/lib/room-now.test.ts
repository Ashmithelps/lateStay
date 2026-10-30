import { describe, expect, it } from "vitest";
import { isUploadedPhoto, uploadedPhotoLoader } from "./photo-loader";
import { nearFirst, roomNow } from "./room-now";
import { leaveTimes, stayDates } from "./stay";

/** A Date at this India time on 29 Oct 2026 (UTC+5:30). */
function ist(time: string, date = "2026-10-29"): Date {
  return new Date(`${date}T${time}+05:30`);
}

describe("roomNow", () => {
  it("at 23:40 arrives 23:45 and leaves 09:00 the next morning, 2 guests", () => {
    expect(roomNow(ist("23:40"))).toEqual({ arrive: "23:45", leave: "09:00", guests: 2 });
    expect(stayDates("23:45", "09:00", ist("23:40"))).toEqual({ arrive: "2026-10-29T23:45", leave: "2026-10-30T09:00" });
  });

  it("at 05:10 arrives 05:15 and leaves 13:00 the same day", () => {
    expect(roomNow(ist("05:10"))).toEqual({ arrive: "05:15", leave: "13:00", guests: 2 });
    expect(stayDates("05:15", "13:00", ist("05:10"))).toEqual({ arrive: "2026-10-29T05:15", leave: "2026-10-29T13:00" });
  });

  it("keeps an exact quarter hour and rounds up seconds past it", () => {
    expect(roomNow(ist("23:30")).arrive).toBe("23:30");
    expect(roomNow(ist("23:30:20")).arrive).toBe("23:45");
  });

  it("rolls 23:59 over to midnight on the next date", () => {
    expect(roomNow(ist("23:59"))).toEqual({ arrive: "00:00", leave: "09:00", guests: 2 });
    expect(stayDates("00:00", "09:00", ist("23:59"))).toEqual({ arrive: "2026-10-30T00:00", leave: "2026-10-30T09:00" });
  });

  it("after midnight still leaves at 09:00 that morning", () => {
    expect(roomNow(ist("01:05"))).toEqual({ arrive: "01:15", leave: "09:00", guests: 2 });
    expect(stayDates("01:15", "09:00", ist("01:05"))).toEqual({ arrive: "2026-10-29T01:15", leave: "2026-10-29T09:00" });
  });

  it("the window edges: 08:50 is still early morning, 02:50 still late night", () => {
    expect(roomNow(ist("08:50"))).toEqual({ arrive: "09:00", leave: "13:00", guests: 2 });
    expect(roomNow(ist("02:50"))).toEqual({ arrive: "03:00", leave: "09:00", guests: 2 });
  });

  it("outside the arrival windows waits for the next one", () => {
    expect(roomNow(ist("15:20"))).toEqual({ arrive: "21:00", leave: "09:00", guests: 2, opensAt: "21:00" });
    expect(roomNow(ist("03:20"))).toEqual({ arrive: "04:00", leave: "13:00", guests: 2, opensAt: "04:00" });
  });

  it("offers 09:00 and 13:00 among the leave times for quarter-hour arrivals", () => {
    expect(leaveTimes("23:45")).toContain("09:00");
    expect(leaveTimes("23:45")[0]).toBe("01:00");
    expect(leaveTimes("05:15")).toContain("13:00");
  });
});

describe("nearFirst", () => {
  it("puts results within 3 km first, nearest first", () => {
    const { near, further } = nearFirst([{ distanceKm: 4.2 }, { distanceKm: 0.8 }, { distanceKm: 3 }, { distanceKm: 9 }]);
    expect(near.map((r) => r.distanceKm)).toEqual([0.8, 3]);
    expect(further.map((r) => r.distanceKm)).toEqual([4.2, 9]);
  });
});

describe("uploadedPhotoLoader", () => {
  const src = "https://photos.latestay.in/hotels/h/p-1600.webp";
  it("picks the smallest stored width that covers the request", () => {
    expect(uploadedPhotoLoader({ src, width: 390 })).toBe("https://photos.latestay.in/hotels/h/p-400.webp");
    expect(uploadedPhotoLoader({ src, width: 640 })).toBe("https://photos.latestay.in/hotels/h/p-800.webp");
    expect(uploadedPhotoLoader({ src, width: 1080 })).toBe("https://photos.latestay.in/hotels/h/p-1600.webp");
    expect(uploadedPhotoLoader({ src, width: 1920 })).toBe("https://photos.latestay.in/hotels/h/p-1600.webp");
  });
  it("leaves seed photos to next/image", () => {
    expect(isUploadedPhoto("/seed/pine-crest-1.png")).toBe(false);
    expect(isUploadedPhoto(src)).toBe(true);
  });
});
