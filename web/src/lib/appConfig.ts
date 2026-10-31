// App 基本資料：manifest、metadata、圖示共用，換專案只改這裡

export const APP = {
  name: "賈維斯資料包",
  shortName: "資料包",
  description: "名片、說明書、保固單、健檢報告與記住的事",
  lang: "zh-TW",
  /** 淺色背景色（manifest、iOS 狀態列） */
  background: "#151a24",
  /** 深色模式背景色 */
  backgroundDark: "#151a24",
  /** 圖示漸層（起點、終點） */
  iconGradient: ["#3cc9b6", "#2a7f86"] as [string, string],
} as const;
