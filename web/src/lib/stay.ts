/**
 * Arrival and leave times as the backend accepts them: late night arrivals 21:00-03:00 (leave by 10:00),
 * early morning arrivals 04:00-09:00 (leave by 14:00). All times are India Standard Time.
 */

export const DEFAULT_ARRIVE = "23:30";
export const DEFAULT_LEAVE = "09:00";

const IST_OFFSET_MINUTES = 330;

function toMinutes(time: string): number {
  const [h, m] = time.split(":").map(Number);
  return h * 60 + m;
}

function toTime(minutes: number): string {
  const m = ((minutes % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
}

function range(from: string, to: string): string[] {
  const out: string[] = [];
  let m = toMinutes(from);
  const end = toMinutes(to) < m ? toMinutes(to) + 1440 : toMinutes(to);
  for (; m <= end; m += 30) out.push(toTime(m));
  return out;
}

export const ARRIVAL_TIMES = [...range("21:00", "03:00"), ...range("04:00", "09:00")];

export function isLateNight(arrive: string): boolean {
  const m = toMinutes(arrive);
  return m >= toMinutes("21:00") || m <= toMinutes("03:00");
}

/** Leave times on the half hour, from one hour after arrival up to the slot's leave-by time. */
export function leaveTimes(arrive: string): string[] {
  const leaveBy = isLateNight(arrive) ? "10:00" : "14:00";
  return range(toTime(Math.ceil((toMinutes(arrive) + 60) / 30) * 30), leaveBy);
}

/** A leave time valid for this arrival: the given one if allowed, else the closest allowed one. */
export function fitLeave(arrive: string, leave: string): string {
  const options = leaveTimes(arrive);
  if (options.includes(leave)) return leave;
  return options.includes(DEFAULT_LEAVE) ? DEFAULT_LEAVE : options[options.length - 1];
}

function istParts(now: Date) {
  const shifted = new Date(now.getTime() + IST_OFFSET_MINUTES * 60_000);
  return {
    date: shifted.toISOString().slice(0, 10),
    minutes: shifted.getUTCHours() * 60 + shifted.getUTCMinutes(),
  };
}

function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/**
 * Turns picked times into local date-times for the API: the next arrival at that time (tonight, including after
 * midnight) and the first leave time after it.
 */
export function stayDates(arrive: string, leave: string, now: Date = new Date()): { arrive: string; leave: string } {
  const today = istParts(now);
  const arriveMinutes = toMinutes(arrive);
  let arriveDate = today.date;
  const afterMidnight = arriveMinutes < 6 * 60;
  if (afterMidnight && today.minutes >= 6 * 60) {
    arriveDate = addDays(arriveDate, 1);
  } else if (arriveMinutes < today.minutes - 60) {
    arriveDate = addDays(arriveDate, 1);
  }
  const leaveDate = toMinutes(leave) > arriveMinutes ? arriveDate : addDays(arriveDate, 1);
  return { arrive: `${arriveDate}T${arrive}`, leave: `${leaveDate}T${leave}` };
}

/** Quick counter-offers below the current offer: current minus 100 and minus 50, rounded down to 50. */
export function quickOffers(current: number): number[] {
  const offers = [current - 100, current - 50].map((v) => Math.floor(v / 50) * 50);
  return [...new Set(offers)].filter((v) => v > 0 && v < current);
}
