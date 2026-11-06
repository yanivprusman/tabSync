// The one copy of "which Chrome tabs move between desktop and phone".
//
// Plain ESM on purpose: the Next.js route (desktop → phone) and the
// `phoneTabsToDesktop` terminal command (phone → desktop) both import it, so a
// filter rule lives in exactly one place and applies in both directions.
//
// Tabs are read from a Chrome's DevTools HTTP endpoint, `/json/list`. That is a
// read-only listing — it does NOT attach to any target, and in particular never
// sends `Browser.setDownloadBehavior` (which permanently breaks the download
// bubble's Open / Show-in-folder buttons; see .claude/CLAUDE.md in automateLinux).

import net from "node:net";

/** The desktop's everyday Chrome (google-chrome-cdp-real). */
export const DESKTOP_CDP = "http://127.0.0.1:9222";

/** This desktop's WireGuard address — what the phone uses to reach it. */
export const DESKTOP_WG_IP = "10.7.0.2";

const DAEMON_SOCKET =
  process.env.AUTOMATE_LINUX_SOCKET_PATH ||
  "/run/automatelinux/automatelinux-daemon.sock";

/** One JSON command over the daemon's UNIX socket; resolves with its raw reply. */
export function daemonCommand(command, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    let out = "";
    let settled = false;
    const sock = net.createConnection(DAEMON_SOCKET);
    const done = (fn, v) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      sock.destroy();
      fn(v);
    };
    const timer = setTimeout(
      () => done(reject, new Error(`daemon timeout on ${command.command}`)),
      timeoutMs,
    );
    sock.on("connect", () => sock.write(JSON.stringify(command) + "\n"));
    sock.on("data", (d) => {
      out += d.toString();
      if (out.endsWith("\n")) done(resolve, out.trim());
    });
    sock.on("error", (e) => done(reject, e));
    sock.on("close", () => done(resolve, out.trim()));
  });
}

/** Dashboard ports come from the daemon's port registry — never hardcoded. */
export async function dashboardPorts() {
  const ports = new Set();
  for (const key of ["dashboard-dev", "dashboard-prod"]) {
    const reply = await daemonCommand({ command: "getPort", key });
    const port = Number(reply);
    if (!Number.isInteger(port) || port <= 0)
      throw new Error(`daemon getPort ${key} did not return a port: ${reply}`);
    ports.add(String(port));
  }
  return ports;
}

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]", "0.0.0.0"]);

function isOurMachine(host) {
  return (
    LOCAL_HOSTS.has(host) ||
    /^10\.7\.0\.\d+$/.test(host) || // WireGuard overlay
    /^10\.0\.0\.\d+$/.test(host) || // home-LAN hosts (net-legacy-ok)
    /^192\.168\.\d+\.\d+$/.test(host)
  );
}

/**
 * Why a tab stays behind, or null if it moves. Rules the user asked for:
 * solitaire, the dashboard, WhatsApp. Plus anything that is not a web page.
 */
export function excludeReason(tab, dashPorts) {
  let u;
  try {
    u = new URL(tab.url);
  } catch {
    return "unparseable url";
  }
  if (u.protocol !== "http:" && u.protocol !== "https:") return "not a web page";

  const host = u.hostname.toLowerCase();
  const title = (tab.title || "").toLowerCase();

  if (host === "web.whatsapp.com" || host.endsWith(".whatsapp.com") || host === "wa.me")
    return "whatsapp";
  // solitaro.com, solitaired.com, Google's solitaire doodle, …
  if (/solitair|solitaro/.test(host + u.pathname.toLowerCase()) || title.includes("solitaire"))
    return "solitaire";
  if (/^dashboard\.(dev|prod)\.ya-niv\.com$/.test(host)) return "dashboard";
  if (isOurMachine(host) && dashPorts.has(u.port)) return "dashboard";
  return null;
}

/**
 * A desktop URL as the phone must open it: `localhost:3165` means *the desktop*,
 * so it becomes the desktop's WireGuard address.
 */
export function forPhone(url) {
  const u = new URL(url);
  if (LOCAL_HOSTS.has(u.hostname)) u.hostname = DESKTOP_WG_IP;
  return u.toString();
}

/** The everyday Chrome's profile dir — how its processes are told apart from the court Chrome. */
const DESKTOP_PROFILE = "google-chrome-cdp-real";

/**
 * A DevTools endpoint failure with a `reason` the phone can act on, so it never
 * blames the network for a Chrome that is simply closed:
 *   chrome-closed       — no everyday-Chrome process at all
 *   chrome-no-debugging — it runs, but was started without --remote-debugging-port
 *   devtools-unreachable — anything else (timeout, port held by something else)
 */
export class ChromeUnavailable extends Error {
  constructor(reason, message) {
    super(message);
    this.reason = reason;
  }
}

/** Is a Chrome running with `profile` as its user-data-dir? Read from /proc. */
async function chromeProfileRunning(profile) {
  const { readdir, readFile } = await import("node:fs/promises");
  for (const pid of await readdir("/proc")) {
    if (!/^\d+$/.test(pid)) continue;
    let comm, cmdline;
    try {
      comm = (await readFile(`/proc/${pid}/comm`, "utf8")).trim();
      cmdline = await readFile(`/proc/${pid}/cmdline`, "utf8");
    } catch {
      continue; // exited while we looked
    }
    if (comm !== "chrome") continue;
    // Chrome rewrites its argv region: cmdline is space-joined, not NUL-separated.
    const args = cmdline.split(/[\0 ]+/);
    if (args.some((a) => a.startsWith("--type="))) continue; // renderer/gpu helpers
    if (args.some((a) => a.startsWith("--user-data-dir=") && a.endsWith(`/${profile}`))) return true;
  }
  return false;
}

