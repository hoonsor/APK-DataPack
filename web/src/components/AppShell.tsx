"use client";
// 手機 App 外框：毛玻璃頂部標題列＋浮動膠囊底部導覽（含 iOS 安全區域）
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ComponentType, ReactNode, SVGProps } from "react";
import { APP } from "@/lib/appConfig";
import { isActiveTab } from "@/lib/nav";
import { HomeIcon, ListIcon, SettingsIcon } from "./icons";

export interface Tab {
  href: string;
  label: string;
  Icon: ComponentType<SVGProps<SVGSVGElement>>;
}

/** 預設分頁；專案可直接改這裡或用 tabs prop 傳入 */
export const DEFAULT_TABS: Tab[] = [
  { href: "/", label: "首頁", Icon: HomeIcon },
  { href: "/list", label: "列表", Icon: ListIcon },
  { href: "/settings", label: "設定", Icon: SettingsIcon },
];

export function AppShell({
  children,
  title,
  action,
  tabs = DEFAULT_TABS,
}: {
  children: ReactNode;
  title?: ReactNode;
  action?: ReactNode;
  tabs?: Tab[];
}) {
  const path = usePathname();
  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-xl flex-col">
      <header className="sticky top-0 z-20 flex items-center justify-between gap-3 px-5 pb-3 pt-[max(env(safe-area-inset-top),1rem)] backdrop-blur-xl">
        <div className="text-lg font-bold tracking-tight">{title ?? APP.name}</div>
        {action}
      </header>
      <main className="flex-1 px-4 pb-32">{children}</main>
      <nav className="safe-bottom fixed inset-x-0 bottom-0 z-30">
        <div className="card mx-auto flex max-w-sm items-center justify-around rounded-full px-2 py-1.5">
          {tabs.map(({ href, label, Icon }) => (
            <Link
              key={href}
              href={href}
              className={`flex flex-1 flex-col items-center gap-0.5 rounded-full py-1.5 text-[11px] font-semibold transition ${
                isActiveTab(href, path) ? "bg-accent-soft text-accent" : "text-muted"
              }`}
            >
              <Icon width={20} height={20} />
              {label}
            </Link>
          ))}
        </div>
      </nav>
    </div>
  );
}
