import dayjs from 'dayjs';

/** 运维页面的展示格式化。金额统一「分」，展示时再换成元。 */

export function fmtNumber(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return Number(value).toLocaleString('zh-CN');
}

/** 入参是 0~1 的比例，不是已经乘过 100 的百分数。 */
export function fmtRatio(value: number | null | undefined, digits = 1): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${(value * 100).toFixed(digits)}%`;
}

/** 入参已经是百分数（如 errorRate60s=3.2 表示 3.2%）。 */
export function fmtPercent(value: number | null | undefined, digits = 2): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${value.toFixed(digits)}%`;
}

export function fmtMs(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  if (value >= 1000) return `${(value / 1000).toFixed(2)} s`;
  return `${Math.round(value)} ms`;
}

export function fmtBytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

export function fmtCent(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `¥${(value / 100).toFixed(2)}`;
}

export function fmtTime(value: string | null | undefined): string {
  return value ? dayjs(value).format('MM-DD HH:mm:ss') : '—';
}

export function fmtFullTime(value: string | null | undefined): string {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';
}

export const DEGRADE_LEVELS = [
  { level: 0, name: '正常', color: 'green', hint: '全部功能可用' },
  { level: 1, name: '轻度降级', color: 'lime', hint: '关闭热搜/搜索联想等非核心读能力' },
  { level: 2, name: '中度降级', color: 'gold', hint: '关闭 AI、个性化推荐、房间礼物' },
  { level: 3, name: '重度降级', color: 'orange', hint: '关闭埋点上报与上传' },
  { level: 4, name: '保命降级', color: 'volcano', hint: '关闭聊天、评论、私信、动态' },
  { level: 5, name: '只读', color: 'red', hint: '除登录与播放上报外，写操作全部拒绝' },
] as const;

export function degradeMeta(level: number): (typeof DEGRADE_LEVELS)[number] {
  return DEGRADE_LEVELS.find((item) => item.level === level) ?? DEGRADE_LEVELS[0];
}

export const STATUS_COLORS: Record<string, string> = {
  UP: 'green',
  DOWN: 'red',
  DEGRADED: 'orange',
  firing: 'red',
  FIRING: 'red',
  silenced: 'gold',
  resolved: 'green',
  CLOSED: 'green',
  OPEN: 'red',
  HALF_OPEN: 'orange',
  RUNNING: 'blue',
  FINISHED: 'green',
  STOPPED: 'default',
  IN_PROGRESS: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  ROLLED_BACK: 'orange',
  PASS: 'green',
  VERIFIED: 'green',
  MISMATCH: 'red',
};

export function statusColor(status: string | null | undefined): string {
  return (status && STATUS_COLORS[status]) || 'default';
}
