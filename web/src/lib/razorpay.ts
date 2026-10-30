import { api, type Checkout, type VerifyPaymentRequest } from "./api";
import { isNative } from "./native/platform";

const CHECKOUT_SRC = "https://checkout.razorpay.com/v1/checkout.js";

type RazorpayResponse = { razorpay_order_id: string; razorpay_payment_id: string; razorpay_signature: string };
type RazorpayInstance = { open(): void; on(event: string, handler: (e: unknown) => void): void };
type RazorpayConstructor = new (options: Record<string, unknown>) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

export class PaymentDismissed extends Error {}

let loading: Promise<RazorpayConstructor> | null = null;

function loadCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay);
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = CHECKOUT_SRC;
    script.async = true;
    script.onload = () => (window.Razorpay ? resolve(window.Razorpay) : reject(new Error("Razorpay didn't load")));
    script.onerror = () => {
      loading = null;
      reject(new Error("Couldn't reach Razorpay. Check your connection and try again."));
    };
    document.body.appendChild(script);
  });
  return loading;
}

/**
 * Takes the deposit. Real mode opens Razorpay Checkout and resolves with its signed response; FAKE mode (local
 * backend without keys) asks the dev API for the same thing. Rejects with PaymentDismissed if the traveller closes
 * Checkout.
 */
export async function pay(checkout: Checkout, description: string): Promise<VerifyPaymentRequest> {
  if (checkout.mode === "FAKE") return api.devPay(checkout.orderId);
  if (isNative()) return payInApp(checkout, description);

  const Razorpay = await loadCheckout();
  return new Promise((resolve, reject) => {
    const instance = new Razorpay({
      key: checkout.keyId,
      order_id: checkout.orderId,
      amount: checkout.amountPaise,
      currency: checkout.currency,
      name: "LateStay",
      description,
      prefill: { name: checkout.name, contact: checkout.phone },
      timeout: checkout.timeoutSeconds,
      theme: { color: "#222222" },
      handler: (r: RazorpayResponse) =>
        resolve({ orderId: r.razorpay_order_id, paymentId: r.razorpay_payment_id, signature: r.razorpay_signature }),
      modal: { ondismiss: () => reject(new PaymentDismissed("Payment wasn't completed.")) },
    });
    instance.on("payment.failed", () => {
      // Checkout shows the failure and lets the traveller retry; dismissing it rejects above.
    });
    instance.open();
  });
}

/**
 * In the iOS/Android app: Razorpay's native SDK (capacitor-razorpay), which opens UPI apps itself and comes back
 * to the app with the result. Same order and signature check as the website; the deposit is for a real-world stay,
 * so it never goes through Apple's or Google's in-app purchase.
 */
async function payInApp(checkout: Checkout, description: string): Promise<VerifyPaymentRequest> {
  const { Checkout: NativeCheckout } = await import("capacitor-razorpay");
  let result: { response: unknown };
  try {
    result = await NativeCheckout.open({
      key: checkout.keyId,
      amount: String(checkout.amountPaise),
      order_id: checkout.orderId,
      currency: checkout.currency,
      name: "LateStay",
      description,
      prefill: { name: checkout.name, contact: checkout.phone },
      timeout: checkout.timeoutSeconds,
      theme: { color: "#222222" },
    } as { key: string; amount: string });
  } catch {
    // Cancelled, failed or another wallet chosen: Razorpay showed its own message; the room stays held.
    throw new PaymentDismissed("Payment wasn't completed.");
  }
  return nativeResult(result.response);
}

/** The SDK's success data, as an object (iOS) or JSON text (some Android versions). */
export function nativeResult(response: unknown): VerifyPaymentRequest {
  const data = (typeof response === "string" ? JSON.parse(response) : response) as Partial<RazorpayResponse> | null;
  if (!data?.razorpay_payment_id || !data.razorpay_order_id || !data.razorpay_signature) {
    throw new Error("The payment app didn't return a complete result. If money left your account, contact support.");
  }
  return { orderId: data.razorpay_order_id, paymentId: data.razorpay_payment_id, signature: data.razorpay_signature };
}
