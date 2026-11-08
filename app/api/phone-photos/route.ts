import { authorize } from "@/lib/auth";
import { listPhonePhotos } from "@/lib/phonePhotos.mjs";

// GET /api/phone-photos?limit=30 — the phone's newest DCIM photos, each marked
// with the desktop file it was already copied to.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  const limit = Math.min(Math.max(Number(new URL(request.url).searchParams.get("limit")) || 30, 1), 300);
  try {
    return Response.json(await listPhonePhotos({ limit }), { headers: { "Cache-Control": "no-store" } });
  } catch (e) {
    const err = e as Error & { reason?: string };
    return Response.json({ error: err.message, reason: err.reason ?? "error" }, { status: 502 });
  }
}
