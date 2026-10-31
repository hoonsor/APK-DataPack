// 主畫面圖示：漸層底＋白色圖形
// 不用文字：ImageResponse 預設字型沒有中日韓字，CJK 字會變成方塊或直接失敗
import { ImageResponse } from "next/og";
import type { ReactNode } from "react";
import { APP } from "./appConfig";

/** 預設圖形：麥克風（s＝尺寸 / 100） */
export function micGlyph(s: number): ReactNode {
  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center" }}>
      <div style={{ width: 22 * s, height: 36 * s, borderRadius: 11 * s, background: "white" }} />
      <div
        style={{
          width: 38 * s,
          height: 22 * s,
          marginTop: -12 * s,
          borderBottomLeftRadius: 19 * s,
          borderBottomRightRadius: 19 * s,
          border: `${4.5 * s}px solid white`,
          borderTop: "none",
        }}
      />
      <div style={{ width: 4.5 * s, height: 9 * s, background: "white" }} />
      <div style={{ width: 20 * s, height: 4.5 * s, borderRadius: 3 * s, background: "white" }} />
    </div>
  );
}

export interface AppIconOptions {
  /** true＝滿版無圓角（maskable、iOS 會自己裁圓角） */
  maskable?: boolean;
  /** 自訂圖形；參數 s＝尺寸 / 100。只能用 flex 版面與純色，不要放中文字 */
  glyph?: (s: number) => ReactNode;
  gradient?: [string, string];
}

export function appIcon(size: number, opts: AppIconOptions = {}) {
  const s = size / 100;
  const [from, to] = opts.gradient ?? APP.iconGradient;
  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          background: `linear-gradient(135deg, ${from} 0%, ${to} 100%)`,
          borderRadius: opts.maskable ? 0 : 22 * s,
        }}
      >
        {(opts.glyph ?? micGlyph)(s)}
      </div>
    ),
    { width: size, height: size },
  );
}
