import type { Quality } from '@/api/player';

/**
 * 播放体验的纯策略（模块 12）。
 *
 * 从 `AudioEngine` 里抽出来的原因和 `playOrder.ts` 一样：这些判定最容易写错、
 * 又最难在浏览器里手工复现（要制造 3 秒缓冲、切设备、等 5 分钟窗口）。
 * 抽成无副作用函数后，`scripts/test-playback-policy.mjs` 可以逐条锁定规则。
 */

/** 弱网自动降级（BR-21-045）：连续 2 次缓冲超过 3 秒 → 降一档 */
export const STALL_THRESHOLD_MS = 3_000;
export const STALL_COUNT_TO_DOWNGRADE = 2;
export const DOWNGRADE_WINDOW_MS = 5 * 60 * 1000;
export const MAX_DOWNGRADES_IN_WINDOW = 2;
/** 停在开始后 5 秒内、或离结束不足 10 秒的远端进度不值得提示 */
export const REMOTE_MIN_POSITION = 5;
export const REMOTE_END_GUARD = 10;

/** 质量降一档；standard 已是最低，返回 null */
export function lowerQuality(quality: Quality): Quality | null {
  if (quality === 'lossless') return 'high';
  if (quality === 'high') return 'standard';
  return null;
}

/** 倍速展示：整数档补一位小数（`1.0x` / `2.0x`），与 `1.25x` 对齐 */
export function formatRate(rate: number): string {
  return `${Number.isInteger(rate) ? rate.toFixed(1) : rate}x`;
}

export interface AutoDowngradeInput {
  /** 窗口内已累计的「长缓冲」次数 */
  stallCount: number;
  effectiveQuality: Quality;
  autoQuality: boolean;
  /** 音乐房内音质由房间统一，自动降级会破坏同步 */
  inRoom: boolean;
  /** 历史上自动降级的时刻（毫秒） */
  downgradeAt: number[];
  now: number;
}

export interface AutoDowngradePlan {
  quality: Quality;
  /** 写入状态机的新时间窗口（已剔除超过 5 分钟的旧记录） */
  downgradeAt: number[];
}

/**
 * 判定是否应该自动降一档。
 *
 * 只降不升：返回值永远比当前档位低一档；调用方负责换源，下一次长缓冲再重新计数。
 * 5 分钟内最多 2 次——网络抖动时一路降到 standard 既救不了播放，还会反复打断。
 */
export function planAutoDowngrade(input: AutoDowngradeInput): AutoDowngradePlan | null {
  if (input.stallCount < STALL_COUNT_TO_DOWNGRADE) return null;
  if (!input.autoQuality || input.inRoom) return null;
  const quality = lowerQuality(input.effectiveQuality);
  if (!quality) return null;
  const recent = input.downgradeAt.filter((at) => input.now - at < DOWNGRADE_WINDOW_MS);
  if (recent.length >= MAX_DOWNGRADES_IN_WINDOW) return null;
  return { quality, downgradeAt: [...recent, input.now] };
}

export interface RemoteProgressLike {
  found: boolean;
  position: number;
  duration: number;
  fromCurrentDevice: boolean;
}

/**
 * 跨设备续播是否值得提示（FR-21-061 / BR-21-066）。
 *
 * 刻意不自动跳转：用户可能只是想从头再听一遍。以下情况不提示：
 * - 本机上报的进度（本机已有断点，再问一次很烦）；
 * - 本地已有待应用的 seek（本地断点优先）；
 * - 已经开始播了（>= 5 秒）；
 * - 开头 5 秒内、结尾 10 秒内（没有续播价值）。
 */
export function shouldPromptRemoteResume(
  progress: RemoteProgressLike,
  localPosition: number,
  hasPendingSeek: boolean,
): boolean {
  if (hasPendingSeek) return false;
  if (Number.isFinite(localPosition) && localPosition >= REMOTE_MIN_POSITION) return false;
  if (!progress.found || progress.fromCurrentDevice) return false;
  if (!Number.isFinite(progress.position) || progress.position < REMOTE_MIN_POSITION) return false;
  if (progress.duration > 0 && progress.duration - progress.position <= REMOTE_END_GUARD) return false;
  return true;
}
