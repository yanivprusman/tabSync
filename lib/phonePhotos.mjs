// Phone → desktop photos, for the desktop page.
//
// The phone's photos are read through Android's own MediaStore over adb
// (`content query` / `content read`), never by file path: ids are numbers, so
// nothing a file is named can reach a shell, and the phone's thumbnail service
// hands back a ~50 KB preview for a photo that may be 30 MB.
//
// Why thumbnails come from the phone: adb to the phone runs over WireGuard at
// roughly 0.35 MB/s (measured 2026-11-08: a 30 MB photo took 83 s). Pulling full
// photos just to draw a grid is out of the question; a thumbnail costs ~1 s.
//
// Same scope as the phone app's photo strip: images under DCIM (camera and
// screenshots), not chat downloads.

import { spawn } from "node:child_process";
import { createWriteStream } from "node:fs";
import { link, mkdir, readFile, readdir, stat, unlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { PHONE_SERIAL, adb, attach } from "./phoneTabs.mjs";

/** Where photos land on the desktop — the same folder the phone app sends to. */
export const PHOTO_DIR = path.join(os.homedir(), "Pictures", "Screenshots");
const THUMB_DIR = path.join(os.homedir(), ".cache", "tabsync", "thumbs");
const IMAGES = "content://media/external/images/media";
const COLUMNS = ["_id", "_display_name", "relative_path", "date_added", "_size", "width", "height"];

/** One line of `content query` output → an object. Values may contain ", ", so split on the known keys. */
function parseRow(line) {
  const body = line.replace(/^Row: \d+ /, "");
  if (!body.startsWith(`${COLUMNS[0]}=`)) return null;
  // where each "key=" begins, searching forward so a ", key=" inside a name can only come after its own key
  const starts = [0];
  for (let i = 1; i < COLUMNS.length; i++) {
    const at = body.indexOf(`, ${COLUMNS[i]}=`, starts[i - 1]);
    if (at < 0) return null;
    starts.push(at + 2);
  }
  const out = {};
  for (let i = 0; i < COLUMNS.length; i++) {
    const from = starts[i] + COLUMNS[i].length + 1;
    const to = i + 1 < COLUMNS.length ? starts[i + 1] - 2 : body.length;
    out[COLUMNS[i]] = body.slice(from, to);
  }
  return out;
}

/** Every DCIM image on the phone, newest first. */
async function queryImages(serial, where) {
  const raw = await adb(
    serial,
    "shell",
    `content query --uri ${IMAGES} --projection ${COLUMNS.join(":")} --where "${where}" --sort 'date_added DESC'`,
  );
  if (raw.startsWith("No result found")) return [];
  return raw
    .split("\n")
    .filter((l) => l.startsWith("Row: "))
    .map(parseRow)
    .filter(Boolean)
    .map((r) => ({
      id: r._id,
      name: r._display_name,
      folder: r.relative_path.replace(/^DCIM\//, "").replace(/\/$/, "") || "DCIM",
      added: Number(r.date_added),
      size: Number(r._size),
      width: Number(r.width) || null,
      height: Number(r.height) || null,
    }));
}

/** name → size of every file already in the desktop's photo folder. */
async function desktopFiles() {
  const sizes = new Map();
  for (const name of await readdir(PHOTO_DIR).catch(() => [])) {
    try {
      sizes.set(name, (await stat(path.join(PHOTO_DIR, name))).size);
    } catch {
      // vanished while we looked
    }
  }
  return sizes;
}

/**
 * The newest `limit` DCIM photos, each with `saved`: the desktop file it was
 * already copied to (same name and same size), or null.
 */
export async function listPhonePhotos({ serial = PHONE_SERIAL, limit = 30 } = {}) {
  await attach(serial);
  const all = await queryImages(serial, "relative_path LIKE 'DCIM/%'");
  const files = await desktopFiles();
  const savedName = (p) => {
    if (files.get(p.name) === p.size) return p.name;
    // a name that was taken gets "-1", "-2" … (see saveUnique)
    const { name: stem, ext } = path.parse(p.name);
    for (let i = 1; i < 20; i++) if (files.get(`${stem}-${i}${ext}`) === p.size) return `${stem}-${i}${ext}`;
    return null;
  };
  return {
    total: all.length,
    photos: all.slice(0, limit).map((p) => ({ ...p, saved: savedName(p) })),
  };
}

function checkId(id) {
  if (!/^\d+$/.test(String(id))) throw new Error(`bad photo id: ${id}`);
  return String(id);
}

/** adb exec-out <cmd> → the raw bytes (thumbnails only — small). */
function execOut(serial, cmd) {
  return new Promise((resolve, reject) => {
    const p = spawn("adb", ["-s", serial, "exec-out", cmd]);
    const chunks = [];
    let err = "";
    p.stdout.on("data", (d) => chunks.push(d));
    p.stderr.on("data", (d) => (err += d));
    const timer = setTimeout(() => p.kill(), 20000);
    p.on("error", reject);
    p.on("close", (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(Buffer.concat(chunks));
      else reject(new Error(err.trim() || `adb exited ${code}`));
    });
  });
}

// At most a few thumbnails in flight: each is its own adb session.
let active = 0;
const waiting = [];
async function limited(fn) {
  if (active >= 4) await new Promise((r) => waiting.push(r));
  active++;
  try {
    return await fn();
  } finally {
    active--;
    waiting.shift()?.();
  }
}

/** The phone's own thumbnail for a photo (~384×512 JPEG), cached on disk by id + date. */
export async function phoneThumbnail(id, added, { serial = PHONE_SERIAL } = {}) {
  id = checkId(id);
  const file = path.join(THUMB_DIR, `${id}-${Number(added) || 0}.jpg`);
  try {
    return await readFile(file);
  } catch {
    // not cached yet
  }
  const bytes = await limited(() => execOut(serial, `content read --uri ${IMAGES}/${id}/thumbnail`));
  // A JPEG starts FF D8; anything else is adb or Android saying no.
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8)
    throw new Error(`the phone has no thumbnail for photo ${id}: ${bytes.subarray(0, 200).toString().trim()}`);
  await mkdir(THUMB_DIR, { recursive: true });
  await writeFile(file, bytes);
  return bytes;
}

/** A file name that may decide nothing but a name inside PHOTO_DIR. */
export function safeName(raw) {
  const name = path.basename(String(raw ?? "").replaceAll("\\", "/"));
  if (!name || name.startsWith(".") || /[\x00-\x1f]/.test(name)) throw new Error(`bad file name: "${raw}"`);
  return name;
}

/** Move `tmp` into PHOTO_DIR as `name`, never replacing a file: a taken name becomes "name-1.jpg". */
async function saveUnique(tmp, name) {
  const { name: stem, ext } = path.parse(name);
  for (let i = 0; ; i++) {
    const saved = i === 0 ? name : `${stem}-${i}${ext}`;
    try {
      await link(tmp, path.join(PHOTO_DIR, saved));
      await unlink(tmp);
      return saved;
    } catch (e) {
      if (e.code !== "EEXIST") throw e;
    }
  }
}

/**
 * Copy one photo, full size, from the phone into PHOTO_DIR.
 * `onProgress(bytesSoFar, totalBytes)` is called as it arrives.
 * Resolves with the saved file name. The photo is looked up by id on the phone,
 * so the name and size come from Android, not from the caller.
 */
export async function copyPhonePhoto(id, onProgress, { serial = PHONE_SERIAL } = {}) {
  id = checkId(id);
  await attach(serial);
  const [photo] = await queryImages(serial, `_id=${id}`);
  if (!photo) throw new Error("That photo isn't on the phone any more.");
  const name = safeName(photo.name);

  await mkdir(PHOTO_DIR, { recursive: true });
  const tmp = path.join(PHOTO_DIR, `.tabsync-${id}-${process.pid}.part`);
  let got = 0;
  try {
    await new Promise((resolve, reject) => {
      const p = spawn("adb", ["-s", serial, "exec-out", `content read --uri ${IMAGES}/${id}`]);
      const out = createWriteStream(tmp, { flags: "w" });
      let err = "";
      let last = 0;
      p.stdout.on("data", (d) => {
        got += d.length;
        const now = Date.now();
        if (now - last > 250) {
          last = now;
          onProgress?.(got, photo.size);
        }
      });
      p.stdout.pipe(out);
      p.stderr.on("data", (d) => (err += d));
      p.on("error", reject);
      p.on("close", (code) => {
        out.end(() => {
          if (code !== 0) reject(new Error(err.trim() || `adb exited ${code}`));
          else resolve();
        });
      });
    });
    if (got !== photo.size)
      throw new Error(`The copy stopped early: got ${got} of ${photo.size} bytes. Nothing was saved.`);
    onProgress?.(got, photo.size);
    return { name: await saveUnique(tmp, name), size: got };
  } finally {
    await unlink(tmp).catch(() => {});
  }
}
