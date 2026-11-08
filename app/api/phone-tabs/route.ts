import { authorize } from "@/lib/auth";
import { phoneTabsForDesktop } from "@/lib/phoneTabs.mjs";

// GET /api/phone-tabs[?wake=1] — the phone's Chrome tabs for the desktop page,
// minus solitaire / dashboard / WhatsApp, each marked with the desktop tab it is
// already open in. `wake=1` brings Chrome to the front on the phone when its
// DevTools socket isn't up; without it that is reported, never done.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  const wake = new URL(request.url).searchParams.get("wake") === "1";
  try {
    return Response.json(await phoneTabsForDesktop({ wake }), { headers: { "Cache-Control": "no-store" } });
  } catch (e) {
    const err = e as Error & { reason?: string };
    return Response.json({ error: err.message, reason: err.reason ?? "error" }, { status: 502 });
  }
}
