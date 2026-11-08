"use client";

import { useState } from "react";

/**
 * What a browser that hasn't signed in sees. It names where the key is, because
 * the only reader is the owner on a new browser, who never chose a password.
 */
export default function SignIn({ linkExpired }: { linkExpired: boolean }) {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(
    linkExpired ? "That sign-in link has expired. Make a new one, or enter the code." : "",
  );

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!code.trim() || busy) return;
    setBusy(true);
    try {
      const res = await fetch("/api/session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: code.trim() }),
      });
      const body = await res.json().catch(() => ({}));
      if (res.ok) {
        window.location.replace("/");
        return;
      }
      setError(body.error || `Sign-in failed (HTTP ${res.status}).`);
    } catch {
      setError("Couldn't reach the tabSync server.");
    }
    setBusy(false);
  }

  return (
    <main className="grid min-h-dvh place-items-center px-5 py-10">
      <form
        data-id="sign-in-form"
        onSubmit={submit}
        className="w-full max-w-[22.5rem] rounded-2xl border border-line bg-surface p-6"
      >
        <div className="flex items-center gap-2.5">
          {/* eslint-disable-next-line @next/next/no-img-element -- the tab icon, already loaded */}
          <img src="/icon.svg" alt="" width={36} height={36} className="size-9 rounded-lg" />
          <h1 className="text-xl font-semibold">tabSync</h1>
        </div>
        <p className="mt-4 text-sm leading-6 text-ink-2">
          This page can open tabs on your computer and read your phone&apos;s tabs, so it&apos;s locked.
        </p>

        <label htmlFor="access-code" className="mt-5 block text-[13px] font-medium text-ink-2">
          Access code
        </label>
        <input
          id="access-code"
          data-id="access-code"
          type="password"
          autoComplete="current-password"
          autoFocus
          value={code}
          onChange={(e) => {
            setCode(e.target.value);
            setError("");
          }}
          aria-invalid={error ? true : undefined}
          aria-describedby="access-code-hint"
          className={`mt-1 h-11 w-full rounded-lg border bg-bg px-3 font-mono text-ink outline-none transition-[border-color,box-shadow] duration-150 focus:ring-3 ${
            error
              ? "border-danger focus:ring-danger/15"
              : "border-line-strong hover:border-ink-3 focus:border-accent focus:ring-accent/20"
          }`}
        />
        {error ? (
          <p id="access-code-hint" role="alert" className="mt-1.5 text-xs leading-5 text-danger">
            {error}
          </p>
        ) : (
          <p id="access-code-hint" className="mt-1.5 text-xs leading-5 text-ink-3">
            The value of <span className="font-mono">TABSYNC_API_TOKEN</span> in{" "}
            <span className="font-mono">.env.local</span>
          </p>
        )}

        <button
          type="submit"
          data-id="sign-in"
          disabled={!code.trim() || busy}
          className="mt-5 h-11 w-full cursor-pointer rounded-lg bg-accent px-4 font-semibold text-accent-ink transition-[background-color,transform] duration-150 hover:bg-accent-hover active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-accent disabled:active:scale-100"
        >
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </main>
  );
}
