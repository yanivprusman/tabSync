import { authorize } from "@/lib/auth";
import { openOnDesktop } from "@/lib/phoneTabs.mjs";

// POST /api/phone-tabs/open {urls: string[]} — open each as a new tab in the
// desktop's Chrome, in order. Stops at the first failure and says which.
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  let urls: unknown;
  try {
    urls = (await request.json()).urls;
  } catch {
    return Response.json({ error: "invalid json" }, { status: 400 });
  }
  if (!Array.isArray(urls) || urls.length === 0 || urls.some((u) => typeof u !== "string"))
    return Response.json({ error: "urls must be a non-empty array of strings" }, { status: 400 });
  const opened: { url: string; id: string }[] = [];
  for (const url of urls as string[]) {
    try {
      opened.push({ url, id: await openOnDesktop(url) });
    } catch (e) {
      return Response.json({ error: (e as Error).message, opened }, { status: 502 });
    }
  }
  return Response.json({ opened });
}
