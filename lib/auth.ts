import { timingSafeEqual } from "node:crypto";

// The tab list is the user's browsing — it must not be readable by anything
// on the home LAN / WireGuard that happens to find this port. One bearer token,
// TABSYNC_API_TOKEN in .env.local, baked into the APK from mobile/.env.
export function authorize(request: Request): string | null {
  const secret = process.env.TABSYNC_API_TOKEN;
  if (!secret) return "server not configured: TABSYNC_API_TOKEN missing from .env.local";
  const header = request.headers.get("authorization") ?? "";
  if (!header.startsWith("Bearer ")) return "missing bearer token";
  const a = Buffer.from(header.slice(7));
  const b = Buffer.from(secret);
  return a.length === b.length && timingSafeEqual(a, b) ? null : "bad token";
}
