import { authorize } from "@/lib/auth";
import { showOnDesktop } from "@/lib/phoneTabs.mjs";

// POST /api/phone-tabs/show {id} — bring a desktop Chrome tab to the front.
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  let id: unknown;
  try {
    id = (await request.json()).id;
  } catch {
    return Response.json({ error: "invalid json" }, { status: 400 });
  }
  if (typeof id !== "string") return Response.json({ error: "id must be a string" }, { status: 400 });
  try {
    await showOnDesktop(id);
    return Response.json({ ok: true });
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
