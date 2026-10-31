// Next 16 的 middleware 已改名為 proxy（檔名 src/proxy.ts、函式名 proxy）
// 每次請求都續期 Supabase 登入 cookie；未登入的頁面導向 /login、API 回 401
import { createServerClient } from "@supabase/ssr";
import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE_OPTIONS } from "./lib/supabase/cookie";

/** 不需要登入的路徑（前綴比對） */
const PUBLIC_PATHS = ["/login", "/api/auth/"];

export async function proxy(request: NextRequest) {
  let response = NextResponse.next({ request });
  const supabase = createServerClient(process.env.NEXT_PUBLIC_SUPABASE_URL!, process.env.NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY!, {
    cookieOptions: SESSION_COOKIE_OPTIONS,
    cookies: {
      getAll: () => request.cookies.getAll(),
      setAll(cookiesToSet, headers) {
        cookiesToSet.forEach(({ name, value }) => request.cookies.set(name, value));
        response = NextResponse.next({ request });
        cookiesToSet.forEach(({ name, value, options }) => response.cookies.set(name, value, options));
        Object.entries(headers).forEach(([k, v]) => response.headers.set(k, v));
      },
    },
  });

  const { data } = await supabase.auth.getClaims();
  const path = request.nextUrl.pathname;
  const isPublic = PUBLIC_PATHS.some((p) => path.startsWith(p));

  if (!data?.claims && !isPublic) {
    if (path.startsWith("/api/")) return Response.json({ error: "請先登入" }, { status: 401 });
    const url = request.nextUrl.clone();
    url.pathname = "/login";
    url.search = "";
    return NextResponse.redirect(url);
  }
  if (data?.claims && path === "/login") {
    const url = request.nextUrl.clone();
    url.pathname = "/";
    return NextResponse.redirect(url);
  }
  return response;
}

export const config = {
  // 靜態檔、PWA 圖示與 manifest 不經過 proxy（否則未登入時主畫面圖示會被導向 /login）
  matcher: ["/((?!_next/static|_next/image|favicon.ico|icon|icons/|apple-icon|manifest.webmanifest|.*\\.(?:png|svg|ico|webp)$).*)"],
};
