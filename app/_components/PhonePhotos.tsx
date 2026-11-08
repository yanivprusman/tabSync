"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { RefreshIcon } from "./PhoneTabs";

type Photo = {
  id: string;
  name: string;
  folder: string;
  added: number;
  size: number;
  width: number | null;
  height: number | null;
  saved: string | null;
};
type Copy = { status: "queued" | "copying" | "error"; got: number; size: number; error?: string; started?: number };

const PAGE = 30;
const POLL_MS = 20000;

function mb(bytes: number) {
  return bytes >= 1e6 ? `${(bytes / 1e6).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1e3))} KB`;
}

function dayLabel(sec: number) {
  const d = new Date(sec * 1000);
  const today = new Date();
  const start = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const days = Math.round((start(today) - start(d)) / 86400000);
  if (days === 0) return "Today";
  if (days === 1) return "Yesterday";
  return d.toLocaleDateString(undefined, {
    weekday: days < 7 ? "long" : undefined,
    day: "numeric",
    month: "long",
    year: d.getFullYear() === today.getFullYear() ? undefined : "numeric",
  });
}

/**
 * The desktop half of the phone app's "Send photos to your desktop": the phone's
 * newest photos (the phone's own thumbnails), copied here full size on a click.
 */
export default function PhonePhotos() {
  const [photos, setPhotos] = useState<Photo[] | null>(null);
  const [total, setTotal] = useState(0);
  const [limit, setLimit] = useState(PAGE);
  const [failure, setFailure] = useState("");
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [copies, setCopies] = useState<Record<string, Copy>>({});
  const [notice, setNotice] = useState("");
  const inFlight = useRef(false);
  const queue = useRef<string[]>([]);
  const running = useRef(false);

  const load = useCallback(async (lim: number) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setLoading(true);
    try {
      const res = await fetch(`/api/phone-photos?limit=${lim}`, { cache: "no-store" });
      if (res.status === 401) {
        window.location.replace("/");
        return;
      }
      const body = await res.json();
      if (!res.ok) setFailure(body.error ?? `HTTP ${res.status}`);
      else {
        setPhotos(body.photos);
        setTotal(body.total);
        setFailure("");
      }
    } catch {
      setFailure("Couldn't reach the tabSync server on this computer.");
    } finally {
      inFlight.current = false;
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const first = setTimeout(() => load(limit), 0);
    const tick = setInterval(() => {
      if (document.visibilityState === "visible" && !running.current) load(limit);
    }, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible" && !running.current) load(limit);
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(first);
      clearInterval(tick);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [load, limit]);

  const flash = (text: string) => {
    setNotice(text);
    setTimeout(() => setNotice((n) => (n === text ? "" : n)), 2500);
  };

  async function copyOne(id: string) {
    setCopies((c) => ({ ...c, [id]: { status: "copying", got: 0, size: c[id]?.size ?? 0, started: Date.now() } }));
    try {
      const res = await fetch("/api/phone-photos/copy", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id }),
      });
      if (!res.ok || !res.body) throw new Error((await res.json().catch(() => ({}))).error || `HTTP ${res.status}`);
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let nl;
        while ((nl = buf.indexOf("\n")) >= 0) {
          const msg = JSON.parse(buf.slice(0, nl));
          buf = buf.slice(nl + 1);
          if (msg.error) throw new Error(msg.error);
          if (msg.done) {
            setPhotos((ps) => ps && ps.map((p) => (p.id === id ? { ...p, saved: msg.name } : p)));
            setCopies((c) => {
              const n = { ...c };
              delete n[id];
              return n;
            });
            return;
          }
          setCopies((c) => ({ ...c, [id]: { ...c[id], status: "copying", got: msg.got, size: msg.size } }));
        }
      }
      throw new Error("The copy was cut off before it finished.");
    } catch (e) {
      setCopies((c) => ({ ...c, [id]: { ...c[id], status: "error", error: (e as Error).message } }));
    }
  }

  // One photo at a time: they share one slow link, so parallel copies only all finish later.
  async function drain() {
    if (running.current) return;
    running.current = true;
    while (queue.current.length) await copyOne(queue.current.shift()!);
    running.current = false;
  }

  function enqueue(ids: string[]) {
    const fresh = ids.filter((id) => !queue.current.includes(id) && copies[id]?.status !== "copying");
    if (!fresh.length) return;
    queue.current.push(...fresh);
    setCopies((c) => {
      const n = { ...c };
      for (const id of fresh) {
        const p = photos?.find((x) => x.id === id);
        n[id] = { status: "queued", got: 0, size: p?.size ?? 0 };
      }
      return n;
    });
    setSelected(new Set());
    drain();
  }

  function toggle(id: string) {
    setSelected((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  async function openSaved(name: string, folder = false) {
    const res = await fetch("/api/phone-photos/open", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, folder }),
    });
    if (!res.ok) flash((await res.json().catch(() => ({}))).error || "Couldn't open it.");
  }

  async function copyToClipboard(name: string) {
    // Chrome's clipboard takes PNG only, so anything else is redrawn as PNG. The
    // blob is handed over as a promise so the click still counts as the gesture.
    const png = (async () => {
      const blob = await (await fetch(`/api/phone-photos/file?name=${encodeURIComponent(name)}`)).blob();
      if (blob.type === "image/png") return blob;
      const bmp = await createImageBitmap(blob);
      const canvas = document.createElement("canvas");
      canvas.width = bmp.width;
      canvas.height = bmp.height;
      canvas.getContext("2d")!.drawImage(bmp, 0, 0);
      return await new Promise<Blob>((r, j) => canvas.toBlob((b) => (b ? r(b) : j(new Error("encode failed"))), "image/png"));
    })();
    try {
      await navigator.clipboard.write([new ClipboardItem({ "image/png": png })]);
      flash("Copied — paste it anywhere");
    } catch (e) {
      flash(`Couldn't put it on the clipboard: ${(e as Error).message}`);
    }
  }

  const notSaved = photos?.filter((p) => !p.saved && !copies[p.id]) ?? [];
  const latest = notSaved[0];
  const busy = Object.values(copies).filter((c) => c.status !== "error").length;

  // Group by day, newest first (the list already is).
  const groups: { label: string; items: Photo[] }[] = [];
  for (const p of photos ?? []) {
    const label = dayLabel(p.added);
    if (groups.at(-1)?.label === label) groups.at(-1)!.items.push(p);
    else groups.push({ label, items: [p] });
  }

  return (
    <section className="mt-8">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-base font-semibold">Photos on your phone</h2>
        <button
          type="button"
          data-id="refresh-phone-photos"
          onClick={() => load(limit)}
          disabled={loading}
          title="Refresh"
          aria-label="Refresh photos"
          className="grid size-8 cursor-pointer place-items-center rounded-lg text-ink-3 transition-colors hover:bg-raised hover:text-ink active:bg-line disabled:cursor-default"
        >
          <RefreshIcon spinning={loading} />
        </button>
        <div className="flex-1" />
        {selected.size > 0 ? (
          <>
            <button
              type="button"
              data-id="clear-photo-selection"
              onClick={() => setSelected(new Set())}
              className="h-9 cursor-pointer rounded-lg px-3 text-sm text-ink-3 transition-colors hover:bg-raised hover:text-ink"
            >
              Clear
            </button>
            <button
              type="button"
              data-id="copy-selected-photos"
              onClick={() => enqueue([...selected])}
              className="h-9 cursor-pointer rounded-lg bg-accent px-4 text-sm font-semibold text-accent-ink transition-[background-color,transform] duration-150 hover:bg-accent-hover active:scale-[0.97]"
            >
              Copy {selected.size} to computer
            </button>
          </>
        ) : (
          photos &&
          latest && (
            <button
              type="button"
              data-id="copy-latest-photo"
              onClick={() => enqueue([latest.id])}
              className="h-9 cursor-pointer rounded-lg bg-accent px-4 text-sm font-semibold text-accent-ink transition-[background-color,transform] duration-150 hover:bg-accent-hover active:scale-[0.97]"
            >
              Copy latest to computer
            </button>
          )
        )}
      </div>
      <p className="mt-1 text-sm text-ink-3">
        Click photos to select them. Copies land in <span className="font-mono text-ink-2">~/Pictures/Screenshots</span>
        {busy > 0 && <span className="text-accent"> · copying {busy}…</span>}
      </p>

      {failure && (
        <div className="mt-4 rounded-2xl border border-line bg-surface px-5 py-6 text-center">
          <p className="text-sm text-ink-2">{failure}</p>
          <button
            type="button"
            data-id="retry-phone-photos"
            onClick={() => load(limit)}
            className="mt-4 h-9 cursor-pointer rounded-lg border border-line-strong px-4 text-sm text-ink-2 transition-colors hover:border-ink-3 hover:text-ink active:bg-raised"
          >
            Try again
          </button>
        </div>
      )}

      {!photos && !failure && (
        <div className="mt-5 grid animate-pulse grid-cols-3 gap-2 sm:grid-cols-5 md:grid-cols-6">
          {Array.from({ length: 12 }, (_, i) => (
            <div key={i} className="aspect-square rounded-xl bg-surface" />
          ))}
        </div>
      )}

      {groups.map((g) => (
        <div key={g.label} className="mt-5">
          <h3 className="mb-2 text-xs font-medium uppercase tracking-wide text-ink-3">{g.label}</h3>
          <div className="grid grid-cols-3 gap-2 sm:grid-cols-5 md:grid-cols-6">
            {g.items.map((p) => (
              <Tile
                key={p.id}
                photo={p}
                copy={copies[p.id]}
                selected={selected.has(p.id)}
                onToggle={() => toggle(p.id)}
                onCopy={() => enqueue([p.id])}
                onOpen={() => p.saved && openSaved(p.saved)}
                onFolder={() => p.saved && openSaved(p.saved, true)}
                onClipboard={() => p.saved && copyToClipboard(p.saved)}
              />
            ))}
          </div>
        </div>
      ))}

      {photos && photos.length < total && (
        <div className="mt-5 text-center">
          <button
            type="button"
            data-id="more-phone-photos"
            onClick={() => setLimit((l) => l + PAGE)}
            disabled={loading}
            className="h-9 cursor-pointer rounded-lg border border-line-strong px-4 text-sm text-ink-2 transition-colors hover:border-ink-3 hover:text-ink active:bg-raised disabled:cursor-wait disabled:opacity-60"
          >
            {loading ? "Loading…" : "Show older photos"}
          </button>
        </div>
      )}
      {photos && photos.length === 0 && <p className="mt-6 text-sm text-ink-3">No photos in the phone&apos;s camera or screenshots.</p>}

      {notice && (
        <div role="status" className="fixed bottom-6 left-1/2 z-50 -translate-x-1/2 rounded-xl border border-line-strong bg-raised px-4 py-2.5 text-sm text-ink shadow-2xl">
          {notice}
        </div>
      )}
    </section>
  );
}

