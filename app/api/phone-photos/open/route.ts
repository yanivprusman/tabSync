import { spawn } from "node:child_process";
import { access } from "node:fs/promises";
import path from "node:path";
import { authorize } from "@/lib/auth";
import { PHOTO_DIR, safeName } from "@/lib/phonePhotos.mjs";


/** Launch detached; report a launcher that fails within 2 s, otherwise assume the window is coming. */
function launch(argv: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(argv[0], argv.slice(1), { detached: true, stdio: ["ignore", "ignore", "pipe"] });
    let err = "";
    child.stderr?.on("data", (d: Buffer) => (err += d));
    const ok = setTimeout(() => {
      child.unref();
      resolve();
    }, 2000);
    child.on("error", (e) => {
      clearTimeout(ok);
      reject(e);
    });
    child.on("exit", (code) => {
      clearTimeout(ok);
      if (code === 0) resolve();
      else reject(new Error(err.trim() || `${argv[1]} exited ${code}`));
    });
  });
}

// POST /api/phone-photos/open {name, folder?: true} — open a copied photo in the
// desktop's image viewer, or show it selected in Files. Launched through
// runInGuiSession.sh so the window belongs to the desktop session, not to this
// service's cgroup (CLAUDE.md, design constraint 7).
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  let name: string;
  let folder = false;
  try {
    const body = await request.json();
    name = safeName(body.name);
    folder = body.folder === true;
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 400 });
  }
  const file = path.join(PHOTO_DIR, name);
  try {
    await access(file);
  } catch {
    return Response.json({ error: `${name} isn't in ${PHOTO_DIR} any more` }, { status: 404 });
  }
  const launcher = path.join(process.env.AUTOMATE_LINUX_DIR ?? "/opt/automateLinux", "utilities", "runInGuiSession.sh");
  try {
    await launch([launcher, ...(folder ? ["nautilus", "--select", file] : ["xdg-open", file])]);
    return Response.json({ ok: true });
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 500 });
  }
}
