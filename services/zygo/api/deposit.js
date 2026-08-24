import { Connection, PublicKey } from "@solana/web3.js";
import { handler, readJson, requireFields, send } from "../lib/http.js";
import { ConfigError, unwrap, zygo } from "../lib/zygo.js";

/**
 * POST /api/deposit  { orderId, payerPublicKey }  ->  unsigned transaction, base64
 *
 * The signing boundary, and the reason this service can be trusted with a merchant key but not with
 * anyone's money: it builds the deposit transaction and never signs it. The user's wallet on the
 * phone signs, so the private key never leaves the device and this service cannot spend a rupee of
 * it even if it is fully compromised.
 *
 * The SDK's `deposit.prepare` builds; only `deposit.execute` signs - which is why `execute` is not
 * called here at all. `prepare` still wants a signer-shaped object because it needs the payer's
 * public key to derive accounts, so it gets one whose `signTransaction` throws. If a future SDK
 * version signs during prepare, that throw turns a silent policy violation into a 500 with this
 * message in the log, which is the failure we want.
 *
 * Verified from the 0.1.4 source: `prepare` delegates to `buildDeposit`, which reads
 * `signer.publicKey` and hands `signer.signTransaction` to an AnchorProvider it only builds with.
 * Not verified by running it - that needs a live key and a funded wallet.
 */
export default handler("POST", async (req, res) => {
  const body = await readJson(req);
  const required = requireFields(body, ["orderId", "payerPublicKey"]);
  if (!required.ok) return send(res, required.status, required.body);

  const rpcUrl = process.env.SOLANA_RPC_URL;
  if (!rpcUrl) {
    throw new ConfigError("SOLANA_RPC_URL is not set; the deposit transaction cannot be built.");
  }

  let payer;
  try {
    payer = new PublicKey(body.payerPublicKey);
  } catch {
    return send(res, 400, {
      error: "BAD_PUBLIC_KEY",
      message: "payerPublicKey is not a valid Solana address.",
    });
  }

  const client = zygo();

  // Moves the order to escrow-pending, so it is not a read-only call and should not be retried
  // casually by the app.
  const instructions = await unwrap(client.payments.depositInstructions(body.orderId));
  if (!instructions.ok) return send(res, instructions.status, instructions.body);
  const instr = instructions.value;

  const connection = new Connection(rpcUrl, "confirmed");
  const prepared = await unwrap(
    client.payments.deposit.prepare(instr, {
      connection,
      signer: {
        publicKey: payer,
        signTransaction: () => {
          throw new Error(
            "prepare() attempted to sign. This service holds no keys by design; signing belongs " +
              "on the user's device. Refusing.",
          );
        },
      },
    }),
  );
  if (!prepared.ok) return send(res, prepared.status, prepared.body);

  const transaction = prepared.value.transaction;
  const unsigned = transaction.serialize({
    requireAllSignatures: false,
    verifySignatures: false,
  });

  return send(res, 200, {
    // What the app signs.
    transactionBase64: Buffer.from(unsigned).toString("base64"),
    recentBlockhash: transaction.recentBlockhash ?? null,
    lastValidBlockHeight: transaction.lastValidBlockHeight ?? null,
    // What the app can state before it does, so the amount leaving the wallet is never a surprise.
    deposit: {
      escrowId: instr.escrow_id,
      chain: instr.chain,
      chainName: instr.chain_name,
      mint: instr.mint,
      vaultAddress: instr.vault_address,
      amountBase: instr.amount_base,
      memo: instr.memo,
      path: instr.path,
      programId: instr.program_id ?? null,
      escrowPda: instr.escrow_pda ?? null,
    },
  });
});
