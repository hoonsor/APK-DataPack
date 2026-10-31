// 導覽列共用邏輯（純函式）

/** 判斷分頁是否為目前頁面（首頁要完全相同，其他用前綴） */
export function isActiveTab(href: string, path: string): boolean {
  return href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`);
}
