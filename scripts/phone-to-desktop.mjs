#!/usr/bin/env node
// phoneTabsToDesktop — open the phone's Chrome tabs in the desktop's Chrome.
// The reverse of the tabSync phone app. Same filter (lib/tabs.mjs): solitaire,
// dashboard and WhatsApp tabs stay where they are.
//
// How the phone's tabs are read: Chrome on Android serves the DevTools HTTP
// endpoint on the abstract socket `chrome_devtools_remote` whenever adb is
// enabled — but only after Chrome has been in the foreground once. adb forwards
// a local port to it; `/json/list` is a read-only listing.
//
// "Already open" compares URLs as the phone sees them (localhost == 10.7.0.2).
//
// How the desktop opens them: `PUT /json/new?<url>` on the everyday Chrome
// (9222). That creates a tab and nothing else — it never sends
// setDownloadBehavior, which would break Chrome's download buttons.
//
// Usage: phone-to-desktop.mjs [--dry-run] [--serial <adb serial>]

import { execFileSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { DESKTOP_CDP, forPhone, listPageTabs, partition } from "../lib/tabs.mjs";

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const serialArg = args.indexOf("--serial");
// The main phone (SM-A536E), reached over WireGuard — see automateLinux memory.
const SERIAL = serialArg >= 0 ? args[serialArg + 1] : "10.7.0.3:5555";
const SOCKET = "chrome_devtools_remote";

function adb(...a) {
  return execFileSync("adb", ["-s", SERIAL, ...a], { encoding: "utf8", timeout: 15000 }).trim();
}

function die(msg) {
  console.error(`phoneTabsToDesktop: ${msg}`);
  process.exit(1);
}

// 1. Attach the phone. An empty `adb devices` only means the adb server was
//    never told about it; connecting is a precondition, not a retry.
const devices = execFileSync("adb", ["devices"], { encoding: "utf8" });
if (!new RegExp(`^${SERIAL.replace(/[.]/g, "\\.")}\\s+device$`, "m").test(devices)) {
  if (SERIAL.includes(":")) execFileSync("adb", ["connect", SERIAL], { encoding: "utf8", timeout: 15000 });
  const again = execFileSync("adb", ["devices"], { encoding: "utf8" });
  if (!new RegExp(`^${SERIAL.replace(/[.]/g, "\\.")}\\s+device$`, "m").test(again))
    die(`phone ${SERIAL} is not attached to adb (tried adb connect). Try phoneAdbConnect.`);
}

// 2. Chrome's DevTools socket exists only once Chrome has been foregrounded.
const hasSocket = () => adb("shell", `grep -a -c '@${SOCKET}$' /proc/net/unix || true`) !== "0";
if (!hasSocket()) {
  console.log("Phone Chrome's DevTools socket is not up yet — bringing Chrome to the front once…");
  adb("shell", "am", "start", "-n", "com.android.chrome/com.google.android.apps.chrome.Main");
  for (let i = 0; i < 20 && !hasSocket(); i++) await sleep(500);
  if (!hasSocket())
    die("phone Chrome never opened its DevTools socket (is the phone unlocked? is USB/wireless debugging on?)");
}

// 3. Read the phone's tabs through a throwaway forward.
const port = adb("forward", "tcp:0", `localabstract:${SOCKET}`);
let phoneTabs;
try {
  phoneTabs = await listPageTabs(`http://127.0.0.1:${port}`);
} finally {
  adb("forward", "--remove", `tcp:${port}`);
}

const { moving, skipped } = await partition(phoneTabs);
const alreadyOpen = new Set((await listPageTabs(DESKTOP_CDP)).map((t) => forPhone(t.url)));
const toOpen = moving.filter((t) => !alreadyOpen.has(forPhone(t.url)));

console.log(`Phone has ${phoneTabs.length} tab(s).`);
for (const t of skipped) console.log(`  skip (${t.reason}): ${t.title}`);
for (const t of moving.filter((t) => alreadyOpen.has(forPhone(t.url)))) console.log(`  skip (already open on desktop): ${t.title}`);

// 4. Open the rest on the desktop.
for (const t of toOpen) {
  if (dryRun) {
    console.log(`  would open: ${t.url}`);
    continue;
  }
  const res = await fetch(`${DESKTOP_CDP}/json/new?${encodeURIComponent(t.url)}`, { method: "PUT" });
  if (!res.ok) die(`desktop Chrome refused to open ${t.url}: HTTP ${res.status}`);
  console.log(`  opened: ${t.title}`);
}
console.log(dryRun ? `${toOpen.length} tab(s) would open on the desktop.` : `${toOpen.length} tab(s) opened on the desktop.`);
