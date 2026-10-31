import { authorize } from "@/lib/auth";
// @ts-expect-error -- plain ESM shared with the phoneTabsToDesktop CLI
import { desktopTabsForPhone } from "@/lib/tabs.mjs";

// GET /api/tabs — the desktop Chrome's open tabs, minus solitaire / dashboard /
// WhatsApp, with localhost URLs rewritten to the desktop's WireGuard address.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  try {
    return Response.json(await desktopTabsForPhone());
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
