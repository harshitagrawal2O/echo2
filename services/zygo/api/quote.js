import { handler, readJson, requireFields, send } from "../lib/http.js";
import { selectUsdcSolana, unwrap, zygo } from "../lib/zygo.js";

/** Refuse implausible amounts before they reach a quote. Minor units: 100 = 1.00 INR. */
const MIN_FIAT_MINOR = 100;
const MAX_FIAT_MINOR = Number(process.env.ZYGO_MAX_FIAT_MINOR ?? 2_000_00);

/**
 * POST /api/quote  { merchantPaymentDestinationId, fiatAmountMinor }  ->  quote
 *
 * Prices the payment in USDC and returns the full fee breakdown, because the app has to say what
 * the user is about to spend and "about five hundred rupees" is not good enough when the amount is
 * the thing being authorised.
 *
 * The asset is chosen here rather than by the caller: a client picking its own `assetId` is a
 * client that can be talked into paying in the wrong token.
 */
export default handler("POST", async (req, res) => {
  const body = await readJson(req);
  const required = requireFields(body, ["merchantPaymentDestinationId"]);
  if (!required.ok) return send(res, required.status, required.body);

  const amount = body.fiatAmountMinor;
  if (!Number.isSafeInteger(amount) || amount < MIN_FIAT_MINOR || amount > MAX_FIAT_MINOR) {
    return send(res, 400, {
      error: "BAD_AMOUNT",
      message:
        `fiatAmountMinor must be a whole number of paise between ${MIN_FIAT_MINOR} and ` +
        `${MAX_FIAT_MINOR}. Raise ZYGO_MAX_FIAT_MINOR deliberately to allow more.`,
    });
  }

  const client = zygo();
  const assets = await unwrap(client.quotes.listAssets());
  if (!assets.ok) return send(res, assets.status, assets.body);
  const usdc = selectUsdcSolana(assets.value);

  const quoted = await unwrap(
    client.quotes.create({
      merchantPaymentDestinationId: body.merchantPaymentDestinationId,
      assetId: usdc.asset_id,
      fiatAmountMinor: amount,
    }),
  );
  if (!quoted.ok) return send(res, quoted.status, quoted.body);

  const quote = quoted.value;
  return send(res, 200, {
    quoteId: quote.quote_id,
    publicId: quote.public_id,
    fiatCurrency: quote.fiat_currency,
    fiatAmountMinor: quote.fiat_amount_minor,
    expiresAt: quote.expires_at,
    asset: {
      assetId: usdc.asset_id,
      symbol: usdc.symbol,
      decimals: usdc.decimals,
      chainName: usdc.chain_name,
      mint: usdc.contract_or_mint,
    },
    // Every component, not just the total: the user is entitled to hear what the spread and fees
    // are before authorising, and a single "total" hides the answer.
    amounts: {
      stablecoinAmountBase: quote.stablecoin_amount_base,
      lpSpreadBase: quote.lp_spread_base,
      platformFeeBase: quote.platform_fee_base,
      networkFeeEstimateBase: quote.network_fee_estimate_base,
      totalBase: quote.total_base,
    },
  });
});
