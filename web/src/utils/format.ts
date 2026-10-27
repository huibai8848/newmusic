/** 展示层格式化工具。 */

/** 秒 → `mm:ss`；空值显示 `--:--` */
export function formatDuration(seconds?: number | null): string {
  if (seconds === null || seconds === undefined || seconds < 0) return '--:--';
  const m = Math.floor(seconds / 60);
  const s = Math.floor(seconds % 60);
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** 播放进度：秒 → `mm:ss`（超过 1 小时用 `h:mm:ss`） */
export function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** 大数字：12800 → `1.3万`；1560 → `1560` */
export function formatCount(count?: number | null): string {
  if (!count || count < 0) return '0';
  if (count >= 100_000_000) return `${(count / 100_000_000).toFixed(1)}亿`;
  if (count >= 10_000) return `${(count / 10_000).toFixed(1)}万`;
  return String(count);
}

/** 文件/音频地址：后端返回的是相对路径（/media/demo/demo-01.wav），dev 下由 Vite 代理 */
export function mediaUrl(url?: string | null): string | undefined {
  if (!url) return undefined;
  return url;
}

/** 兜底封面：无封面时用渐变占位，避免 AntD Image 显示破图 */
export function coverOrUndefined(cover?: string | null): string | undefined {
  return mediaUrl(cover);
}
