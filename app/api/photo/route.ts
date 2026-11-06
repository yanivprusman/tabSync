import { writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { authorize } from "@/lib/auth";

// POST /api/photo?name=<file name> with the image as the body: saves it in this desktop's
// Screenshots folder. Never replaces a file: the same name again becomes "name-1.jpg".
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });

  // The name comes from the phone, and only its last segment may decide where a file lands.
  const raw = new URL(request.url).searchParams.get("name") ?? "";
  const name = path.basename(raw.replaceAll("\\", "/"));
  // eslint-disable-next-line no-control-regex
  if (!name || name.startsWith(".") || /[\x00-\x1f]/.test(name))
    return Response.json({ error: `bad file name: "${raw}"` }, { status: 400 });
  const type = request.headers.get("content-type") ?? "";
  if (!type.startsWith("image/")) return Response.json({ error: `not an image: "${type}"` }, { status: 400 });

  let bytes: Buffer;
  try {
    bytes = Buffer.from(await request.arrayBuffer());
  } catch (e) {
    return Response.json({ error: `upload did not finish: ${(e as Error).message}` }, { status: 400 });
  }
  if (bytes.length === 0) return Response.json({ error: "the photo arrived empty" }, { status: 400 });

  const dir = path.join(os.homedir(), "Pictures", "Screenshots");
  const { name: stem, ext } = path.parse(name);
  for (let i = 0; ; i++) {
    const saved = i === 0 ? name : `${stem}-${i}${ext}`;
    try {
      await writeFile(path.join(dir, saved), bytes, { flag: "wx" });
      return Response.json({ name: saved });
    } catch (e) {
      const err = e as NodeJS.ErrnoException;
      if (err.code !== "EEXIST") return Response.json({ error: err.message }, { status: 500 });
    }
  }
}
