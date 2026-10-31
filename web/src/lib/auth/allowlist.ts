// Email 白名單：伺服器環境變數 AUTH_ALLOWED_EMAILS（逗號分隔）

export function allowedEmails(raw = process.env.AUTH_ALLOWED_EMAILS ?? ""): string[] {
  return raw
    .split(",")
    .map((s) => s.trim().toLowerCase())
    .filter(Boolean);
}

/** 白名單是空的時候一律拒絕，避免忘記設定就對所有人開放 */
export function isAllowedEmail(email: string, raw?: string): boolean {
  return allowedEmails(raw).includes(email.trim().toLowerCase());
}
