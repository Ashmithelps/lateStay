import type { ErrorEvent } from "@sentry/nextjs";

const PHONE = /(?<!\d)(?:\+?91[\s-]?|0)?[6-9]\d{4}[\s-]?\d{5}(?!\d)/g;

/** "+919876543210" -> "+91•••••3210" anywhere in a string. */
export function scrubPhones(text: string | undefined): string | undefined {
  return text?.replace(PHONE, (match) => {
    const digits = match.replace(/\D/g, "");
    return `${digits.length === 12 && digits.startsWith("91") ? "+91" : ""}•••••${digits.slice(-4)}`;
  });
}

/** Sentry beforeSend: no personal data leaves the app (phones masked; no request bodies, cookies or user). */
export function scrubEvent(event: ErrorEvent): ErrorEvent {
  event.message = scrubPhones(event.message);
  for (const exception of event.exception?.values ?? []) {
    exception.value = scrubPhones(exception.value);
  }
  for (const crumb of event.breadcrumbs ?? []) {
    crumb.message = scrubPhones(crumb.message);
    crumb.data = undefined;
  }
  if (event.request) {
    event.request.data = undefined;
    event.request.cookies = undefined;
    event.request.query_string = undefined;
  }
  event.user = undefined;
  return event;
}
