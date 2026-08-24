import { createZygo } from "@zygopay/sdk";

/**
 * The one place the Zygo merchant key is read.
 *
 * The SDK's own README is unambiguous: "Server-side only: secret API keys must never ship to a
 * browser or mobile app." The key is a `client_id:secret` pair carrying the `quotes:write` and
 * `orders:write` scopes, and an APK is a zip file. So this service exists purely so that the key
 * lives somewhere the user's device cannot read.
 */
let cached = null;

export function zygo() {
  if (cached) return cached;

  const apiKey = process.env.ZYGO_API_KEY;
  if (!apiKey) {
    throw new ConfigError(
      "ZYGO_API_KEY is not set. Create one in the Zygo app (Profile -> Developer mode); it is " +
        "shown once. Set it in the deployment's environment, never in the repo.",
    );
  }

  // Sandbox by default. A payment service that defaults to live money because someone forgot to
  // set a variable is the wrong default, and the failure is silent until it isn't.
  const environment = process.env.ZYGO_ENVIRONMENT === "live" ? "live" : "sandbox";

  cached = createZygo({
    apiKey,
    environment,
    ...(process.env.ZYGO_BASE_URL ? { baseUrl: process.env.ZYGO_BASE_URL } : {}),
    timeoutMs: Number(process.env.ZYGO_TIMEOUT_MS ?? 15_000),
  });
  return cached;
}

export function zygoEnvironment() {
  return process.env.ZYGO_ENVIRONMENT === "live" ? "live" : "sandbox";
}

export class ConfigError extends Error {}

/**
 * Every SDK call returns `ResultAsync<T, ZygoError>` (neverthrow) rather than throwing, so an
 * unchecked `.value` is the easy mistake. This unwraps one Result and turns the error side into
 * something the handler can return verbatim.
 */
export async function unwrap(resultAsync) {
  const result = await resultAsync;
  if (result.isErr()) {
    const error = result.error;
    return {
      ok: false,
      status: error.httpStatus && error.httpStatus >= 400 ? error.httpStatus : 502,
      body: {
        error: error.code ?? "ZYGO_ERROR",
        message: error.message,
        retryable: Boolean(error.retryable),
      },
    };
  }
  return { ok: true, value: result.value };
}

/**
 * Picks the USDC-on-Solana asset from the enabled list.
 *
 * Chosen by symbol and chain namespace rather than a hardcoded `asset_id`, because asset ids are
 * per-environment: a sandbox id pasted into a live deployment would resolve to nothing, or worse,
 * to something else.
 */
export function selectUsdcSolana(assets) {
  const match = assets.find(
    (asset) => asset.symbol === "USDC" && asset.chain_namespace === "solana",
  );
  if (!match) {
    const seen = assets.map((a) => `${a.symbol}/${a.chain_namespace}`).join(", ") || "none";
    throw new ConfigError(`No USDC-on-Solana asset is enabled for this key. Assets seen: ${seen}.`);
  }
  return match;
}
