import { createHash } from "node:crypto";
import { authorize } from "@/lib/auth";
import { desktopTabsForPhone } from "@/lib/tabs.mjs";

// GET /api/tabs — the desktop Chrome's open tabs, minus solitaire / dashboard /
// WhatsApp, with localhost URLs rewritten to the desktop's WireGuard address.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  try {
    // Polled every few seconds, and every favicon is in the body: answer "unchanged" when it is.
    const body = JSON.stringify(await desktopTabsForPhone());
    const etag = `"${createHash("sha1").update(body).digest("base64url")}"`;
    if (request.headers.get("if-none-match") === etag) return new Response(null, { status: 304, headers: { ETag: etag } });
    return new Response(body, { headers: { "Content-Type": "application/json", ETag: etag } });
  } catch (e) {
    // `reason` (ChromeUnavailable) tells the phone the desktop answered and WHAT
    // is wrong on it — so it never shows a network error for a closed Chrome.
    const err = e as Error & { reason?: string };
    return Response.json({ error: err.message, reason: err.reason ?? "desktop-error" }, { status: 502 });
  }
}
