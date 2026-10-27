/**
 * 模块 18 展示规则（纯函数）。
 *
 * 这些判断前后端会各做一次：前端只负责展示与禁用，真正的权限/时长/次数
 * 校验以后端为准，避免把「隐藏按钮」误当成安全边界。
 */

export type MinorUsageLevel = 'normal' | 'warning' | 'exhausted';

/** 剩余时长不足 5 分钟时提示，0 或负数视为已用尽。 */
export function minorUsageLevel(usedMinutes: number, dailyLimitMinutes: number): MinorUsageLevel {
  const used = Number.isFinite(usedMinutes) ? usedMinutes : 0;
  const limit = Number.isFinite(dailyLimitMinutes) ? dailyLimitMinutes : 0;
  if (limit <= 0 || used >= limit) return 'exhausted';
  if (limit - used <= 5) return 'warning';
  return 'normal';
}

/** 后端时间形如 `22:00:00` / `22:00`；只取小时用于展示。 */
export function formatMinorWindow(start: string, end: string): string {
  const hour = (value: string): string => {
    const match = /^(\d{1,2})/.exec(value ?? '');
    return match ? String(Number(match[1])) : '--';
  };
  return `${hour(start)}:00 - ${hour(end)}:00`;
}

export function appealStatusLabel(status: number): string {
  switch (status) {
    case 0:
      return '待处理';
    case 1:
      return '已通过';
    case 2:
      return '已驳回';
    default:
      return `状态 ${status}`;
  }
}

export function appealStatusColor(status: number): string {
  switch (status) {
    case 0:
      return 'orange';
    case 1:
      return 'green';
    case 2:
      return 'red';
    default:
      return 'default';
  }
}

/** 内容/账号被判违规后 7 天内可申诉；已处理或超时都不可再提交。 */
export function canAppeal(status: number, createTime: string, now = Date.now()): boolean {
  if (status !== 0) return false;
  const created = new Date(createTime).getTime();
  if (!Number.isFinite(created)) return false;
  return now - created <= 7 * 24 * 60 * 60 * 1000;
}

/** AI 标识只能由后台添加，前端只负责展示。 */
export function aiLabelText(aiGenerated?: number | boolean | null): string | null {
  return aiGenerated === 1 || aiGenerated === true ? 'AI 生成' : null;
}
