import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { authorize } from "@/lib/auth";

const run = promisify(execFile);

// GET /api/sound — is the computer's sound being sent to the phone?
// Its own route, not a field of /api/tabs: that one fails when Chrome is closed,
// and the sound has nothing to do with Chrome.
//
// phoneAudio.sh owns what "on" means (its state file); never re-derive it here.
export async function GET(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  const root = process.env.AUTOMATE_LINUX_DIR;
  if (!root) return Response.json({ error: "server not configured: AUTOMATE_LINUX_DIR is not set" }, { status: 500 });
  try {
    const { stdout } = await run(`${root}/desktop/phoneAudio.sh`, ["status"], { timeout: 5000 });
    const status = stdout.trim();
    if (status !== "on" && status !== "off") throw new Error(`phoneAudio.sh status answered "${status}"`);
    return Response.json({ onPhone: status === "on" });
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
