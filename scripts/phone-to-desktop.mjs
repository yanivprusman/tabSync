#!/usr/bin/env node
// phoneTabsToDesktop — open the phone's Chrome tabs in the desktop's Chrome.
// The terminal twin of the desktop page (localhost:3185). Both use
// lib/phoneTabs.mjs, so they read, filter and open tabs the same way.
//
// Unlike the page, this brings Chrome to the front on the phone when its
// DevTools socket isn't up: someone who typed the command wants the tabs now.
//
// Usage: phone-to-desktop.mjs [--dry-run] [--serial <adb serial>]

import { PHONE_SERIAL, phoneTabsForDesktop, openOnDesktop } from "../lib/phoneTabs.mjs";

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const serialArg = args.indexOf("--serial");
const serial = serialArg >= 0 ? args[serialArg + 1] : PHONE_SERIAL;

let result;
try {
  result = await phoneTabsForDesktop({ serial, wake: true });
} catch (e) {
  console.error(`phoneTabsToDesktop: ${e.message}`);
  process.exit(1);
}
const { tabs, skipped } = result;
const toOpen = tabs.filter((t) => !t.desktopId);

console.log(`Phone has ${tabs.length + skipped.length} tab(s).`);
for (const t of skipped) console.log(`  skip (${t.reason}): ${t.title}`);
for (const t of tabs.filter((t) => t.desktopId)) console.log(`  skip (already open on desktop): ${t.title}`);

for (const t of toOpen) {
  if (dryRun) {
    console.log(`  would open: ${t.url}`);
    continue;
  }
  try {
    await openOnDesktop(t.url);
  } catch (e) {
    console.error(`phoneTabsToDesktop: ${e.message}`);
    process.exit(1);
  }
  console.log(`  opened: ${t.title}`);
}
console.log(dryRun ? `${toOpen.length} tab(s) would open on the desktop.` : `${toOpen.length} tab(s) opened on the desktop.`);
