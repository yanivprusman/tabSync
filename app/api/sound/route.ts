import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { authorize } from "@/lib/auth";

const run = promisify(execFile);

// The computer's sound: read where it goes, or move it. Its own route, not part of
// /api/tabs: that one fails when Chrome is closed, and the sound has nothing to do with Chrome.
//
// phoneAudio.sh owns what "on" means and how to get there; never re-derive it here.
async function phoneAudio(command: "status" | "on" | "off"): Promise<string> {
  const root = process.env.AUTOMATE_LINUX_DIR;
  if (!root) throw new Error("server not configured: AUTOMATE_LINUX_DIR is not set");
  // This server is a system service, and the sound lives in the user's session. The script's
  // pactl and `systemctl --user` need that session's address, and its lock (shared with the
  // keeper) lives in the same runtime dir.
  const runtime = `/run/user/${process.getuid!()}`;
  try {
    const { stdout } = await run(`${root}/desktop/phoneAudio.sh`, [command], {
      timeout: 15000,
      env: { ...process.env, XDG_RUNTIME_DIR: runtime, DBUS_SESSION_BUS_ADDRESS: `unix:path=${runtime}/bus` },
    });
    return stdout.trim();
  } catch (e) {
    // The script's own stderr is written for a person; execFile's message is not.
    const err = e as Error & { stderr?: string };
    throw new Error(err.stderr?.trim() || err.message);
  }
}

async function soundStatus() {
  const status = await phoneAudio("status");
  if (status !== "on" && status !== "off") throw new Error(`phoneAudio.sh status answered "${status}"`);
  return { onPhone: status === "on" };
}

export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  try {
    return Response.json(await soundStatus());
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}

// POST /api/sound {"on": true|false} — true sends it to the phone, false brings it back.
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  const body = await request.json().catch(() => null);
  if (typeof body?.on !== "boolean") return Response.json({ error: 'body must be {"on": true|false}' }, { status: 400 });
  try {
    await phoneAudio(body.on ? "on" : "off");
    return Response.json(await soundStatus());
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
