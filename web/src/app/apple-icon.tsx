import { appIcon } from "@/lib/appIcon";

export const size = { width: 180, height: 180 };
export const contentType = "image/png";

// iOS 會自己裁圓角，所以用滿版
export default function AppleIcon() {
  return appIcon(180, { maskable: true });
}
