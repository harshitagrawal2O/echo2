import { handler, readJson, requireFields, send } from "../lib/http.js";
import { unwrap, zygo } from "../lib/zygo.js";

/**
 * POST /api/status  { orderId, withTimeline? }  ->  order state
 *
 * Polled by the app after it has signed and sent the deposit.
 *
 * Deliberately not `orders.waitFor`: that blocks server-side until the order settles, which on a
 * serverless function means paying for the wait and risking the platform's execution limit cutting
 * it off mid-flight - and a truncated wait is indistinguishable from a failure. The app polls
 * instead, so it also stays free to keep its "still working" cue running while it does.
 */
export default handler("POST", async (req, res) => {
  const body = await readJson(req);
  const required = requireFields(body, ["orderId"]);
  if (!required.ok) return send(res, required.status, required.body);

  const client = zygo();
  const fetched = await unwrap(client.orders.get(body.orderId));
  if (!fetched.ok) return send(res, fetched.status, fetched.body);

  const order = fetched.value;
  const payload = {
    orderId: order.order_id,
    publicId: order.public_id,
    currentState: order.current_state,
    fiatCurrency: order.fiat_currency,
    fiatAmountMinor: order.fiat_amount_minor,
    stablecoinAmountBase: order.stablecoin_amount_base,
    refundTxHash: order.refund_tx_hash ?? null,
  };

  if (body.withTimeline === true) {
    const timeline = await unwrap(client.orders.timeline(body.orderId));
    // A timeline failure must not mask a successfully read order state, which is the field the
    // caller is actually waiting on.
    payload.timeline = timeline.ok
      ? timeline.value.map((event) => ({
          fromState: event.from_state,
          toState: event.to_state,
          actorType: event.actor_type,
          reasonCode: event.reason_code,
          createdAt: event.created_at,
        }))
      : null;
  }

  return send(res, 200, payload);
});
