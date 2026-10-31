import { appIcon } from "@/lib/appIcon";

// PWA manifest 用的 192／512 圖示
export async function GET(_req: Request, ctx: { params: Promise<{ size: string }> }) {
  const n = Number((await ctx.params).size);
  return appIcon(n === 512 ? 512 : 192, { maskable: true });
}
