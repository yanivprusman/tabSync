import { authorize } from "@/lib/auth";
import { phoneThumbnail } from "@/lib/phonePhotos.mjs";

// GET /api/phone-photos/thumb?id=<MediaStore id>&v=<date_added> — the phone's own
// thumbnail. `v` changes when the photo does, so the URL can be cached for good.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return new Response(denied, { status: 401 });
  const params = new URL(request.url).searchParams;
  try {
    const bytes = await phoneThumbnail(params.get("id"), params.get("v"));
    return new Response(new Uint8Array(bytes), {
      headers: { "Content-Type": "image/jpeg", "Cache-Control": "private, max-age=31536000, immutable" },
    });
  } catch (e) {
    return new Response((e as Error).message, { status: 502 });
  }
}
