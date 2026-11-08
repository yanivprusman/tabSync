import { clearSessionCookieHeader, isAccessCode, notConfigured, sessionCookieHeader } from "@/lib/auth";

/** Sign a browser in. The access code IS TABSYNC_API_TOKEN — a second password would be a second key. */
export async function POST(request: Request) {
  const unset = notConfigured();
  if (unset) return Response.json({ ok: false, error: unset }, { status: 503 });
  let body: Record<string, unknown>;
  try {
    body = await request.json();
  } catch {
    return Response.json({ ok: false, error: "invalid json" }, { status: 400 });
  }
  if (!isAccessCode(String(body.code ?? "").trim()))
    return Response.json({ ok: false, error: "That isn't the access code." }, { status: 401 });
  return Response.json({ ok: true }, { headers: { "Set-Cookie": sessionCookieHeader(request) } });
}

/** Sign this browser out. Only ever removes access, so it needs no proof. */
export async function DELETE() {
  return Response.json({ ok: true }, { headers: { "Set-Cookie": clearSessionCookieHeader() } });
}
