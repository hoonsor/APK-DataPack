"use client";
// Email 驗證碼登入頁：輸入 Email → 收信 → 輸入數字驗證碼（不用點信裡的連結，iOS PWA 才能登入）
// 樣式用 pwa-shell 的 .card / .field / .btn；沒裝 pwa-shell 時請自行替換 className
import { useState } from "react";

const APP_NAME = "賈維斯資料包";
const TAGLINE = "用 Email 驗證碼登入（只開放倬的信箱）";

async function post(path: string, body: unknown) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error ?? `請求失敗（${res.status}）`);
  return data;
}

export default function LoginPage() {
  const [step, setStep] = useState<"email" | "code">("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const send = async (e?: React.FormEvent) => {
    e?.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await post("/api/auth/otp", { email });
      setStep("code");
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const verify = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await post("/api/auth/verify", { email, token: code });
      // 登入狀態改變後要整頁重新載入，讓 proxy 讀到新 cookie
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- 刻意整頁重新載入
      window.location.assign("/");
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  };

  return (
    <main className="mx-auto flex min-h-dvh max-w-sm flex-col justify-center px-6">
      <div className="mb-8 text-center">
        <h1 className="text-2xl font-bold">{APP_NAME}</h1>
        <p className="mt-1 text-sm text-muted">{TAGLINE}</p>
      </div>

      <div className="card p-6">
        {step === "email" ? (
          <form onSubmit={send} className="space-y-3">
            <label className="block">
              <span className="mb-1.5 block text-sm font-semibold">Email</span>
              <input
                className="field"
                type="email"
                inputMode="email"
                autoComplete="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value.trim())}
                placeholder="you@example.com"
              />
            </label>
            <button className="btn btn-primary w-full" disabled={busy || !email}>
              {busy ? "寄送中…" : "寄送驗證碼"}
            </button>
          </form>
        ) : (
          <form onSubmit={verify} className="space-y-3">
            <p className="text-sm text-muted">
              驗證碼已寄到 <b className="text-ink">{email}</b>，請到信箱查看後輸入（不要點信裡的連結）。
            </p>
            <input
              className="field num text-center text-2xl font-bold tracking-[0.4em]"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={10}
              required
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
              placeholder="••••••"
              autoFocus
            />
            <button className="btn btn-primary w-full" disabled={busy || code.length < 6}>
              {busy ? "驗證中…" : "登入"}
            </button>
            <div className="flex justify-between text-sm">
              <button type="button" className="font-semibold text-muted" onClick={() => setStep("email")}>
                換一個 Email
              </button>
              <button type="button" className="font-semibold text-accent" onClick={() => send()} disabled={busy}>
                重新寄送
              </button>
            </div>
          </form>
        )}
        {error && <p className="mt-3 rounded-xl bg-danger-soft px-3 py-2 text-sm font-semibold text-danger">{error}</p>}
      </div>
      <p className="mt-6 text-center text-xs text-muted">登入一次後會保持登入，除非按登出</p>
    </main>
  );
}
