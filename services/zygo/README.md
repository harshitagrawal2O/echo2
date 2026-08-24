# Zygo payment service

Server side of the UPI-over-Solana payment flow. Exists for one reason: the `@zygopay/sdk` README
is explicit that *"secret API keys must never ship to a browser or mobile app"*, and an APK is a zip
file. The merchant key lives here and only here.

## What this does and does not hold

| | where it lives | why |
|---|---|---|
| Zygo merchant key (`client_id:secret`) | this service, env only | carries `quotes:write` / `orders:write`; extractable from an APK in seconds |
| Solana private key | **nowhere** — the user's wallet | the SDK never holds keys, and neither does this; see `api/deposit.js` |
| Caller bearer token | this service *and* the APK | an abuse gate, not a secret — see `lib/http.js` |

The consequence worth stating plainly: **a full compromise of this service cannot move anyone's
money.** It can spam orders and quotes against the merchant key, which is why the bearer token
exists, but every payment is signed on the user's device by a wallet this service cannot reach. If
a signing key is ever added here, that property is gone and the bearer token stops being sufficient.

## Endpoints

All `POST`, all requiring `Authorization: Bearer $ZYGO_PROXY_TOKEN`, all returning JSON.

| endpoint | body | returns |
|---|---|---|
| `/api/resolve` | `{ rawQr }` | merchant destination, name, payee id |
| `/api/quote` | `{ merchantPaymentDestinationId, fiatAmountMinor }` | USDC quote with the full fee breakdown |
| `/api/order` | `{ quoteId, idempotencyKey? }` | order |
| `/api/deposit` | `{ orderId, payerPublicKey }` | **unsigned** transaction, base64 |
| `/api/status` | `{ orderId, withTimeline? }` | order state |

Two of these are not reads, and the client must treat them accordingly: `/api/order` creates an
order, and `/api/deposit` advances it to escrow-pending. The Android client marks the deposit step
non-retryable for exactly that reason.

`fiatAmountMinor` is paise throughout — `50000` is ₹500.00. No amount crosses this boundary as a
floating point number.

## Setup

```sh
cd services/zygo
npm install
cp .env.example .env      # then fill it in
npx vercel dev            # or deploy: npx vercel --prod
```

Get the key from the Zygo app: Profile → Developer mode → Create API key. It is shown **once**.

### Environment

See `.env.example`. Two defaults are deliberate:

- **`ZYGO_ENVIRONMENT` absent means sandbox.** A payments service that goes live because someone
  forgot a variable is the wrong failure direction.
- **`ZYGO_MAX_FIAT_MINOR` defaults to ₹2000.00.** Raise it on purpose, not by accident.

## Point the app at it

Gradle properties, so nothing is committed — the same pattern the project already uses for
`OPENAI_API_KEY`:

```sh
./gradlew :app:assembleDebug \
  -PZYGO_SERVICE_URL=https://your-deployment.vercel.app \
  -PZYGO_PROXY_TOKEN=the-same-token-as-the-service
```

Changing either one currently means a rebuild — the plugin has no settings screen yet. The
preference keys are read, so adding one is small, but as shipped this is build-time configuration.

## Verified, and not

Read from the 0.1.4 sources rather than assumed:

- `merchants.resolveQr` → `quotes.create` → `orders.create` → `payments.depositInstructions` →
  wallet signs → `orders.get` is the real call sequence.
- Every method returns `ResultAsync<T, ZygoError>` (neverthrow) and throws nothing, which is why
  `lib/zygo.js` has an `unwrap` and no `try`/`catch` around SDK calls.
- `payments.deposit.prepare` builds and does not sign: it delegates to `buildDeposit`, which reads
  `signer.publicKey` and hands `signer.signTransaction` to an AnchorProvider it only builds with.
  Signing happens in `execute`, which this service never calls.

**Not verified:** none of this has been run. That needs a real merchant key, a funded USDC wallet
and, for the app half, a CY-01 — so the honest status is "written against the published types and
sources, never executed". The specific thing most likely to be wrong is the `prepare` assumption
above; the stub signer throws rather than returning something plausible, so if it is wrong the
result is a 500 with a clear message in the log rather than a silently mis-built transaction.
