import type { Metadata, Viewport } from "next";
import { Noto_Sans_TC } from "next/font/google";
import { ToastProvider } from "@/components/Toast";
import { APP } from "@/lib/appConfig";
import "./globals.css";

const noto = Noto_Sans_TC({ variable: "--font-noto", weight: ["400", "500", "700"], display: "swap", preload: false });

export const metadata: Metadata = {
  title: APP.name,
  description: APP.description,
  applicationName: APP.name,
  appleWebApp: { capable: true, title: APP.name, statusBarStyle: "black-translucent" },
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: APP.backgroundDark,
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-Hant-TW" className={`${noto.variable} h-full antialiased`}>
      <body className="min-h-full">
        <ToastProvider>{children}</ToastProvider>
      </body>
    </html>
  );
}
