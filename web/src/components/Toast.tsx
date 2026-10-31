"use client";
// 輕量 Toast：useToast()({ text, tone, action? })，可帶「復原」之類的按鈕
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";

export interface ToastInput {
  text: string;
  tone: "ok" | "error";
  action?: { label: string; run: () => void };
}

interface ToastItem extends ToastInput {
  id: number;
}

const ToastCtx = createContext<(t: ToastInput, ms?: number) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);
  const push = useCallback((t: ToastInput, ms = 4000) => {
    const id = ++seq.current;
    setItems((list) => [...list, { ...t, id }]);
    setTimeout(() => setItems((list) => list.filter((x) => x.id !== id)), ms);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 bottom-24 z-50 flex flex-col items-center gap-2 px-4" aria-live="polite">
        {items.map((t) => (
          <div
            key={t.id}
            className={`rise pointer-events-auto flex items-center gap-3 rounded-full px-4 py-2.5 text-sm font-semibold shadow-card backdrop-blur-xl ${
              t.tone === "ok" ? "bg-ink text-bg" : "bg-danger text-white"
            }`}
          >
            <span>{t.text}</span>
            {t.action && (
              <button
                className="rounded-full bg-accent px-3 py-1 text-accent-ink"
                onClick={() => {
                  t.action?.run();
                  setItems((list) => list.filter((x) => x.id !== t.id));
                }}
              >
                {t.action.label}
              </button>
            )}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export const useToast = () => useContext(ToastCtx);
