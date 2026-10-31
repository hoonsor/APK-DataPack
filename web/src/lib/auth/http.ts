// API 共用：錯誤型別、統一錯誤回應、簡易頻率限制、OTP 錯誤訊息（純函式，可測試）

export class HttpError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Route Handler 統一錯誤處理：HttpError → 原狀態碼；Zod 錯誤 → 400；其他 → 500 */
export function handleError(err: unknown): Response {
  if (err instanceof HttpError) return Response.json({ error: err.message }, { status: err.status });
  if (err && typeof err === "object" && "issues" in err) {
    const issues = (err as { issues: { message: string }[] }).issues;
    return Response.json({ error: issues.map((i) => i.message).join("；") }, { status: 400 });
  }
  console.error(err);
  return Response.json({ error: "伺服器發生錯誤，請稍後再試" }, { status: 500 });
}

// 每個伺服器執行個體各自計算，只用來擋誤觸與小規模濫用
const hits = new Map<string, number[]>();

/** 超過次數丟 429 */
export function rateLimit(key: string, limit: number, windowMs: number, now = Date.now()) {
  const list = (hits.get(key) ?? []).filter((t) => now - t < windowMs);
  if (list.length >= limit) throw new HttpError(429, "操作太頻繁，請稍等一分鐘");
  list.push(now);
  hits.set(key, list);
}

/** Supabase signInWithOtp 的錯誤 → 給使用者看的訊息 */
export function otpErrorMessage(error: { status?: number; message: string }): string {
  // 504 幾乎都是 Supabase 連不上自訂 SMTP（多半是 Port 打錯）
  if (error.status === 504 || /timeout|deadline/i.test(error.message)) {
    return "寄信伺服器連線逾時，請檢查 Supabase 的 SMTP 設定（Port 建議 587）";
  }
  if (error.status === 429 || /rate limit/i.test(error.message)) {
    return "寄信太頻繁，請稍後再試（Supabase 預設每小時寄信次數有限）";
  }
  return `寄送驗證碼失敗：${error.message}`;
}
