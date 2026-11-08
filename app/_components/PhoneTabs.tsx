"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type Tab = { title: string; url: string; host: string; icon: string | null; desktopId: string | null };
type Skipped = { title: string; reason: string };
type Data = { tabs: Tab[]; skipped: Skipped[] };
type Failure = { reason: string; message: string };

const POLL_MS = 15000;

const SKIP_LABEL: Record<string, string> = {
  whatsapp: "WhatsApp",
  solitaire: "Solitaire",
  dashboard: "Dashboard",
  "not a web page": "Not a web page",
  "unparseable url": "Unreadable address",
};

/**
 * The desktop half of tabSync: the phone's Chrome tabs, each one click from
 * being open here. The mirror of the phone app's "Tabs on your desktop".
 */
export default function PhoneTabs() {
  const [data, setData] = useState<Data | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [loading, setLoading] = useState(true);
  const [waking, setWaking] = useState(false);
  const [busyUrls, setBusyUrls] = useState<Set<string>>(new Set());
  const [openingAll, setOpeningAll] = useState(false);
  const [actionError, setActionError] = useState("");
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const inFlight = useRef(false);

  const load = useCallback(async (wake = false) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setLoading(true);
    try {
      const res = await fetch(`/api/phone-tabs${wake ? "?wake=1" : ""}`, { cache: "no-store" });
      if (res.status === 401) {
        window.location.replace("/");
        return;
      }
      const body = await res.json();
      if (!res.ok) {
        setFailure({ reason: body.reason ?? "error", message: body.error ?? `HTTP ${res.status}` });
      } else {
        setData(body);
        setFailure(null);
        setUpdatedAt(Date.now());
      }
    } catch {
      setFailure({ reason: "server", message: "Couldn't reach the tabSync server on this computer." });
    } finally {
      inFlight.current = false;
      setLoading(false);
    }
  }, []);

  // Poll only while the page is on screen; catch up the moment it comes back.
  useEffect(() => {
    const first = setTimeout(load, 0);
    const tick = setInterval(() => {
      if (document.visibilityState === "visible") load();
    }, POLL_MS);
    const onVisible = () => {
      if (document.visibilityState === "visible") load();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(first);
      clearInterval(tick);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [load]);

  async function post(path: string, body: unknown) {
    const res = await fetch(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const out = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(out.error || `HTTP ${res.status}`);
    return out;
  }

  function markOpened(opened: { url: string; id: string }[]) {
    const ids = new Map(opened.map((o) => [o.url, o.id]));
    setData((d) => d && { ...d, tabs: d.tabs.map((t) => (ids.has(t.url) ? { ...t, desktopId: ids.get(t.url)! } : t)) });
  }

  async function openOne(tab: Tab) {
    setActionError("");
    setBusyUrls((s) => new Set(s).add(tab.url));
    try {
      if (tab.desktopId) await post("/api/phone-tabs/show", { id: tab.desktopId });
      else markOpened((await post("/api/phone-tabs/open", { urls: [tab.url] })).opened);
    } catch (e) {
      setActionError((e as Error).message);
      if (tab.desktopId) load(); // the desktop tab was probably closed: re-read which are open
    } finally {
      setBusyUrls((s) => {
        const n = new Set(s);
        n.delete(tab.url);
        return n;
      });
    }
  }

  async function openAll() {
    if (!data) return;
    const urls = data.tabs.filter((t) => !t.desktopId).map((t) => t.url);
    if (!urls.length) return;
    setActionError("");
    setOpeningAll(true);
    try {
      markOpened((await post("/api/phone-tabs/open", { urls })).opened);
    } catch (e) {
      setActionError((e as Error).message);
      load();
    } finally {
      setOpeningAll(false);
    }
  }

  async function wake() {
    setWaking(true);
    await load(true);
    setWaking(false);
  }

  async function signOut() {
    await fetch("/api/session", { method: "DELETE" });
    window.location.replace("/");
  }

  const notOpen = data?.tabs.filter((t) => !t.desktopId).length ?? 0;

  return (
    <main className="mx-auto w-full max-w-2xl px-5 py-10 sm:py-14">
      <header className="flex items-center gap-3">
        {/* eslint-disable-next-line @next/next/no-img-element -- the tab icon, already loaded */}
        <img src="/icon.svg" alt="" width={40} height={40} className="size-10 rounded-xl" />
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold leading-6">tabSync</h1>
          <p className="text-sm text-ink-3">Your phone&apos;s tabs, on this computer</p>
        </div>
        <button
          type="button"
          data-id="refresh-phone-tabs"
          onClick={() => load()}
          disabled={loading}
          title="Refresh"
          aria-label="Refresh"
          className="grid size-9 cursor-pointer place-items-center rounded-lg text-ink-3 transition-colors hover:bg-raised hover:text-ink active:bg-line disabled:cursor-default"
        >
          <RefreshIcon spinning={loading} />
        </button>
        <button
          type="button"
          data-id="sign-out"
          onClick={signOut}
          className="cursor-pointer rounded-lg px-2.5 py-1.5 text-sm text-ink-3 transition-colors hover:bg-raised hover:text-ink"
        >
          Sign out
        </button>
      </header>

      <section className="mt-8 overflow-hidden rounded-2xl border border-line bg-surface">
        <div className="flex items-center gap-3 border-b border-line px-5 py-4">
          <h2 className="font-semibold">Tabs on your phone</h2>
          {data && (
            <span className="rounded-full bg-raised px-2 py-0.5 text-xs font-medium tabular-nums text-ink-2">
              {data.tabs.length}
            </span>
          )}
          <div className="flex-1" />
          {data && data.tabs.length > 0 && (
            <button
              type="button"
              data-id="open-all-on-desktop"
              onClick={openAll}
              disabled={notOpen === 0 || openingAll}
              className="h-9 cursor-pointer rounded-lg bg-accent px-4 text-sm font-semibold text-accent-ink transition-[background-color,transform] duration-150 hover:bg-accent-hover active:scale-[0.97] disabled:cursor-not-allowed disabled:bg-raised disabled:text-ink-3 disabled:active:scale-100"
            >
              {openingAll ? "Opening…" : notOpen === 0 ? "All open here" : `Open ${notOpen === data.tabs.length ? "all" : notOpen} here`}
            </button>
          )}
        </div>

        {actionError && (
          <p role="alert" className="border-b border-line bg-danger/10 px-5 py-2.5 text-sm text-danger">
            {actionError}
          </p>
        )}
        {failure && data && (
          <p role="status" className="border-b border-line bg-raised/60 px-5 py-2.5 text-sm text-ink-2">
            Couldn&apos;t refresh: {failure.message} Showing the last list.
          </p>
        )}

        {!data && failure ? (
          <FailureView failure={failure} waking={waking} onWake={wake} onRetry={() => load()} />
        ) : !data ? (
          <Skeleton />
        ) : data.tabs.length === 0 ? (
          <p className="px-5 py-10 text-center text-sm text-ink-3">
            {data.skipped.length ? "Nothing to bring over — every tab on the phone stays there." : "No tabs open on the phone."}
          </p>
        ) : (
          <ul>
            {data.tabs.map((tab) => (
              <TabRow key={tab.url} tab={tab} busy={busyUrls.has(tab.url) || openingAll} onAction={() => openOne(tab)} />
            ))}
          </ul>
        )}
      </section>

      <div className="mt-3 flex items-start justify-between gap-4 px-1 text-xs text-ink-3">
        {data && data.skipped.length > 0 ? (
          <details data-id="skipped-tabs" className="group min-w-0">
            <summary className="cursor-pointer list-none select-none transition-colors hover:text-ink-2">
              <span className="inline-block transition-transform group-open:rotate-90">›</span>{" "}
              {data.skipped.length} {data.skipped.length === 1 ? "tab stays" : "tabs stay"} on the phone
            </summary>
            <ul className="mt-2 space-y-1 pl-3">
              {data.skipped.map((s, i) => (
                <li key={i} className="flex gap-2">
                  <span className="shrink-0 text-ink-2">{SKIP_LABEL[s.reason] ?? s.reason}</span>
                  <span dir="auto" className="truncate">{s.title}</span>
                </li>
              ))}
            </ul>
          </details>
        ) : (
          <span />
        )}
        {updatedAt && <UpdatedAgo at={updatedAt} />}
      </div>
    </main>
  );
}

function TabRow({ tab, busy, onAction }: { tab: Tab; busy: boolean; onAction: () => void }) {
  const open = !!tab.desktopId;
  return (
    <li className="flex items-center gap-3.5 border-b border-line px-5 py-3 transition-colors last:border-b-0 hover:bg-raised/50">
      <Favicon tab={tab} />
      <div className="min-w-0 flex-1">
        <p dir="auto" className="truncate text-left text-[15px] leading-6 text-ink" title={tab.title}>
          {tab.title}
        </p>
        <p className="truncate text-xs leading-5 text-ink-3" title={tab.url}>
          {tab.host}
          {open && <span className="text-accent/80"> · open here</span>}
        </p>
      </div>
      <button
        type="button"
        data-id={open ? "show-tab-on-desktop" : "open-tab-on-desktop"}
        onClick={onAction}
        disabled={busy}
        className={`h-8 min-w-16 shrink-0 cursor-pointer rounded-lg px-3 text-sm font-medium transition-[background-color,color,border-color,transform] duration-150 active:scale-[0.96] disabled:cursor-wait disabled:opacity-50 disabled:active:scale-100 ${
          open
            ? "text-ink-2 hover:bg-line hover:text-ink"
            : "border border-accent/40 text-accent hover:border-accent hover:bg-accent/10"
        }`}
      >
        {busy ? "…" : open ? "Show" : "Open"}
      </button>
    </li>
  );
}

function Favicon({ tab }: { tab: Tab }) {
  const [broken, setBroken] = useState(false);
  if (tab.icon && !broken)
    // eslint-disable-next-line @next/next/no-img-element -- a remote favicon, any size, any host
    return <img src={tab.icon} alt="" width={20} height={20} className="size-5 shrink-0 rounded" onError={() => setBroken(true)} />;
  const letter = (tab.host.replace(/^www\./, "")[0] ?? "?").toUpperCase();
  return (
    <span aria-hidden className="grid size-5 shrink-0 place-items-center rounded bg-line text-[11px] font-semibold text-ink-2">
      {letter}
    </span>
  );
}

function FailureView({
  failure,
  waking,
  onWake,
  onRetry,
}: {
  failure: Failure;
  waking: boolean;
  onWake: () => void;
  onRetry: () => void;
}) {
  const canWake = failure.reason === "phone-chrome-asleep" || failure.reason === "phone-chrome-wont-wake";
  return (
    <div className="px-5 py-10 text-center">
      <p className="mx-auto max-w-md text-sm leading-6 text-ink-2">{failure.message}</p>
      {canWake && (
        <p className="mx-auto mt-1 max-w-md text-xs leading-5 text-ink-3">
          Opening it brings Chrome to the front on the phone&apos;s screen.
        </p>
      )}
      <div className="mt-5 flex justify-center gap-2">
        {canWake && (
          <button
            type="button"
            data-id="wake-phone-chrome"
            onClick={onWake}
            disabled={waking}
            className="h-9 cursor-pointer rounded-lg bg-accent px-4 text-sm font-semibold text-accent-ink transition-[background-color,transform] duration-150 hover:bg-accent-hover active:scale-[0.97] disabled:cursor-wait disabled:opacity-60"
          >
            {waking ? "Opening Chrome…" : "Open Chrome on the phone"}
          </button>
        )}
        <button
          type="button"
          data-id="retry-phone-tabs"
          onClick={onRetry}
          className="h-9 cursor-pointer rounded-lg border border-line-strong px-4 text-sm text-ink-2 transition-colors hover:border-ink-3 hover:text-ink active:bg-raised"
        >
          Try again
        </button>
      </div>
    </div>
  );
}

function Skeleton() {
  return (
    <ul aria-label="Loading the phone's tabs" className="animate-pulse">
      {[0.62, 0.45, 0.7].map((w, i) => (
        <li key={i} className="flex items-center gap-3.5 border-b border-line px-5 py-3 last:border-b-0">
          <span className="size-5 rounded bg-line" />
          <div className="flex-1 space-y-2 py-1">
            <div className="h-3 rounded bg-line" style={{ width: `${w * 100}%` }} />
            <div className="h-2.5 w-1/4 rounded bg-line/70" />
          </div>
          <span className="h-8 w-16 rounded-lg bg-line/60" />
        </li>
      ))}
    </ul>
  );
}

function UpdatedAgo({ at }: { at: number }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(t);
  }, []);
  const s = Math.max(0, Math.round((now - at) / 1000));
  return <span className="shrink-0 tabular-nums">{s < 10 ? "Updated just now" : `Updated ${s < 60 ? `${s}s` : `${Math.round(s / 60)}m`} ago`}</span>;
}

function RefreshIcon({ spinning }: { spinning: boolean }) {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={spinning ? "animate-spin" : ""} aria-hidden>
      <path d="M21 12a9 9 0 1 1-2.64-6.36" />
      <path d="M21 3v6h-6" />
    </svg>
  );
}
