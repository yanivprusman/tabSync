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

/** All page tabs a Chrome DevTools endpoint reports, in its own order. */
export async function listPageTabs(cdpBase) {
  let res;
  try {
    res = await fetch(`${cdpBase}/json/list`, { signal: AbortSignal.timeout(5000) });
  } catch (e) {
    throw new Error(`Chrome DevTools endpoint ${cdpBase} unreachable: ${e.message}`);
  }
  if (!res.ok) throw new Error(`${cdpBase}/json/list answered HTTP ${res.status}`);
  const targets = await res.json();
  return targets
    .filter((t) => t.type === "page")
    .map((t) => ({ title: t.title || t.url, url: t.url }));
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

/** Desktop → phone: the desktop's tabs, filtered, rewritten for the phone. */
export async function desktopTabsForPhone() {
  const { moving, skipped } = await partition(await listPageTabs(DESKTOP_CDP));
  return {
    tabs: moving.map((t) => ({ title: t.title, url: forPhone(t.url) })),
    skipped: skipped.map((t) => ({ title: t.title, reason: t.reason })),
  };
}
