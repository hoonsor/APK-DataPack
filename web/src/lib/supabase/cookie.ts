// 登入 cookie 設定：保留一年，有在使用就由 proxy 自動續期
export const SESSION_MAX_AGE = 60 * 60 * 24 * 365;

export const SESSION_COOKIE_OPTIONS = {
  maxAge: SESSION_MAX_AGE,
  sameSite: "lax" as const,
  secure: process.env.NODE_ENV === "production",
  httpOnly: true,
};
