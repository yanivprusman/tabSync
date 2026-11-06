import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { authorize } from "@/lib/auth";

const run = promisify(execFile);

// The computer's sound: read where it goes, or move it. Its own route, not part of
// /api/tabs: that one fails when Chrome is closed, and the sound has nothing to do with Chrome.
//
// phoneAudio.sh owns what "on" means and how to get there; never re-derive it here.
async function inSession(file: string, args: string[]): Promise<string> {
  // This server is a system service, and the sound lives in the user's session. pactl, wpctl
  // and `systemctl --user` need that session's address, and phoneAudio.sh's lock (shared with
  // the keeper) lives in the same runtime dir.
  const runtime = `/run/user/${process.getuid!()}`;
  try {
    const { stdout } = await run(file, args, {
      timeout: 15000,
      env: { ...process.env, XDG_RUNTIME_DIR: runtime, DBUS_SESSION_BUS_ADDRESS: `unix:path=${runtime}/bus` },
    });
    return stdout.trim();
  } catch (e) {
    // The tool's own stderr is written for a person; execFile's message is not.
    const err = e as Error & { stderr?: string };
    throw new Error(err.stderr?.trim() || err.message);
  }
}

function phoneAudio(command: "status" | "on" | "off"): Promise<string> {
  const root = process.env.AUTOMATE_LINUX_DIR;
  if (!root) throw new Error("server not configured: AUTOMATE_LINUX_DIR is not set");
  return inSession(`${root}/desktop/phoneAudio.sh`, [command]);
}

// The volume is always the default output's, the one GNOME's own slider moves: that is the
// output in use, on the computer or on the way to the phone.
const OUTPUT = "@DEFAULT_AUDIO_SINK@";

async function readVolume() {
  const answer = await inSession("wpctl", ["get-volume", OUTPUT]);
  const match = answer.match(/^Volume: (\d+\.\d+)( \[MUTED\])?$/);
  if (!match) throw new Error(`wpctl get-volume answered "${answer}"`);
  return { percent: Math.round(Number(match[1]) * 100), muted: match[2] !== undefined };
}

async function soundStatus() {
  const [status, volume] = await Promise.all([phoneAudio("status"), readVolume()]);
  if (status !== "on" && status !== "off") throw new Error(`phoneAudio.sh status answered "${status}"`);
  return { onPhone: status === "on", ...volume };
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

// POST /api/sound, one of:
//   {"on": true|false}    true sends it to the phone, false brings it back
//   {"percent": 0..100}   sets the volume, and unmutes unless it is 0, as GNOME's slider does.
//                         No higher than 100: above it is plain digital gain, which clips.
//   {"muted": true|false}
export async function POST(request: Request) {
  const denied = authorize(request);
  if (denied) return Response.json({ error: denied }, { status: 401 });
  const body = await request.json().catch(() => null);
  const { on, percent, muted } = body ?? {};
  const validPercent = Number.isInteger(percent) && percent >= 0 && percent <= 100;
  if (typeof on !== "boolean" && !validPercent && typeof muted !== "boolean") {
    return Response.json({ error: 'body must be {"on": true|false}, {"percent": 0..100} or {"muted": true|false}' }, { status: 400 });
  }
  try {
    if (typeof on === "boolean") {
      await phoneAudio(on ? "on" : "off");
    } else if (validPercent) {
      await inSession("wpctl", ["set-volume", OUTPUT, `${percent}%`]);
      if (percent > 0) await inSession("wpctl", ["set-mute", OUTPUT, "0"]);
    } else {
      await inSession("wpctl", ["set-mute", OUTPUT, muted ? "1" : "0"]);
    }
    return Response.json(await soundStatus());
  } catch (e) {
    return Response.json({ error: (e as Error).message }, { status: 502 });
  }
}
