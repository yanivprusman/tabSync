import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { SESSION_COOKIE, isSession } from "@/lib/auth";

// Which screen a browser gets at `/`: the desktop page, or the sign-in form.
// This decides what is SHOWN only — every API route checks the caller itself.
export function proxy(request: NextRequest) {
  if (isSession(request.cookies.get(SESSION_COOKIE)?.value)) return NextResponse.next();
  return NextResponse.rewrite(new URL("/signin", request.url));
}

export const config = { matcher: "/" };
