import type { MetadataRoute } from "next";
import { APP } from "../lib/appConfig";

// PWA manifest（Next 會輸出成 /manifest.webmanifest）
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: APP.name,
    short_name: APP.shortName,
    description: APP.description,
    start_url: "/",
    display: "standalone",
    background_color: APP.background,
    theme_color: APP.background,
    lang: APP.lang,
    icons: [
      { src: "/icons/192", sizes: "192x192", type: "image/png", purpose: "maskable" },
      { src: "/icons/512", sizes: "512x512", type: "image/png", purpose: "maskable" },
      { src: "/icons/512", sizes: "512x512", type: "image/png", purpose: "any" },
    ],
  };
}
