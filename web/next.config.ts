import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 不開 cacheComponents：登入後的頁面每次都要讀 cookie 與即時資料（hoonsor-kit LESSONS「Next.js 16」）
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