function Tile({
  photo: p,
  copy,
  selected,
  onToggle,
  onCopy,
  onOpen,
  onFolder,
  onClipboard,
}: {
  photo: Photo;
  copy?: Copy;
  selected: boolean;
  onToggle: () => void;
  onCopy: () => void;
  onOpen: () => void;
  onFolder: () => void;
  onClipboard: () => void;
}) {
  const [broken, setBroken] = useState(false);
  const saved = !!p.saved;
  const pct = copy && copy.size ? Math.min(100, Math.round((copy.got / copy.size) * 100)) : 0;
  const time = new Date(p.added * 1000).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });

  return (
    <div
      className={`group relative aspect-square overflow-hidden rounded-xl bg-surface outline-2 -outline-offset-2 transition-[outline-color] ${
        selected ? "outline-accent" : copy?.status === "error" ? "outline-danger" : "outline-transparent"
      }`}
      title={`${p.name} · ${mb(p.size)} · ${time}`}
    >
      {broken ? (
        <div className="grid size-full place-items-center p-2 text-center text-[11px] text-ink-3">{p.name}</div>
      ) : (
        // eslint-disable-next-line @next/next/no-img-element -- a thumbnail from the phone, through our own route
        <img
          src={`/api/phone-photos/thumb?id=${p.id}&v=${p.added}`}
          alt={p.name}
          loading="lazy"
          onError={() => setBroken(true)}
          className="size-full object-cover"
        />
      )}

      {/* Whole tile selects — only for photos not on the computer yet. */}
      {!saved && !copy && (
        <button
          type="button"
          data-id="select-phone-photo"
          aria-pressed={selected}
          aria-label={`Select ${p.name}`}
          onClick={onToggle}
          className="absolute inset-0 cursor-pointer bg-black/0 transition-colors hover:bg-black/15"
        >
          <span
            className={`absolute left-2 top-2 grid size-6 place-items-center rounded-full border-2 transition-all ${
              selected
                ? "border-accent bg-accent text-accent-ink"
                : "border-white/80 bg-black/20 opacity-0 group-hover:opacity-100"
            }`}
          >
            {selected && <CheckIcon />}
          </span>
        </button>
      )}

      {!saved && !copy && !selected && (
        <button
          type="button"
          data-id="copy-phone-photo"
          onClick={onCopy}
          className="absolute bottom-2 right-2 cursor-pointer rounded-lg bg-black/60 px-2.5 py-1 text-xs font-medium text-white opacity-0 backdrop-blur transition-[opacity,background-color] hover:bg-accent hover:text-accent-ink group-hover:opacity-100 focus-visible:opacity-100"
        >
          Copy
        </button>
      )}

      {copy && copy.status !== "error" && (
        <div className="absolute inset-0 flex flex-col justify-end bg-black/45 p-2">
          <p className="text-xs font-medium text-white">
            {copy.status === "queued" ? "Waiting…" : `${pct}% · ${mb(copy.got)} of ${mb(copy.size)}`}
          </p>
          <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-white/25">
            <div className="h-full rounded-full bg-accent transition-[width] duration-300" style={{ width: `${pct}%` }} />
          </div>
        </div>
      )}

      {copy?.status === "error" && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/70 p-2 text-center">
          <p className="line-clamp-3 text-[11px] leading-4 text-danger">{copy.error}</p>
          <button
            type="button"
            data-id="retry-copy-phone-photo"
            onClick={onCopy}
            className="cursor-pointer rounded-lg bg-white/15 px-2.5 py-1 text-xs text-white transition-colors hover:bg-white/25"
          >
            Try again
          </button>
        </div>
      )}

      {saved && (
        <>
          <span className="absolute left-2 top-2 grid size-6 place-items-center rounded-full bg-accent text-accent-ink shadow" title="On this computer">
            <CheckIcon />
          </span>
          <div className="absolute inset-x-0 bottom-0 flex gap-1 bg-gradient-to-t from-black/75 to-transparent p-2 pt-6 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
            <TileAction id="open-saved-photo" onClick={onOpen}>Open</TileAction>
            <TileAction id="clipboard-saved-photo" onClick={onClipboard}>Copy image</TileAction>
            <TileAction id="folder-saved-photo" onClick={onFolder}>Folder</TileAction>
          </div>
        </>
      )}
    </div>
  );
}

function TileAction({ id, onClick, children }: { id: string; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      data-id={id}
      onClick={onClick}
      className="flex-1 cursor-pointer truncate rounded-md bg-white/15 px-1 py-1 text-[11px] font-medium text-white backdrop-blur transition-colors hover:bg-accent hover:text-accent-ink"
    >
      {children}
    </button>
  );
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  );
}
