import { authorize } from "@/lib/auth";
import { copyPhonePhoto } from "@/lib/phonePhotos.mjs";

// POST /api/phone-photos/copy {id} — copy one photo, full size, into the desktop's
// ~/Pictures/Screenshots. adb to the phone is slow (~0.35 MB/s), so the reply is
// a stream of JSON lines: {"got":n,"size":n} as it arrives, then {"done":true,"name":…}
// or {"error":…}.
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  let id: unknown;
  try {
    id = (await request.json()).id;
  } catch {
    return Response.json({ error: "invalid json" }, { status: 400 });
  }
  const enc = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      const line = (o: unknown) => controller.enqueue(enc.encode(JSON.stringify(o) + "\n"));
      try {
        const saved = await copyPhonePhoto(String(id), (got: number, size: number) => line({ got, size }));
        line({ done: true, ...saved });
      } catch (e) {
        line({ error: (e as Error).message });
      }
      controller.close();
    },
  });
  return new Response(stream, { headers: { "Content-Type": "application/x-ndjson", "Cache-Control": "no-store" } });
}
