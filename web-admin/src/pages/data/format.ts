/** 数据平台页面的展示格式化小工具。 */

export function formatNumber(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return value.toLocaleString('zh-CN');
}

export function formatPercent(value: number | null | undefined, digits = 2): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${value.toFixed(digits)}%`;
}

export function formatRate(value: number | null | undefined, digits = 2): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${value.toFixed(digits)}%`;
}

/** dd 日期的柱状图宽度百分比（最小 2%，避免 0 值完全看不见） */
export function barWidth(value: number, max: number): string {
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) return '2%';
  return `${Math.max(2, Math.min(100, (value / max) * 100))}%`;
}

/** nested object 里的数值转成表格行 */
export function toRows(source: Record<string, unknown> | undefined): Array<{ key: string; value: unknown }> {
  if (!source) return [];
  return Object.entries(source).map(([key, value]) => ({ key, value }));
}