/** All page tabs a Chrome DevTools endpoint reports, in its own order. */
export async function listPageTabs(cdpBase) {
  let res;
  try {
    res = await fetch(`${cdpBase}/json/list`, { signal: AbortSignal.timeout(5000) });
  } catch (e) {
    const refused = e.cause?.code === "ECONNREFUSED";
    if (refused && cdpBase === DESKTOP_CDP) {
      if (!(await chromeProfileRunning(DESKTOP_PROFILE)))
        throw new ChromeUnavailable("chrome-closed", "Chrome is not open on the desktop.");
      throw new ChromeUnavailable(
        "chrome-no-debugging",
        `Chrome is open on the desktop, but was started without remote debugging, so nothing answers on ${cdpBase}. Close it and reopen it from the dock icon.`,
      );
    }
    throw new ChromeUnavailable(
      "devtools-unreachable",
      `Chrome DevTools endpoint ${cdpBase} unreachable: ${e.cause?.code || e.cause?.message || e.message}`,
    );
  }
  if (!res.ok) throw new Error(`${cdpBase}/json/list answered HTTP ${res.status}`);
  const targets = await res.json();
  return targets
    .filter((t) => t.type === "page")
    .map((t) => ({ title: t.title || t.url, url: t.url, faviconUrl: t.faviconUrl || null }));
}

/** Split tabs into those that move and those that stay (with why); de-duplicated. */
export async function partition(tabs) {
  const dashPorts = await dashboardPorts();
  const moving = [];
  const skipped = [];
  const seen = new Set();
  for (const tab of tabs) {
    const reason = excludeReason(tab, dashPorts);
    if (reason) {
      skipped.push({ ...tab, reason });
      continue;
    }
    if (seen.has(tab.url)) continue;
    seen.add(tab.url);
    moving.push(tab);
  }
  return { moving, skipped };
}

/**
 * A tab's favicon as a data: URI, fetched HERE (on the desktop) because the
 * phone cannot reach `localhost` favicons and should not leak every domain to a
 * favicon service. Raster formats only — the phone decodes with BitmapFactory.
 * null when the page has none (the phone then draws a monogram).
 */
async function inlineFavicon(faviconUrl) {
  if (!faviconUrl) return null;
  if (faviconUrl.startsWith("data:image/") && !faviconUrl.startsWith("data:image/svg")) return faviconUrl;
  if (!/^https?:/.test(faviconUrl)) return null;
  // The phone polls this list every few seconds: a site's icon is fetched once an hour at most.
  const cached = faviconCache.get(faviconUrl);
  if (cached && Date.now() - cached.at < FAVICON_TTL_MS) return cached.icon;
  const icon = await fetchFavicon(faviconUrl);
  if (faviconCache.size >= FAVICON_CACHE_MAX) faviconCache.clear();
  faviconCache.set(faviconUrl, { icon, at: Date.now() });
  return icon;
}

const FAVICON_TTL_MS = 60 * 60 * 1000;
const FAVICON_CACHE_MAX = 500;
const faviconCache = new Map();

async function fetchFavicon(faviconUrl) {
  try {
    const res = await fetch(faviconUrl, { signal: AbortSignal.timeout(2500) });
    if (!res.ok) return null;
    const type = (res.headers.get("content-type") || "").split(";")[0].trim();
    if (!/^image\/(png|x-icon|vnd\.microsoft\.icon|jpeg|gif|webp)$/.test(type)) return null;
    const buf = Buffer.from(await res.arrayBuffer());
    if (buf.length > 96 * 1024) return null;
    return `data:${type};base64,${buf.toString("base64")}`;
  } catch {
    return null; // an unreachable icon is just a tab without one
  }
}

/** Chrome reports some page titles with their HTML entities still in them ("Don&#39;t"). */
function decodeEntities(text) {
  const named = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
  return text.replace(/&(?:#(\d+)|#x([0-9a-f]+)|(amp|lt|gt|quot|apos|nbsp));/gi, (whole, dec, hex, name) => {
    if (name) return named[name.toLowerCase()];
    const code = dec ? Number(dec) : parseInt(hex, 16);
    return code <= 0x10ffff ? String.fromCodePoint(code) : whole;
  });
}

function hostOf(url) {
  const u = new URL(url);
  return u.port ? `${u.hostname}:${u.port}` : u.hostname;
}

/** Desktop → phone: the desktop's tabs, filtered, rewritten for the phone. */
export async function desktopTabsForPhone() {
  const { moving, skipped } = await partition(await listPageTabs(DESKTOP_CDP));
  const icons = await Promise.all(moving.map((t) => inlineFavicon(t.faviconUrl)));
  return {
    tabs: moving.map((t, i) => {
      const url = forPhone(t.url);
      return { title: decodeEntities(t.title), url, host: hostOf(url), icon: icons[i] };
    }),
    skipped: skipped.map((t) => ({ title: decodeEntities(t.title), reason: t.reason })),
  };
}
