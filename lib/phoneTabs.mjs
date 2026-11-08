// Phone → desktop: the phone's Chrome tabs, and opening them on the desktop.
//
// Plain ESM, like lib/tabs.mjs, because two callers share it: the desktop page
// (app/api/phone-tabs) and the `phoneTabsToDesktop` terminal command
// (scripts/phone-to-desktop.mjs). Both apply the same filter, in tabs.mjs.
//
// How the phone's tabs are read: Chrome on Android serves the DevTools HTTP
// endpoint on the abstract socket `chrome_devtools_remote` whenever adb is
// enabled — but only after Chrome has been in the foreground once since it
// started. adb forwards a local port to it; `/json/list` is a read-only listing.
//
// How the desktop opens them: `PUT /json/new?<url>` on the everyday Chrome
// (9222). That creates a tab and nothing else — it never sends
// setDownloadBehavior, which would break Chrome's download buttons.

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { setTimeout as sleep } from "node:timers/promises";
import { DESKTOP_CDP, decodeEntities, forPhone, listPageTabs, partition } from "./tabs.mjs";

const run = promisify(execFile);

/** The main phone (SM-A536E), reached over WireGuard. */
export const PHONE_SERIAL = "10.7.0.3:5555";
const SOCKET = "chrome_devtools_remote";

/**
 * Why the phone's tabs can't be read, with a `reason` the page acts on:
 *   phone-not-attached  — adb can't reach the phone (off, asleep on WiFi, debugging off)
 *   phone-chrome-asleep — the phone is there, but Chrome hasn't been on screen since it started
 *   phone-chrome-wont-wake — we brought Chrome to the front and it still opened no socket
 */
export class PhoneUnavailable extends Error {
  constructor(reason, message) {
    super(message);
    this.reason = reason;
  }
}

async function adbRaw(args) {
  try {
    const { stdout } = await run("adb", args, { encoding: "utf8", timeout: 15000 });
    return stdout.trim();
  } catch (e) {
    throw new Error(`adb ${args.join(" ")}: ${(e.stderr || "").trim() || e.message}`);
  }
}

const adb = (serial, ...args) => adbRaw(["-s", serial, ...args]);

async function attached(serial) {
  const devices = await adbRaw(["devices"]);
  return new RegExp(`^${serial.replace(/[.]/g, "\\.")}\\s+device$`, "m").test(devices);
}

/** Attach the phone. `adb devices` not listing it only means the adb server was never told. */
async function attach(serial) {
  if (await attached(serial)) return;
  if (serial.includes(":")) {
    try {
      await adbRaw(["connect", serial]);
    } catch {
      // judged by the check below, which says what is wrong in the user's terms
    }
  }
  if (!(await attached(serial)))
    throw new PhoneUnavailable(
      "phone-not-attached",
      `Can't reach the phone over adb (${serial}). Is it on, with wireless debugging enabled?`,
    );
}

async function hasSocket(serial) {
  return (await adb(serial, "shell", `grep -a -c '@${SOCKET}$' /proc/net/unix || true`)) !== "0";
}

/**
 * The phone's Chrome tabs, in Chrome's own order.
 * `wake`: bring Chrome to the front on the phone if its socket isn't up yet.
 * Never done without being asked — it changes what is on the phone's screen.
 */
export async function readPhoneTabs({ serial = PHONE_SERIAL, wake = false } = {}) {
  await attach(serial);
  if (!(await hasSocket(serial))) {
    if (!wake)
      throw new PhoneUnavailable(
        "phone-chrome-asleep",
        "Chrome on the phone hasn't been opened since it last started, so its tabs can't be read yet.",
      );
    await adb(serial, "shell", "am", "start", "-n", "com.android.chrome/com.google.android.apps.chrome.Main");
    for (let i = 0; i < 20 && !(await hasSocket(serial)); i++) await sleep(500);
    if (!(await hasSocket(serial)))
      throw new PhoneUnavailable(
        "phone-chrome-wont-wake",
        "Chrome on the phone still isn't answering. Is the phone unlocked?",
      );
  }
  const port = await adb(serial, "forward", "tcp:0", `localabstract:${SOCKET}`);
  try {
    return await listPageTabs(`http://127.0.0.1:${port}`);
  } finally {
    await adb(serial, "forward", "--remove", `tcp:${port}`).catch(() => {});
  }
}

function hostOf(url) {
  try {
    const u = new URL(url);
    return u.port ? `${u.hostname}:${u.port}` : u.hostname;
  } catch {
    return url;
  }
}

/**
 * The phone's tabs as the desktop sees them: which move (and whether each is
 * already open on the desktop — then `desktopId` is that desktop tab), and
 * which stay on the phone and why.
 */
export async function phoneTabsForDesktop(opts = {}) {
  const phoneTabs = await readPhoneTabs(opts);
  const { moving, skipped } = await partition(phoneTabs);
  const desktopByUrl = new Map();
  for (const t of await listPageTabs(DESKTOP_CDP)) {
    const key = forPhone(t.url);
    if (!desktopByUrl.has(key)) desktopByUrl.set(key, t.id);
  }
  return {
    tabs: moving.map((t) => ({
      title: decodeEntities(t.title),
      url: t.url,
      host: hostOf(t.url),
      icon: /^https?:/.test(t.faviconUrl || "") ? t.faviconUrl : null,
      desktopId: desktopByUrl.get(forPhone(t.url)) ?? null,
    })),
    skipped: skipped.map((t) => ({ title: decodeEntities(t.title), reason: t.reason })),
  };
}

/** Open one URL as a new tab in the desktop's Chrome; resolves with the new tab's id. */
export async function openOnDesktop(url) {
  const u = new URL(url); // throws on garbage
  if (u.protocol !== "http:" && u.protocol !== "https:") throw new Error(`not a web page: ${url}`);
  const res = await fetch(`${DESKTOP_CDP}/json/new?${encodeURIComponent(u.toString())}`, {
    method: "PUT",
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`desktop Chrome refused to open ${url}: HTTP ${res.status}`);
  return (await res.json()).id;
}

/** Bring an existing desktop tab to the front. */
export async function showOnDesktop(id) {
  if (!/^[A-Za-z0-9]+$/.test(id)) throw new Error(`bad tab id: ${id}`);
  const res = await fetch(`${DESKTOP_CDP}/json/activate/${id}`, { signal: AbortSignal.timeout(5000) });
  if (!res.ok) throw new Error(`desktop Chrome has no tab ${id} any more (HTTP ${res.status})`);
}
