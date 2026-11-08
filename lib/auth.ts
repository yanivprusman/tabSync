import { createHmac, timingSafeEqual } from "node:crypto";

// The tab list is the user's browsing, and these routes open tabs, move the
// computer's sound and power-cycle a USB hub. The server listens on 0.0.0.0 —
// WireGuard AND the home LAN — so "only my devices reach it" is not enough.
//
// ONE secret, TABSYNC_API_TOKEN in .env.local, held two ways:
//  - the phone app sends it as a bearer token (baked into the APK from mobile/.env);
//  - a browser (the desktop page) signs in once and gets a session cookie that is
//    an HMAC of it — the secret never sits in a browser, and rotating it signs
//    every browser out with no session table to clear.
// Same scheme as the charge app.

export const SESSION_COOKIE = "tabsync_session";
const SESSION_MAX_AGE_S = 365 * 24 * 60 * 60;

function secret(): string {
  return process.env.TABSYNC_API_TOKEN ?? "";
}

function same(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function mac(message: string): string {
  return createHmac("sha256", secret()).update(message).digest("hex");
}

const sessionValue = () => mac("tabsync-web-session-v1");

export function notConfigured(): string | null {
  return secret() ? null : "server not configured: TABSYNC_API_TOKEN missing from .env.local";
}

export function isAccessCode(code: string): boolean {
  return secret() !== "" && same(code, secret());
}

export function isSession(value: string | undefined): boolean {
  return secret() !== "" && !!value && same(value, sessionValue());
}

/** Signature on a sign-in link from scripts/make-link.mjs: covers the expiry only. */
export function isLinkSignature(exp: number, sig: string): boolean {
  return secret() !== "" && same(sig, mac(`tabsync-web-link-v1:${exp}`));
}

/** `Secure` only over https: on plain http (localhost, the VPN) a Secure cookie is silently dropped. */
export function sessionCookieHeader(request: Request): string {
  const https = request.headers.get("x-forwarded-proto") === "https";
  return (
    `${SESSION_COOKIE}=${sessionValue()}; Path=/; Max-Age=${SESSION_MAX_AGE_S}; ` +
    `HttpOnly; SameSite=Strict${https ? "; Secure" : ""}`
  );
}

export function clearSessionCookieHeader(): string {
  return `${SESSION_COOKIE}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict`;
}

function cookieFrom(request: Request, name: string): string | undefined {
  for (const part of (request.headers.get("cookie") ?? "").split(";")) {
    const eq = part.indexOf("=");
    if (eq > 0 && part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return undefined;
}

/** A cookie-authenticated write must come from this app's own page, not just carry the cookie. */
function fromOwnOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  const host = request.headers.get("host");
  if (!origin || !host) return false;
  try {
    return new URL(origin).host === host;
  } catch {
    return false;
  }
}

/** Null when the request may proceed; otherwise why not. */
export function authorize(request: Request): string | null {
  const unset = notConfigured();
  if (unset) return unset;

  const header = request.headers.get("authorization") ?? "";
  if (header.startsWith("Bearer ")) return same(header.slice(7), secret()) ? null : "bad token";

  const session = cookieFrom(request, SESSION_COOKIE);
  if (!session) return "missing bearer token or session";
  if (!isSession(session)) return "bad session";
  if (request.method !== "GET" && !fromOwnOrigin(request)) return "cross-origin request refused";
  return null;
}
