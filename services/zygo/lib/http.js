import { ConfigError } from "./zygo.js";

/**
 * Request plumbing shared by the handlers: method guard, JSON body, caller auth, error shape.
 */

export function send(res, status, body) {
  res.statusCode = status;
  res.setHeader("content-type", "application/json; charset=utf-8");
  res.setHeader("cache-control", "no-store");
  res.end(JSON.stringify(body));
}

/**
 * Authenticates the *caller* (the app), which is a different question from authenticating this
 * service to Zygo.
 *
 * Be clear about what this is worth: the token ships inside the APK, so a determined user can
 * extract it. It is an abuse gate, not a secret. What makes that acceptable is that this service
 * cannot move anyone's money - every payment is signed on the user's device by the user's own
 * wallet - so the worst an extracted token buys is order and quote spam against the merchant key.
 * If this service ever gains a signing key, this check stops being sufficient and must be replaced
 * with per-user authentication.
 */
export function authorize(req) {
  const expected = process.env.ZYGO_PROXY_TOKEN;
  if (!expected) {
    throw new ConfigError(
      "ZYGO_PROXY_TOKEN is not set. Refusing to serve an unauthenticated payments endpoint.",
    );
  }
  const header = req.headers?.authorization ?? "";
  const presented = header.startsWith("Bearer ") ? header.slice(7) : "";
  return timingSafeEqual(presented, expected);
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function readJson(req) {
  if (req.body && typeof req.body === "object") return req.body;
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  if (chunks.length === 0) return {};
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw.trim()) return {};
  return JSON.parse(raw);
}

/**
 * Wraps a handler with the checks every endpoint needs, so no endpoint can be added without them.
 * A payments endpoint that forgot its auth check would look exactly like one that has it.
 */
export function handler(method, fn) {
  return async (req, res) => {
    if (req.method !== method) {
      res.setHeader("allow", method);
      return send(res, 405, { error: "METHOD_NOT_ALLOWED", message: `Use ${method}.` });
    }
    try {
      if (!authorize(req)) {
        return send(res, 401, { error: "UNAUTHORIZED", message: "Bad or missing bearer token." });
      }
      return await fn(req, res);
    } catch (error) {
      if (error instanceof ConfigError) {
        // Deployment misconfiguration, not a caller error. Logged in full, summarised to the caller.
        console.error("[zygo] configuration error:", error.message);
        return send(res, 503, { error: "SERVICE_MISCONFIGURED", message: error.message });
      }
      if (error instanceof SyntaxError) {
        return send(res, 400, { error: "BAD_JSON", message: "Request body is not valid JSON." });
      }
      console.error("[zygo] unhandled error:", error);
      return send(res, 500, { error: "INTERNAL", message: "Unexpected server error." });
    }
  };
}

/** Returns the named string fields, or null plus a 400 body when any is missing. */
export function requireFields(body, fields) {
  const missing = fields.filter((f) => typeof body?.[f] !== "string" || body[f].trim() === "");
  if (missing.length > 0) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "MISSING_FIELDS",
        message: `Missing or empty: ${missing.join(", ")}.`,
      },
    };
  }
  return { ok: true };
}
