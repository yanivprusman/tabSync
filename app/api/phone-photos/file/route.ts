import { readFile } from "node:fs/promises";
import path from "node:path";
import { authorize } from "@/lib/auth";
import { PHOTO_DIR, safeName } from "@/lib/phonePhotos.mjs";

const TYPES: Record<string, string> = {
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".heic": "image/heic",
};

// GET /api/phone-photos/file?name= — a photo already copied to the desktop, for
// the page to put on the clipboard or let you drag out. Only names in the photo folder.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return new Response(denied, { status: 401 });
  let name: string;
  try {
    name = safeName(new URL(request.url).searchParams.get("name"));
  } catch (e) {
    return new Response((e as Error).message, { status: 400 });
  }
  try {
    const bytes = await readFile(path.join(PHOTO_DIR, name));
    return new Response(new Uint8Array(bytes), {
      headers: {
        "Content-Type": TYPES[path.extname(name).toLowerCase()] ?? "application/octet-stream",
        "Cache-Control": "no-store",
      },
    });
  } catch {
    return new Response(`${name} isn't in ${PHOTO_DIR}`, { status: 404 });
  }
}
