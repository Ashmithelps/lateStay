import { describe, expect, it } from "vitest";
import { nativeResult } from "./razorpay";

describe("Razorpay's native result", () => {
  const ok = { razorpay_order_id: "order_1", razorpay_payment_id: "pay_1", razorpay_signature: "sig" };
  it("reads the object and the JSON forms", () => {
    expect(nativeResult(ok)).toEqual({ orderId: "order_1", paymentId: "pay_1", signature: "sig" });
    expect(nativeResult(JSON.stringify(ok))).toEqual({ orderId: "order_1", paymentId: "pay_1", signature: "sig" });
  });
  it("refuses an incomplete result", () => {
    expect(() => nativeResult({ razorpay_payment_id: "pay_1" })).toThrow(/complete result/);
    expect(() => nativeResult(null)).toThrow();
  });
});
