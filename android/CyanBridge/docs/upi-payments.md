# UPI payments (Zygo) — how the flow works and what is unproven

Pay an Indian merchant by photographing its UPI QR with the glasses. The rupee amount is settled by
Zygo out of USDC held in the user's own Solana wallet.

Two halves: `services/zygo/` (Node, holds the merchant key) and `plugins/zygopay/` (Android).

## Why it is split this way

The `@zygopay/sdk` README requires the merchant key stay server-side, so the SDK cannot go in the
app at all. That constraint turns out to be the useful one — it forces the only thing that can spend
money to be the user's own wallet:

```
glasses still ──► ML Kit QR decode ──► service: resolveQr ──► service: quote
                        (on device)                                  │
                                                                     ▼
   settled ◄── service: status ◄── wallet signs+sends ◄── SPOKEN CONFIRMATION ──► service: order
                                    (user's wallet app)     (flow stops here)      service: deposit
```

Everything left of the confirmation is reversible. Everything right of it needs the user's wallet to
agree as well, in its own UI, in a different app.

## The safety properties, and where they are enforced

| property | enforced by |
|---|---|
| No key that can spend reaches this app or the service | `api/deposit.js` builds and never signs; the stub signer throws |
| A payment cannot start in the background | `ZygoPayFlow.confirm` needs an `ActivityResultSender`, only obtainable in an Activity |
| Nothing is paid without the amount being said out loud | `ZygoPayFlow.State.AwaitingConfirmation` has no self-transition |
| Live money needs two deliberate presses | `ZygoPayActivity`, `needsArming` |
| A retried request cannot become two orders | idempotency key minted at confirmation, reused |
| A non-payment QR cannot start a flow | `UpiQr.parse` refuses anything that is not `upi://pay` with a payee |
| Two QRs in frame are refused, not guessed | `ZygoQrDecoder.Outcome.Ambiguous` |
| Sandbox unless deliberately set live | `lib/zygo.js`, and the wallet follows it to devnet |

The last row matters more than it looks: if the service were live while the wallet was on devnet
(or the reverse), the user would be prompted to spend the wrong money entirely. `ZygoWallet` takes
its cluster from the environment the service reported, not from its own config.

## Deliberately not built

**No hardware-button entry point.** The glasses AI button does not start a payment. It could —
`ZygoPayPlugin.startPaymentFromCapture` exists and takes a still — but nothing calls it. Two
reasons, and the second is the real one: `MainActivity` is under active edit by someone else, and a
physical button that begins a payment is a physical button that can be pressed in a pocket. The
plugin screen asks the glasses for the photo instead, so the flow is still hands-free after one
deliberate launch.

**No spoken aiming guidance.** Same conclusion as the phone-camera work: the glasses camera is
head-aimed with a wide field of view, so there is nothing useful to say about framing that a user
can act on.

## Unproven, in the order it should be tested

1. **Can a UPI QR be decoded from a BLE thumbnail at all?** This is the one that can kill the
   feature. The glasses return a thumbnail, not a photo — `ImageThumbnailQuality.DETAILED` is the
   highest the vendor selector exposes — and a UPI QR is a dense grid. If `DETAILED` cannot carry
   enough pixels, the fix is the Wi-Fi full-resolution path, **not** a more permissive decoder.
   Test with a printed merchant QR at 30cm, 50cm and 1m.
2. **Does `prepare` really not sign?** Read from the 0.1.4 source, never executed. The stub signer
   throws, so a wrong answer here is a 500 with a clear log line rather than a bad transaction.
3. **Does a wallet app honour `signAndSendTransactions` for this transaction shape?** Escrow-program
   path and vault-transfer fallback may behave differently.
4. **Does the spoken confirmation arrive before the wallet screen steals focus?** The wallet is
   another app; if it foregrounds mid-sentence the user may approve a payment they never heard
   described. If so, the confirmation has to complete before `confirm` is callable.

Nothing above has been run. The Kotlin compiles and the pure logic is unit-tested; no part of this
has touched a merchant key, a funded wallet, or a CY-01.

## Configuration

```sh
./gradlew :app:assembleDebug \
  -PZYGO_SERVICE_URL=https://your-deployment.vercel.app \
  -PZYGO_PROXY_TOKEN=matching-token
```

Build-time only: there is no settings screen, so changing the endpoint means a rebuild. The plugin
card reports itself unavailable until both are set, which is deliberate — but it means a build
without them cannot be fixed from inside the app. A settings screen is the obvious next piece.

Neither value is the merchant secret — see `services/zygo/README.md` for what the bearer token is
and is not worth.
