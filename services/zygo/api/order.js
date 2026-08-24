import { handler, readJson, requireFields, send } from "../lib/http.js";
import { unwrap, zygo } from "../lib/zygo.js";

/**
 * POST /api/order  { quoteId, idempotencyKey? }  ->  order
 *
 * Creating the order is the first step with a consequence, so the caller should have spoken the
 * merchant and the amount and got a yes before reaching here.
 *
 * `idempotencyKey` is passed through rather than generated here on purpose. A key minted
 * server-side is a new key on every retry, which is the same as having none: the app's retry of a
 * request whose response it never saw would create a second order. The app owns the key because the
 * app is what retries.
 */
export default handler("POST", async (req, res) => {
  const body = await readJson(req);
  const required = requireFields(body, ["quoteId"]);
  if (!required.ok) return send(res, required.status, required.body);

  const created = await unwrap(
    zygo().orders.create({
      quoteId: body.quoteId,
      ...(typeof body.idempotencyKey === "string" && body.idempotencyKey.trim()
        ? { idempotencyKey: body.idempotencyKey.trim() }
        : {}),
    }),
  );
  if (!created.ok) return send(res, created.status, created.body);

  const order = created.value;
  return send(res, 200, {
    orderId: order.order_id,
    publicId: order.public_id,
    currentState: order.current_state,
    fiatCurrency: order.fiat_currency,
    fiatAmountMinor: order.fiat_amount_minor,
    stablecoinAmountBase: order.stablecoin_amount_base,
  });
});
