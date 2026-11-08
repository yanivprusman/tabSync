"use client";

export default function Header() {
  async function signOut() {
    await fetch("/api/session", { method: "DELETE" });
    window.location.replace("/");
  }
  return (
    <header className="flex items-center gap-3">
      {/* eslint-disable-next-line @next/next/no-img-element -- the tab icon, already loaded */}
      <img src="/icon.svg" alt="" width={40} height={40} className="size-10 rounded-xl" />
      <div className="min-w-0 flex-1">
        <h1 className="text-lg font-semibold leading-6">tabSync</h1>
        <p className="text-sm text-ink-3">Your phone&apos;s photos and tabs, on this computer</p>
      </div>
      <button
        type="button"
        data-id="sign-out"
        onClick={signOut}
        className="cursor-pointer rounded-lg px-2.5 py-1.5 text-sm text-ink-3 transition-colors hover:bg-raised hover:text-ink"
      >
        Sign out
      </button>
    </header>
  );
}
