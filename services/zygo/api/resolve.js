import { handler, readJson, requireFields, send } from "../lib/http.js";
import { unwrap, zygo, zygoEnvironment } from "../lib/zygo.js";

/**
 * POST /api/resolve  { rawQr }  ->  merchant destination
 *
 * Turns the raw text decoded from a UPI QR into a Zygo merchant destination. This is the only step
 * that interprets the QR, and it is deliberately on the server: whether a payee is a real,
 * payable merchant is not a question a client should answer for itself.
 *
 * `payee_identifier` and `merchant_name` come back so the app can read them aloud. That readback is
 * the whole safety story for a user who cannot see the code they just photographed - it is the only
 * point at which a wrong or swapped QR can be caught, so the app must speak it and wait.
 */
export default handler("POST", async (req, res) => {
  const body = await readJson(req);
  const required = requireFields(body, ["rawQr"]);
  if (!required.ok) return send(res, required.status, required.body);

  const resolved = await unwrap(zygo().merchants.resolveQr(body.rawQr));
  if (!resolved.ok) return send(res, resolved.status, resolved.body);

  const merchant = resolved.value;
  return send(res, 200, {
    environment: zygoEnvironment(),
    merchantPaymentDestinationId: merchant.merchant_payment_destination_id,
    merchantName: merchant.merchant_name,
    payeeIdentifier: merchant.payee_identifier,
    railCode: merchant.rail_code,
    suggestedAmountMinor: merchant.suggested_amount_minor ?? null,
    merchantCategory: merchant.merchant_category ?? null,
  });
});
